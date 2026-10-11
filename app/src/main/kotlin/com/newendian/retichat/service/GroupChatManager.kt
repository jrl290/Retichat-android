package com.newendian.retichat.service

import android.content.Context
import android.util.Base64
import android.util.Log
import com.newendian.retichat.bridge.GroupEntry
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.model.hexToBytes
import com.newendian.retichat.data.model.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Network-level group chat protocol operations.
 *
 * All outbound LXMF messages are sent DIRECT (method 0x02).
 * Every message carries the group id and GROUP_SENDER so the receiver can
 * attribute the message correctly even when it arrives via a relay hop.
 * Group entries are written only through [GroupFields] (DISPLAY_NAMES.md §10:
 * the old fields 0xA0-0xA8 until the switch, then the Retichat field 0xD1).
 *
 * Every message goes as the [GroupSigner] the caller resolved for its group
 * at send time (GROUPS-DISTRO-DESIGN.md §8.1 `groupSigner`): the device, or the
 * distro D for a group whose own address is D, never captured at configure.
 * The targets are the caller's, without any self address (§8.2, §9.2).
 *
 * See RFed-spec/Group.md for the full protocol specification.
 */
class GroupChatManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val routerHandle: Long,
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
        /** The creator's answer to an accept: the other members' own accepts and leaves. */
        const val STATUS        = "status"
    }

    private val trackedHandles = ConcurrentHashMap<String, Long>()
    private val fallbackStarted = ConcurrentHashMap.newKeySet<Long>()

    /**
     * DIRECT handles whose propagated clone has not been made yet, and those
     * of them whose send ended meanwhile. The router can report 0x10 and
     * FAILED in one pass, so the FAILED can arrive before the fallback has
     * cloned the message; releasing the handle then left nothing to clone and
     * the member got no copy. Such a handle is released by the fallback once
     * it has cloned it. Guarded by [cloneLock].
     */
    private val cloning = mutableSetOf<Long>()
    private val releaseAfterClone = mutableSetOf<Long>()
    private val cloneLock = Any()

    // ---- Invite ---------------------------------------------------------

    /**
     * Send the invite chunks of a new group to [targets] (every listed member
     * but the user's own addresses), as [signer], one chunk per member key:
     * each carries the full member list [allMembers] and one key. Returns
     * whether the chunks were built: false, with nothing sent, when a listed
     * member's key is missing (the creator's own included: for a group created
     * as D it is D's key, from the distro, not the contacts).
     */
    fun sendInvites(
        signer: GroupSigner,
        groupId: String,
        groupName: String,
        allMembers: List<String>,
        memberPublicKeys: Map<String, String>,
        targets: List<String>,
    ): Boolean {
        val membersCSV = allMembers.joinToString(",")
        if (allMembers.any { memberPublicKeys[it]?.length != 128 }) {
            Log.w(TAG, "invite aborted: missing member public key")
            return false
        }
        val memberKeyEntries = allMembers.sorted().map { hash ->
            val publicKey = memberPublicKeys.getValue(hash).hexToBytes() ?: return false
            "$hash:${Base64.encodeToString(publicKey, Base64.NO_WRAP)}"
        }
        val content = ""
        targets.filter { it != signer.hex }.forEach { target ->
            memberKeyEntries.forEach { memberKeyEntry ->
                send(signer, target, content) { handle ->
                    GroupFields.set(handle, GroupEntry.ID, groupId)
                    GroupFields.set(handle, GroupEntry.NAME, groupName)
                    GroupFields.set(handle, GroupEntry.MEMBERS, membersCSV)
                    GroupFields.set(handle, GroupEntry.MEMBER_KEYS, memberKeyEntry)
                    GroupFields.set(handle, GroupEntry.ACTION, Action.INVITE)
                    GroupFields.set(handle, GroupEntry.SENDER, signer.hex)
                }
            }
        }
        return true
    }

    // ---- Accept ---------------------------------------------------------

    /**
     * Send the user's accept to [targets] (every listed member but the user's
     * own addresses), as [signer].
     *
     * Called by the recipient after accepting an invite. Every listed member
     * will add the acceptor to their "accepted" set and start including them
     * in future fanouts. Every accept carries the signer's own key
     * ([GroupEnvelopes], RFed-spec Group.md "The sender's key", James
     * 2026-10-06; as D, `D:<D's key>`, which binds on every receiver whichever
     * device of D sent it), so a member that never heard of it can verify it,
     * and the creator answers it with a `status`.
     */
    fun sendAccept(
        signer: GroupSigner,
        groupId: String,
        targets: List<String>,
    ) {
        val ownKey = ownKeyEntry(signer)
        val to = targets.filter { it != signer.hex }
        Log.i(TAG, "Accept for group ${groupId.take(8)} as ${signer.hex.take(8)} to ${to.size} member(s), own key ${if (ownKey != null) "carried" else "NOT carried"}")
        to.forEach { target ->
            send(signer, target) { handle ->
                GroupEnvelopes.write(handle, GroupEnvelopes.accept(groupId, signer.hex, ownKey))
            }
        }
    }

    /**
     * The signer's own GROUP_MEMBER_KEYS entry, `hash:base64-public-key`: the
     * form an invite chunk uses. Read from the identity, not the router, so a
     * leave sent with no connection carries it too. Null, and logged, when the
     * key cannot be read (it is always held): the accept or leave then goes
     * without it, as a build from before this change sent it, rather than not
     * at all.
     */
    private fun ownKeyEntry(signer: GroupSigner): String? {
        val publicKey = signer.publicKey
        if (publicKey == null || publicKey.size != 64) {
            Log.w(TAG, "own key of ${signer.hex.take(8)} not readable, the accept/leave carries none: ${RetichatBridge.lastError()}")
            return null
        }
        return GroupEnvelopes.ownKeyEntry(signer.hex, publicKey)
    }

    // ---- Status (the creator's answer to an accept) ---------------------

    /**
     * The creator's answer to an accept that counted and carried its sender's
     * key (RFed-spec Group.md, "The creator's answer: status"): one `status`
     * to [memberHex] alone, [statuses] being every OTHER listed member's
     * newest counted accept or leave exactly as this device received it, by
     * member hash ascending (the caller chose them; an empty list is a valid
     * answer). It goes like every group control message: DIRECT, with the
     * propagated fallback, and as a Resource when too large for a packet. Once
     * per accept received: nothing here sends it again (DESIGN_PRINCIPLES §3).
     * A status that cannot be built is not sent.
     */
    fun sendStatus(signer: GroupSigner, groupId: String, memberHex: String, statuses: List<ByteArray>) {
        sendChecked(signer, memberHex, "") { handle ->
            GroupEnvelopes.write(handle, GroupEnvelopes.status(groupId, signer.hex))
            GroupFields.setStatuses(handle, statuses)
        }
    }

    // ---- Relay done (outbound confirmation) ----------------------------

    /** Confirm to [requesterHex] that the relay was completed. */
    private fun sendRelayDone(signer: GroupSigner, groupId: String, requesterHex: String) {
        send(signer, requesterHex) { handle ->
            GroupFields.set(handle, GroupEntry.ID,       groupId)
            GroupFields.set(handle, GroupEntry.ACTION,   Action.RELAY_DONE)
            GroupFields.set(handle, GroupEntry.SENDER,   signer.hex)
            GroupFields.set(handle, GroupEntry.RELAY_DONE, true)
        }
    }

    // ---- Leave ----------------------------------------------------------

    /**
     * The user's leave of [groupId], sent when the user leaves a group and
     * when the user declines an invite (James, 2026-10-02: "Make the decline
     * message the same as the leave message"): GROUP_ID, GROUP_ACTION
     * `leave` and GROUP_SENDER this user, no content, as the web and iOS send
     * it (DISPLAY_NAMES.md §7). [members] are the targets the caller chose
     * (GroupMemberStatuses.leaveTargets: every listed member that has not
     * left, and no self address). Each copy goes like any group control message: DIRECT, with the
     * propagated fallback. Until 2026-10-05 nothing called this: a leave was
     * a plain post "left the group" with no action (audit L5), and a decline
     * sent nothing. Like an accept it carries the signer's own key
     * ([GroupEnvelopes]), so a member that never heard of it can verify it
     * before it makes the leave final.
     */
    fun sendLeave(
        signer: GroupSigner,
        groupId: String,
        members: List<String>,
    ) {
        val ownKey = ownKeyEntry(signer)
        val to = members.filter { it != signer.hex }
        Log.i(TAG, "Leave for group ${groupId.take(8)} as ${signer.hex.take(8)} to ${to.size} member(s), own key ${if (ownKey != null) "carried" else "NOT carried"}")
        to.forEach { target ->
            send(signer, target, "") { handle ->
                GroupEnvelopes.write(handle, GroupEnvelopes.leave(groupId, signer.hex, ownKey))
            }
        }
    }

    // Posts go through ChatRepository.sendGroupMessage (one row and its
    // delivery tracking per post); this class sends the control messages.

    // ---- Relay request --------------------------------------------------

    /**
     * Ask [relayerHex] to forward this message to members not yet covered.
     *
     * [alreadySeen] is the set of member hashes that have already received
     * this message; the relayer will skip them to avoid duplicates.
     */
    fun sendRelayRequest(
        signer: GroupSigner,
        groupId: String,
        content: String,
        originalSender: String,
        alreadySeen: List<String>,
        relayerHex: String,
    ) {
        send(signer, relayerHex, content) { handle ->
            GroupFields.set(handle, GroupEntry.ID,       groupId)
            GroupFields.set(handle, GroupEntry.ACTION,   Action.RELAY_REQUEST)
            GroupFields.set(handle, GroupEntry.SENDER,   originalSender)
            GroupFields.set(handle, GroupEntry.RELAY_FOR, originalSender)
            if (alreadySeen.isNotEmpty()) {
                GroupFields.set(handle, GroupEntry.RELAY_SEEN, alreadySeen.joinToString(","))
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
        signer: GroupSigner,
        groupId: String,
        groupName: String,
        content: String,
        originalSender: String,
        alreadySeen: List<String>,
        requesterHex: String,
        allAcceptedMembers: List<String>,
    ) {
        val seenSet = (alreadySeen + listOf(requesterHex, signer.hex, originalSender)).toHashSet()
        val targets = allAcceptedMembers.filter { it !in seenSet }
        val newSeen = (seenSet + targets).toList()

        Log.d(TAG, "performRelay: relaying to ${targets.size} member(s), req=$requesterHex")

        // Forward to uncovered members
        targets.forEach { target ->
            send(signer, target, content) { handle ->
                GroupFields.set(handle, GroupEntry.ID,         groupId)
                GroupFields.set(handle, GroupEntry.NAME,       groupName)
                GroupFields.set(handle, GroupEntry.SENDER,     originalSender)
                GroupFields.set(handle, GroupEntry.RELAY_FOR,  originalSender)
                GroupFields.set(handle, GroupEntry.RELAY_SEEN, newSeen.joinToString(","))
            }
        }

        // Confirm completion to the requester
        sendRelayDone(signer, groupId, requesterHex)
    }

    // ---- Private helpers ------------------------------------------------

    /** Create, configure, and send a single LXMF message to [targetHex], as [signer]. */
    private fun send(signer: GroupSigner, targetHex: String, content: String = "", configure: (Long) -> Unit) =
        sendChecked(signer, targetHex, content) { handle -> configure(handle); true }

    /**
     * [send], for a message that must not leave unless [configure] built it:
     * a false from it releases the message, and nothing is sent.
     */
    private fun sendChecked(signer: GroupSigner, targetHex: String, content: String = "", configure: (Long) -> Boolean) {
        scope.launch(Dispatchers.IO) {
            val destBytes = targetHex.hexToBytes() ?: run {
                Log.w(TAG, "send: bad hex $targetHex")
                return@launch
            }
            // Register the AppLinks spec synchronously before send so
            // the POB loop takes the AppLinks-owned DIRECT path (which
            // sends LINKREQUEST in tier-3) instead of the legacy path
            // (which may never create a link when no path is cached).
            // Never to a member that is a distro (GROUPS-DISTRO-DESIGN.md
            // §8.4): no device is behind it to prove a link, and the router
            // propagates every message to it at once.
            if (!DistroAddresses.isDistro(appContext, destBytes)) {
                RetichatBridge.appLinkOpen(routerHandle, destBytes, "lxmf", "delivery")
            }

            val handle = RetichatBridge.messageCreate(
                destHash = destBytes,
                srcHash = signer.hash,
                content = content,
                method = RetichatBridge.DeliveryMethod.DIRECT,
                identityHandle = signer.identity,
            )
            if (handle == 0L) {
                Log.w(TAG, "send: messageCreate failed for $targetHex: ${RetichatBridge.lastError()}")
                return@launch
            }
            if (!configure(handle)) {
                Log.w(TAG, "send: the message for $targetHex could not be built, not sent")
                destroyAfterDelay(handle)
                return@launch
            }
            val ok = RetichatBridge.messageSendViaAppLinks(handle)
            if (!ok) {
                Log.w(TAG, "send: messageSend failed for $targetHex: ${RetichatBridge.lastError()}")
                destroyAfterDelay(handle)
            } else {
                track(handle)
            }
        }
    }

    fun onMessageState(hash: ByteArray, state: Int): Boolean {
        val hashHex = hash.toHex()
        val handle = trackedHandles[hashHex] ?: return false
        if (state == RetichatBridge.MessageState.PROP_FALLBACK_REQUESTED) {
            if (fallbackStarted.add(handle)) {
                synchronized(cloneLock) { cloning.add(handle) }
                scope.launch(Dispatchers.IO) { startPropagationFallback(handle) }
            }
            return true
        }
        if (state == RetichatBridge.MessageState.SENT ||
            state == RetichatBridge.MessageState.DELIVERED ||
            state == RetichatBridge.MessageState.REJECTED ||
            state == RetichatBridge.MessageState.CANCELLED ||
            state == RetichatBridge.MessageState.FAILED
        ) {
            trackedHandles.remove(hashHex)
            fallbackStarted.remove(handle)
            val releaseNow = synchronized(cloneLock) {
                if (handle in cloning) { releaseAfterClone.add(handle); false } else true
            }
            if (releaseNow) RetichatBridge.messageDestroy(handle)
        }
        return true
    }

    private fun track(handle: Long) {
        val hash = RetichatBridge.messageGetHash(handle) ?: run {
            RetichatBridge.messageDestroy(handle)
            return
        }
        trackedHandles[hash.toHex()] = handle
    }

    private fun startPropagationFallback(directHandle: Long) {
        try {
            sendPropagatedClone(directHandle)
        } finally {
            // Cloned (or given up): a send that ended meanwhile left its
            // handle for this to release.
            val release = synchronized(cloneLock) {
                cloning.remove(directHandle)
                releaseAfterClone.remove(directHandle)
            }
            if (release) RetichatBridge.messageDestroy(directHandle)
        }
    }

    private fun sendPropagatedClone(directHandle: Long) {
        val node = PropagationNodeManager(
            userConfiguredHash = PropagationSync.resolvePropagationOverride(appContext),
        ).primaryNode
        if (!RetichatBridge.routerSetPropagationNode(routerHandle, node)) {
            Log.w(TAG, "group fallback: setPropagationNode failed: ${RetichatBridge.lastError()}")
            return
        }
        val propagated = RetichatBridge.messageClonePropagated(directHandle)
        if (propagated == 0L) {
            Log.w(TAG, "group fallback: clone failed: ${RetichatBridge.lastError()}")
            return
        }
        if (!RetichatBridge.messageSendViaAppLinks(propagated)) {
            Log.w(TAG, "group fallback: send failed: ${RetichatBridge.lastError()}")
            RetichatBridge.messageDestroy(propagated)
            return
        }
        // The router retains the message Arc; release the registry handle so it
        // cannot replace the direct handle under their shared canonical hash.
        RetichatBridge.messageDestroy(propagated)
        Log.i(TAG, "group propagation fallback dispatched")
    }

    private fun destroyAfterDelay(handle: Long, delayMs: Long = 0L) {
        scope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            RetichatBridge.messageDestroy(handle)
        }
    }
}

/**
 * Who a group message goes as (GROUPS-DISTRO-DESIGN.md §8.1, §8.2): its LXMF
 * source [hash] ([hex]), the identity that signs it, and that identity's public
 * key for the accept's and leave's carried key. Resolved per send from the
 * group's own address (`ChatRepository.groupSigner`): the device, or the
 * distro D loaded now when the group's own address is D. Never the fallback
 * helpers (`DistroManager.sendingIdentity`), which ignore the group.
 */
class GroupSigner(val hex: String, val hash: ByteArray, val identity: Long, val publicKey: ByteArray?)

/**
 * A distro address has no device behind it to prove a direct link; RFed fans
 * the message out from the propagation node. Its announce says so (RFed SPEC
 * §17.10), and the preference keeps what past announces and `lxma://` links
 * taught. The one test for a DM's dispatch and a group envelope's (§8.4).
 */
object DistroAddresses {
    fun isDistro(context: Context, destHash: ByteArray): Boolean =
        RetichatBridge.peerIsDistro(destHash) || UserPreferences.isDistroContact(context, destHash.toHex())
}
