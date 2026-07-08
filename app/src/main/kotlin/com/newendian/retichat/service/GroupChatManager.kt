package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.model.hexToBytes
import com.newendian.retichat.data.model.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Network-level group chat protocol operations.
 *
 * All outbound LXMF messages are sent DIRECT (method 0x02).
 * Every message carries GROUP_ID (0xA0) and GROUP_SENDER (0xA4) so the
 * receiver can attribute the message correctly even when it arrives via
 * a relay hop.
 *
 * See RFed-spec/Group.md for the full protocol specification.
 */
class GroupChatManager(
    private val scope: CoroutineScope,
    private val selfDestHash: ByteArray,
    private val routerHandle: Long,
    private val identityHandle: Long,
) {
    companion object {
        private const val TAG = "GroupChatManager"
    }

    /** GroupAction string constants (wire values — do not change). */
    object Action {
        const val INVITE        = "invite"
        const val ACCEPT        = "accept"
        const val LEAVE         = "leave"
        const val RELAY_REQUEST = "relay_req"
        const val RELAY_DONE    = "relay_done"
    }

    private val selfHex: String get() = selfDestHash.toHex()

    // ---- Invite ---------------------------------------------------------

    /**
     * Send group invites to all members except the creator (self).
     *
     * Each invite carries the full member list so recipients know who is in
     * the group without a separate directory lookup.
     */
    fun sendInvites(
        groupId: String,
        groupName: String,
        allMembers: List<String>,
    ) {
        val membersCSV = allMembers.joinToString(",")
        val content = "You've been invited to \"$groupName\""
        allMembers.filter { it != selfHex }.forEach { target ->
            send(target, content) { handle ->
                setField(handle, LxmfFields.GROUP_ID,      groupId)
                setField(handle, LxmfFields.GROUP_NAME,    groupName)
                setField(handle, LxmfFields.GROUP_MEMBERS, membersCSV)
                setField(handle, LxmfFields.GROUP_ACTION,  Action.INVITE)
                setField(handle, LxmfFields.GROUP_SENDER,  selfHex)
            }
        }
    }

    // ---- Accept ---------------------------------------------------------

    /**
     * Broadcast acceptance to every member (excluding self).
     *
     * Called by the recipient after accepting an invite. Every listed member
     * will add the acceptor to their "accepted" set and start including them
     * in future fanouts.
     */
    fun sendAccept(
        groupId: String,
        members: List<String>,
    ) {
        members.filter { it != selfHex }.forEach { target ->
            send(target) { handle ->
                setField(handle, LxmfFields.GROUP_ID,     groupId)
                setField(handle, LxmfFields.GROUP_ACTION, Action.ACCEPT)
                setField(handle, LxmfFields.GROUP_SENDER, selfHex)
            }
        }
    }

    // ---- Relay done (outbound confirmation) ----------------------------

    /** Confirm to [requesterHex] that the relay was completed. */
    private fun sendRelayDone(groupId: String, requesterHex: String) {
        send(requesterHex) { handle ->
            setField(handle, LxmfFields.GROUP_ID,       groupId)
            setField(handle, LxmfFields.GROUP_ACTION,   Action.RELAY_DONE)
            setField(handle, LxmfFields.GROUP_SENDER,   selfHex)
            setFieldBool(handle, LxmfFields.GROUP_RELAY_DONE, true)
        }
    }

    // ---- Leave ----------------------------------------------------------

    /**
     * Notify all currently-accepted members that this user is leaving.
     */
    fun sendLeave(
        groupId: String,
        acceptedMembers: List<String>,
    ) {
        acceptedMembers.filter { it != selfHex }.forEach { target ->
            send(target, "left the group") { handle ->
                setField(handle, LxmfFields.GROUP_ID,     groupId)
                setField(handle, LxmfFields.GROUP_ACTION, Action.LEAVE)
                setField(handle, LxmfFields.GROUP_SENDER, selfHex)
            }
        }
    }

    // ---- Message fanout -------------------------------------------------

    /**
     * Send a group message to every accepted member.
     *
     * [onHandle] is called once per successful send with the native message
     * handle and the target hash, so the caller can set up delivery-state
     * polling.
     */
    fun fanoutMessage(
        groupId: String,
        groupName: String,
        content: String,
        attachments: List<Pair<String, ByteArray>> = emptyList(),
        acceptedMembers: List<String>,
        onHandle: ((Long, String) -> Unit)? = null,
    ) {
        acceptedMembers.filter { it != selfHex }.forEach { target ->
            scope.launch(Dispatchers.IO) {
                val destBytes = target.hexToBytes() ?: return@launch
                val handle = RetichatBridge.messageCreate(
                    destHash = destBytes,
                    srcHash = selfDestHash,
                    content = content,
                    method = RetichatBridge.DeliveryMethod.DIRECT,
                    identityHandle = identityHandle,
                )
                if (handle == 0L) {
                    Log.w(TAG, "fanout: messageCreate failed for $target")
                    return@launch
                }
                attachments.forEach { (name, data) ->
                    RetichatBridge.messageAddAttachment(handle, name, data)
                }
                setField(handle, LxmfFields.GROUP_ID,     groupId)
                setField(handle, LxmfFields.GROUP_NAME,   groupName)
                setField(handle, LxmfFields.GROUP_SENDER, selfHex)
                val ok = RetichatBridge.messageSendViaAppLinks(handle)
                if (ok) {
                    onHandle?.invoke(handle, target)
                } else {
                    Log.w(TAG, "fanout: messageSend failed for $target")
                    destroyAfterDelay(handle)
                }
            }
        }
    }

    // ---- Relay request --------------------------------------------------

    /**
     * Ask [relayerHex] to forward this message to members not yet covered.
     *
     * [alreadySeen] is the set of member hashes that have already received
     * this message; the relayer will skip them to avoid duplicates.
     */
    fun sendRelayRequest(
        groupId: String,
        content: String,
        originalSender: String,
        alreadySeen: List<String>,
        relayerHex: String,
    ) {
        send(relayerHex, content) { handle ->
            setField(handle, LxmfFields.GROUP_ID,       groupId)
            setField(handle, LxmfFields.GROUP_ACTION,   Action.RELAY_REQUEST)
            setField(handle, LxmfFields.GROUP_SENDER,   originalSender)
            setField(handle, LxmfFields.GROUP_RELAY_FOR, originalSender)
            if (alreadySeen.isNotEmpty()) {
                setField(handle, LxmfFields.GROUP_RELAY_SEEN, alreadySeen.joinToString(","))
            }
        }
    }

    // ---- Perform relay (incoming relay_req) -----------------------------

    /**
     * Relay a group message to all members NOT yet in [alreadySeen].
     *
     * Sends a relay-done confirmation back to [requesterHex].
     */
    fun performRelay(
        groupId: String,
        groupName: String,
        content: String,
        originalSender: String,
        alreadySeen: List<String>,
        requesterHex: String,
        allAcceptedMembers: List<String>,
    ) {
        val seenSet = (alreadySeen + listOf(requesterHex, selfHex, originalSender)).toHashSet()
        val targets = allAcceptedMembers.filter { it !in seenSet }
        val newSeen = (seenSet + targets).toList()

        Log.d(TAG, "performRelay: relaying to ${targets.size} member(s), req=$requesterHex")

        // Forward to uncovered members
        targets.forEach { target ->
            send(target, content) { handle ->
                setField(handle, LxmfFields.GROUP_ID,         groupId)
                setField(handle, LxmfFields.GROUP_NAME,       groupName)
                setField(handle, LxmfFields.GROUP_SENDER,     originalSender)
                setField(handle, LxmfFields.GROUP_RELAY_FOR,  originalSender)
                setField(handle, LxmfFields.GROUP_RELAY_SEEN, newSeen.joinToString(","))
            }
        }

        // Confirm completion to the requester
        sendRelayDone(groupId, requesterHex)
    }

    // ---- Private helpers ------------------------------------------------

    /** Create, configure, and send a single LXMF message to [targetHex]. */
    private fun send(targetHex: String, content: String = "", configure: (Long) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val destBytes = targetHex.hexToBytes() ?: run {
                Log.w(TAG, "send: bad hex $targetHex")
                return@launch
            }
            // Register the AppLinks spec synchronously before send so
            // the POB loop takes the AppLinks-owned DIRECT path (which
            // sends LINKREQUEST in tier-3) instead of the legacy path
            // (which may never create a link when no path is cached).
            RetichatBridge.appLinkOpen(routerHandle, destBytes, "lxmf", "delivery")

            val handle = RetichatBridge.messageCreate(
                destHash = destBytes,
                srcHash = selfDestHash,
                content = content,
                method = RetichatBridge.DeliveryMethod.DIRECT,
                identityHandle = identityHandle,
            )
            if (handle == 0L) {
                Log.w(TAG, "send: messageCreate failed for $targetHex: ${RetichatBridge.lastError()}")
                return@launch
            }
            configure(handle)
            val ok = RetichatBridge.messageSendViaAppLinks(handle)
            if (!ok) {
                Log.w(TAG, "send: messageSend failed for $targetHex: ${RetichatBridge.lastError()}")
                destroyAfterDelay(handle)
            } else {
                // Allow 60s for delivery confirmation before releasing the handle
                destroyAfterDelay(handle, delayMs = 60_000L)
            }
        }
    }

    private fun setField(handle: Long, key: Int, value: String) {
        RetichatBridge.messageAddFieldString(handle, key, value)
    }

    private fun setFieldBool(handle: Long, key: Int, value: Boolean) {
        RetichatBridge.messageAddFieldBool(handle, key, value)
    }

    private fun destroyAfterDelay(handle: Long, delayMs: Long = 0L) {
        scope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            RetichatBridge.messageDestroy(handle)
        }
    }
}
