package com.newendian.retichat.data.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import com.newendian.retichat.GroupMemberStatuses
import com.newendian.retichat.MemberStatus
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.service.DistroCodec
import com.newendian.retichat.service.DistroManager
import com.newendian.retichat.service.RfedDistroClient
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.service.GroupChatManager
import com.newendian.retichat.service.MessageNotificationHelper
import com.newendian.retichat.service.NetworkMonitor
import com.newendian.retichat.service.PropagationNodeManager
import com.newendian.retichat.service.StackRuntime
import com.newendian.retichat.service.UserPreferences
import com.newendian.retichat.data.db.dao.ChatDao
import com.newendian.retichat.data.db.dao.ChatPreview
import com.newendian.retichat.data.db.dao.ContactDao
import com.newendian.retichat.data.db.dao.MessageDao
import com.newendian.retichat.data.db.entity.*
import com.newendian.retichat.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single source of truth for chats, messages, and contacts.
 *
 * Bridges the Room database with the Rust native layer via [RetichatBridge].
 */
class ChatRepository(
    private val appContext: Context,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val attachmentDir: File,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ChatRepo"
    }
    // ---- Own identity (set by ReticulumService after init) ----

    var selfDestHash: ByteArray = ByteArray(0)
        private set
    var routerHandle: Long = 0L
        private set
    var identityHandle: Long = 0L
        private set

    /** Lazily initialised after configure(); non-null only when routerHandle != 0. */
    private var groupChatManager: GroupChatManager? = null

    fun configure(selfHash: ByteArray, router: Long, identity: Long) {
        selfDestHash = selfHash
        routerHandle = router
        identityHandle = identity

        groupChatManager = if (router != 0L) {
            GroupChatManager(appContext, scope, selfHash, router, identity)
        } else null

        // Queued messages wait for onStackReady(), not for this: bootstrap
        // calls configure() before the message-state callback and
        // ConnectionStateManager are registered.
        // A stopped router reports nothing more on the sends it had.
        if (router == 0L) propagationFallbacks.clear()

        // Mark any stale GENERATING/OUTBOUND/SENDING messages as FAILED.
        // These are leftovers from a previous session that never completed.
        scope.launch(Dispatchers.IO) {
            messageDao.failStaleOutbound()
        }
    }

    /**
     * StackRuntime's bootstrap has finished, so the messages queued while it
     * ran go out now (§5: the queue waits for this signal). Not from
     * [configure], which bootstrap calls before the message-state callback
     * and ConnectionStateManager are registered: a message flushed from there
     * lost its router reports, its propagated fallback among them.
     */
    fun onStackReady() {
        scope.launch(Dispatchers.IO) { flushPendingMessages() }
    }

    fun onMessageState(hash: ByteArray, state: Int) {
        val hashHex = hash.toHex()
        if (finishSentCopy(hashHex, state)) return
        if (state == RetichatBridge.MessageState.DELIVERED) {
            deliveryTargets.take(hashHex)?.let { target ->
                scope.launch(Dispatchers.IO) { recordDelivered(target) }
            }
        }
        // The router reports 0x10 and FAILED holding its lock and this
        // message's, so the copy is sent from IO: a message call made on this
        // thread would wait on those locks forever.
        propagationFallbacks.onState(hashHex, state)?.let(::startPropagatedCopy)
        groupChatManager?.onMessageState(hash, state)
    }

    /**
     * The recipient proved [target]'s message. Reached from the router's
     * DELIVERED report, which can come after the poll let go: the receipt
     * timed out first (the bubble said FAILED) or the propagated fallback
     * took the bubble over. Delivery outranks every other state.
     */
    private suspend fun recordDelivered(target: DeliveryTargets.Target) {
        when (target) {
            is DeliveryTargets.Target.Row -> {
                val row = messageDao.findById(target.messageId) ?: return
                if (row.state == RetichatBridge.MessageState.DELIVERED) return
                Log.i(TAG, "delivery: ${target.messageId} proved delivered (was state=${row.state})")
                messageDao.updateStateAndProgress(target.messageId, RetichatBridge.MessageState.DELIVERED, 1f)
            }
            is DeliveryTargets.Target.GroupMember ->
                recordGroupMemberDelivered(target.groupMsgId, target.chatId, target.memberHex)
        }
    }

    /** Remember where [handle]'s delivery report lands (see [DeliveryTargets]). */
    private fun rememberDeliveryTarget(handle: Long, target: DeliveryTargets.Target) {
        val hash = RetichatBridge.messageGetHash(handle) ?: return
        deliveryTargets.remember(hash.toHex(), target)
    }

    /**
     * Apply persisted core delivery privacy settings to the running router.
     * Called once at stack startup after [configure] to seed the router with
     * the user's saved preferences (filter strangers, drop announces, etc.).
     */
    fun primeCoreDeliveryPrivacy() {
        if (routerHandle == 0L) return
        val ctx = appContext ?: return
        val enabled = UserPreferences.isFilterStrangersEnabled(ctx)
        RetichatBridge.routerSetFilterStrangers(routerHandle, enabled)
        Log.d(TAG, "primeCoreDeliveryPrivacy: filterStrangers=$enabled")
    }

    /**
     * Toggle the "filter strangers" delivery privacy setting on the live router
     * and persist the preference. Called from Settings whenever the user flips
     * the toggle.
     */
    fun setCoreFilterStrangers(enabled: Boolean) {
        if (routerHandle != 0L) {
            RetichatBridge.routerSetFilterStrangers(routerHandle, enabled)
        }
        val ctx = appContext ?: return
        UserPreferences.setFilterStrangersEnabled(ctx, enabled)
    }

    /**
     * One [flushPendingMessages] at a time; see [SerialFlush]. Declared before
     * the init block below: that block hands [networkListener] to
     * NetworkMonitor, whose callback thread may run a flush at once.
     */
    private val pendingFlush = SerialFlush()

    /**
     * Where each sent message's DELIVERED report lands; see [DeliveryTargets].
     * Before the init block too: a flush started from there records targets.
     */
    private val deliveryTargets = DeliveryTargets()

    /** When a DIRECT send's propagated copy starts; see [PropagationFallbacks]. */
    private val propagationFallbacks = PropagationFallbacks()

    /** Network-available listener, registered once. */
    private val networkListener: () -> Unit = {
        scope.launch(Dispatchers.IO) { flushPendingMessages() }
    }

    init {
        NetworkMonitor.addOnAvailableListener(networkListener)
    }

    // ---- Contacts ----

    fun contacts(): Flow<List<Contact>> =
        contactDao.allContacts().map { list ->
            list.map { it.toDomain() }
        }

    suspend fun addContact(destHash: ByteArray, name: String, publicKey: ByteArray? = null) {
        contactDao.upsert(
            ContactEntity(
                destHashHex = destHash.toHex(),
                displayName = name,
                publicKeyHex = publicKey?.toHex(),
                isNameManual = false,
            )
        )
    }

    suspend fun findContact(destHash: ByteArray): Contact? =
        contactDao.findByHash(destHash.toHex())?.toDomain()

    /** Rename a contact and update the corresponding DM chat name. */
    suspend fun renameContact(destHashHex: String, newName: String) {
        val existing = contactDao.findByHash(destHashHex)
        if (existing != null) {
            contactDao.upsert(existing.copy(displayName = newName, isNameManual = true))
        }
        // Also update the 1:1 chat display name
        val chatId = "dm_$destHashHex"
        chatDao.updateChatName(chatId, newName)
    }

    suspend fun renameGroup(chatId: String, newName: String) {
        val chat = chatDao.findById(chatId) ?: return
        if (!chat.isGroup) return
        chatDao.updateChatName(chatId, newName)
    }

    /** Group members for a given chat (reactive Flow). */
    fun groupMembers(chatId: String): Flow<List<GroupMemberEntity>> =
        messageDao.groupMembers(chatId)

    // ---- Chats ----

    fun chatPreviews(): Flow<List<ChatPreview>> = chatDao.chatPreviews()

    fun chatById(chatId: String): Flow<ChatEntity?> = chatDao.chatByIdFlow(chatId)

    suspend fun archiveChat(chatId: String) {
        chatDao.archiveChat(chatId)
    }

    suspend fun deleteChat(chatId: String) {
        val chat = chatDao.findById(chatId) ?: return
        deleteChatLocal(chat)
    }

    fun messagesForChatPaged(chatId: String) = messageDao.messagesForChatPaged(chatId)

    suspend fun getOrCreateDirectChat(contact: Contact): String {
        val chatId = directChatId(contact.destHash)
        val existing = chatDao.findById(chatId)
        if (existing == null) {
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    isGroup = false,
                    name = contact.displayName,
                    memberHashes = contact.destHashHex,
                )
            )
        } else if (existing.isArchived) {
            chatDao.unarchiveChat(chatId)
        }
        return chatId
    }

    suspend fun createGroupChat(
        name: String,
        members: List<ByteArray>,
    ): String {
        // Generate a random group ID (16 bytes → 32-char hex)
        val groupIdBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val groupIdHex = groupIdBytes.toHex()

        // Include self in canonical member list
        val allMembers = (members + selfDestHash).distinctBy { it.toHex() }
        val sorted = allMembers.sortedBy { it.toHex() }
        val hashes = sorted.joinToString(",") { it.toHex() }
        val chatId = "group_${groupIdHex.take(16)}"

        chatDao.upsert(
            ChatEntity(
                id = chatId,
                isGroup = true,
                name = name,
                memberHashes = hashes,
                groupIdHex = groupIdHex,
            )
        )

        sorted.forEach { hash ->
            val hexHash = hash.toHex()
            val contact = findContact(hash)
            val isSelf = hexHash == selfDestHash.toHex()
            messageDao.upsertGroupMember(
                GroupMemberEntity(
                    chatId = chatId,
                    destHashHex = hexHash,
                    displayName = contact?.displayName ?: hexHash.take(8),
                    inviteStatus = if (isSelf) MemberStatus.ACCEPTED else MemberStatus.INVITED,
                )
            )
        }

        // Broadcast invites to all members (except self) via GroupChatManager
        val allMemberHexes = sorted.map { it.toHex() }
        val memberPublicKeys = buildMap {
            sorted.forEach { hash ->
                val hashHex = hash.toHex()
                val publicKey = if (hashHex == selfDestHash.toHex()) {
                    RetichatBridge.identityPublicKey(identityHandle)?.toHex()
                } else {
                    contactDao.findByHash(hashHex)?.publicKeyHex
                }
                if (publicKey?.length == 128) put(hashHex, publicKey.lowercase())
            }
        }
        groupChatManager?.sendInvites(groupIdHex, name, allMemberHexes, memberPublicKeys) ?: run {
            // Fallback: offline — invites will be sent when stack comes back up
            Log.w(TAG, "createGroupChat: stack offline, invites deferred")
        }

        Log.i(TAG, "createGroupChat: id=$chatId, groupId=$groupIdHex, ${sorted.size} members")
        return chatId
    }

    // ---- Messages ----

    fun messagesForChat(chatId: String): Flow<List<MessageEntity>> =
        messageDao.messagesForChat(chatId)

    /**
     * Send all messages that were queued while offline or while the stack was
     * starting (state == OUTBOUND, no native handle). Called when the stack is
     * ready ([onStackReady]), when network becomes available, and by a send
     * that queued while the stack was starting. These can fire together, so
     * flushes are serialized and a flush that waited re-reads the queue (and
     * readiness) after the one before it has ended.
     */
    private suspend fun flushPendingMessages() = pendingFlush.run(
        pending = {
            if (!stackReady() || !NetworkMonitor.isOnline.value) {
                emptyList()
            } else {
                messageDao.pendingOutbound()
            }
        },
    ) { pending ->
        Log.i(TAG, "flushPending: ${pending.size} message(s) queued")

        for (msg in pending) {
            val chat = chatDao.findById(msg.chatId)
            if (chat == null) {
                Log.w(TAG, "flushPending: no chat ${msg.chatId} for queued msg ${msg.id} — left queued")
                continue
            }
            if (chat.isGroup) {
                // Group messages are fan-out; re-sending properly would need
                // the original member list.  For now, mark failed so the user
                // can resend manually.
                messageDao.updateState(msg.id, RetichatBridge.MessageState.FAILED)
                continue
            }

            try {
                // The same dispatch as a message sent with the stack up: a
                // queued message still goes PROPAGATED to a distro recipient,
                // keeps its attachments, and has the propagated fallback
                // behind its DIRECT attempt.
                dispatch(
                    localId = msg.id,
                    destHash = chat.memberHashes.hexToBytes(),
                    content = msg.content,
                    attachments = queuedAttachments(msg.id),
                    fromQueue = true,
                )
                Log.d(TAG, "flushPending: sent queued msg ${msg.id}")
            } catch (e: Exception) {
                Log.e(TAG, "flushPending: failed ${msg.id}: ${e.message}")
                messageDao.updateState(msg.id, RetichatBridge.MessageState.FAILED)
            }
        }
    }

    /**
     * A queued message's attachments, read back from where
     * [saveOutboundAttachments] stored them. A file that can not be read
     * throws, and the flush shows the message failed rather than send it
     * without the file.
     */
    private suspend fun queuedAttachments(msgId: String): List<Pair<String, ByteArray>> =
        messageDao.attachmentsFor(msgId).map { it.filename to File(it.localPath).readBytes() }

    /**
     * Send a text message in a 1:1 or group chat.
     */
    suspend fun sendMessage(chatId: String, content: String, attachmentFiles: List<Pair<String, ByteArray>> = emptyList()) {
        Log.d(TAG, "sendMessage: chatId=$chatId, content='${content.take(40)}', routerHandle=$routerHandle, selfDest=${selfDestHash.toHex().take(16)}")
        val chat = chatDao.findById(chatId)
        if (chat == null) {
            Log.e(TAG, "sendMessage: chat not found for id=$chatId")
            return
        }

        if (chat.isGroup) {
            sendGroupMessage(chat, content, attachmentFiles)
        } else {
            val destHash = chat.memberHashes.hexToBytes()
            Log.d(TAG, "sendMessage: direct to ${chat.memberHashes.take(16)}, destHash ${destHash.size} bytes")
            sendDirectMessage(chatId, destHash, content, attachmentFiles)
        }
    }

    private suspend fun sendDirectMessage(
        chatId: String,
        destHash: ByteArray,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
    ) {
        Log.d(TAG, "sendDirect: dest=${destHash.toHex().take(16)}, src=${selfDestHash.toHex().take(16)}, router=$routerHandle, identity=$identityHandle")

        // 1) Insert the bubble immediately so the user sees it right away
        val localId = "out_${System.currentTimeMillis()}"
        messageDao.upsert(
            MessageEntity(
                id = localId,
                chatId = chatId,
                senderHashHex = selfDestHash.toHex(),
                content = content,
                timestamp = System.currentTimeMillis(),
                isOutbound = true,
                state = RetichatBridge.MessageState.GENERATING,
            )
        )

        // Save outbound attachments to disk + DB so they display in the bubble
        if (attachments.isNotEmpty()) {
            saveOutboundAttachments(localId, attachments)
        }

        // 2) Attempt native send in the background
        scope.launch(Dispatchers.IO) {
            try {
                // Not initialized: the stack is down (e.g. app briefly
                // backgrounded) or still starting. The message is queued
                // until StackRuntime says it is ready (onStackReady), which
                // is after the message-state callback and ConnectionStateManager
                // are registered; configure() comes before both.
                if (!stackReady()) {
                    messageDao.updateState(localId, RetichatBridge.MessageState.OUTBOUND)
                    // The ready signal may have flushed the queue between the
                    // check above and the write: look again now that the row
                    // is queued, or it waits for the next trigger.
                    if (StackRuntime.isReady) flushPendingMessages()
                    if (!NetworkMonitor.isOnline.value) {
                        Log.i(TAG, "sendDirect: offline — queued for later")
                        return@launch
                    }
                    // A bootstrap already running flushes the queue when it
                    // finishes.
                    if (StackRuntime.isStarting) {
                        Log.i(TAG, "sendDirect: stack starting — queued until it is ready")
                        return@launch
                    }
                    Log.i(TAG, "sendDirect: stack not ready — queued, acquiring")
                    // Held only while it starts and the queue goes out: the
                    // stack then stays up (D7). Until 2026-09-25 this hold was
                    // never given back.
                    StackRuntime.holding(appContext) { ready ->
                        if (!ready) {
                            Log.e(TAG, "sendDirect: acquire failed — message stays queued")
                            return@holding
                        }
                        // A stack that was already up sends no ready signal.
                        if (StackRuntime.isReady) flushPendingMessages()
                    }
                    return@launch
                }
                dispatch(localId, destHash, content, attachments, fromQueue = false)
            } catch (e: Exception) {
                Log.e(TAG, "sendDirect: exception: ${e.message}", e)
                messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
            }
        }
    }

    /** The stack has finished starting and its handles are live. */
    private fun stackReady(): Boolean =
        StackRuntime.isReady && routerHandle != 0L && identityHandle != 0L

    /**
     * Send the 1:1 message [localId], just written by [sendDirectMessage] or
     * taken from the queue by [flushPendingMessages] ([fromQueue]). One path
     * for both: a distro recipient gets the PROPAGATED send; anyone else the
     * DIRECT attempt, with the propagated copy behind it started by the
     * router's reports ([PropagationFallbacks]). Returns once the message is
     * out; its state is followed on a coroutine of its own, so a flush waits
     * for the dispatches and not for every queued message's delivery.
     */
    private suspend fun dispatch(
        localId: String,
        destHash: ByteArray,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
        fromQueue: Boolean,
    ) {
        // A distro address has no device behind it to prove a direct
        // link; RFed fans the message out from the propagation node
        // (Retichat-js: propagationDelay 0 and no _sendPacket for
        // isDistro contacts). The address says so in its announce
        // (RFed SPEC §17.10); the preference is what past announces taught us.
        if (RetichatBridge.peerIsDistro(destHash) ||
            UserPreferences.isDistroContact(appContext, destHash.toHex())
        ) {
            Log.i(TAG, "dispatch: ${destHash.toHex().take(16)} is a distro address — propagating")
            val (sendSrc, sendIdentity) = DistroManager.sendingIdentity(selfDestHash, identityHandle)
            sendPropagatedCopy(
                localId,
                directHandle = 0L,
                createCopy = {
                    RetichatBridge.messageCreate(
                        destHash = destHash,
                        srcHash = sendSrc,
                        content = content,
                        method = RetichatBridge.DeliveryMethod.PROPAGATED,
                        identityHandle = sendIdentity,
                    ).also { handle ->
                        if (handle != 0L) {
                            attachments.forEach { (name, data) ->
                                RetichatBridge.messageAddAttachment(handle, name, data)
                            }
                        }
                    }
                },
                // No DIRECT attempt here, so the propagated send is this
                // message's only dispatch and carries the §17.11 sent-copy.
                onSent = { sendDistroSentCopy(destHash, sendSrc, title = "", content = content) },
            )
            return
        }

        // Register the AppLinks spec synchronously on this IO thread
        // BEFORE messageSendViaAppLinks triggers process_outbound.
        // openConversation() is fire-and-forget via a separate coroutine
        // scope and does NOT guarantee the spec is registered before the
        // POB loop checks AppLinks::contains().  If contains() is false
        // and Transport::has_path() is also false (fresh install, expired
        // cache), the legacy DIRECT path only requests a path without
        // creating a link — no LINKREQUEST is ever sent.  The synchronous
        // call below closes that race.
        // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
        RetichatBridge.appLinkOpen(routerHandle, destHash, "lxmf", "delivery")
        Log.d(TAG, "dispatch: appLinkOpen OK, submitting DIRECT")

        // Send as the distro identity when this device holds one
        // (Retichat-js sendingIdentity()): replies then reach every device.
        val (sendSrc, sendIdentity) = DistroManager.sendingIdentity(selfDestHash, identityHandle)
        val msgHandle = RetichatBridge.messageCreate(
            destHash = destHash,
            srcHash = sendSrc,
            content = content,
            method = RetichatBridge.DeliveryMethod.DIRECT,
            identityHandle = sendIdentity,
        )
        if (msgHandle == 0L) {
            Log.e(TAG, "dispatch: messageCreate FAILED for $localId: ${RetichatBridge.lastError()}")
            messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
            return
        }
        Log.d(TAG, "dispatch: messageCreate OK handle=$msgHandle")

        attachments.forEach { (name, data) ->
            RetichatBridge.messageAddAttachment(msgHandle, name, data)
        }

        // Take a queued row before it goes out: the handle is recorded only
        // if the row is still queued, and from then on it is out of
        // pendingOutbound(), so no later flush sends it or its §17.11
        // copy again, even if this send throws below.
        if (fromQueue && messageDao.claimPendingOutbound(localId, msgHandle) == 0) {
            Log.w(TAG, "dispatch: queued msg $localId already taken — not sent again")
            RetichatBridge.messageDestroy(msgHandle)
            return
        }

        val sent = RetichatBridge.messageSendViaAppLinks(msgHandle)
        Log.d(TAG, "dispatch: messageSendViaAppLinks result=$sent")
        if (!sent) {
            Log.e(TAG, "dispatch: messageSendViaAppLinks FAILED for $localId: ${RetichatBridge.lastError()}")
            messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
            return
        }
        val hashHex = RetichatBridge.messageGetHash(msgHandle)?.toHex()
        hashHex?.let { deliveryTargets.remember(it, DeliveryTargets.Target.Row(localId)) }

        // RFed SPEC §17.11: the message is out, so tell our other
        // devices. Sent here, where the DIRECT send is accepted, and
        // never from the propagated copy behind it: DIRECT plus
        // fallback is still one message and gets one copy.
        sendDistroSentCopy(destHash, sendSrc, title = "", content = content)

        val state = RetichatBridge.messageGetState(msgHandle)
        Log.d(TAG, "dispatch: post-send state=$state")

        // Point the bubble at the DIRECT handle before the fallback is
        // tracked: from then on a propagated copy can take the bubble over,
        // and this write must not undo that.
        messageDao.updateHandle(localId, msgHandle)
        if (hashHex == null) {
            // Nothing matches the router's reports to this send, so no
            // propagated copy is behind it and the bubble shows its outcome.
            Log.w(TAG, "dispatch: no hash for $localId — no propagated fallback")
            messageDao.updateState(localId, state)
        } else {
            // A DIRECT failure is not the bubble's outcome: the propagated
            // copy is (see pollMessageState).
            if (!isFailureState(state)) messageDao.updateState(localId, state)
            propagationFallbacks.track(
                hashHex, PropagationFallbacks.Send(localId, directHandle = msgHandle),
            )?.let(::startPropagatedCopy)
            if (isFailureState(state)) {
                propagationFallbacks.onPolledState(hashHex, state)?.let(::startPropagatedCopy)
                return
            }
        }

        // 3) Poll until the native message reaches a terminal state
        //    (SENT, DELIVERED, FAILED, REJECTED, CANCELLED).
        //    The proof arrives async via the link; we need to notice it.
        if (!isTerminalState(state)) {
            scope.launch(Dispatchers.IO) {
                try {
                    pollMessageState(localId, msgHandle, directHashHex = hashHex)
                } catch (e: Exception) {
                    Log.e(TAG, "dispatch: state poll failed $localId: ${e.message}")
                    failUnlessSucceeded(localId)
                }
            }
        }
    }

    /** Terminal states that won't change further. */
    private fun isTerminalState(state: Int): Boolean = state in intArrayOf(
        RetichatBridge.MessageState.SENT,
        RetichatBridge.MessageState.DELIVERED,
        RetichatBridge.MessageState.FAILED,
        RetichatBridge.MessageState.REJECTED,
        RetichatBridge.MessageState.CANCELLED,
    )

    /**
     * Success states (SENT=4, DELIVERED=8) are sticky — a direct delivery win
     * must never be overwritten by a concurrent propagation failure or any
     * other non-success state.  DELIVERED can upgrade SENT but nothing else
     * can downgrade it.
     */
    private fun isSuccessState(state: Int) =
        state == RetichatBridge.MessageState.SENT ||
        state == RetichatBridge.MessageState.DELIVERED

    /** Terminal failure states: FAILED, REJECTED, CANCELLED. */
    private fun isFailureState(state: Int) =
        state == RetichatBridge.MessageState.FAILED ||
        state == RetichatBridge.MessageState.REJECTED ||
        state == RetichatBridge.MessageState.CANCELLED

    /** Show [localId] failed, unless it already succeeded (success is sticky). */
    private suspend fun failUnlessSucceeded(localId: String) {
        messageDao.updateStateUnlessSucceeded(localId, RetichatBridge.MessageState.FAILED)
    }

    /**
     * Poll the native message handle until it reaches a terminal state.
     * Uses exponential back-off: 200ms, 300ms, 450ms, … capped at 5s.
     *
     * [initialDeadlineMs] controls how long to wait before giving up:
     *   - 60s (default) for DIRECT sends that should succeed in <5s
     *   - 600s for PROPAGATED fallback sends that can legitimately be slow
     * For large transfers (resource-based) the deadline extends to 10 min
     * and is reset whenever transfer progress advances, so that active
     * transfers are never prematurely killed.
     *
     * [directHashHex] is set when [msgHandle] is a DIRECT send with the
     * propagated fallback behind it. Its failure starts the propagated copy,
     * which is then the bubble's outcome, unless the router's report started
     * it first ([PropagationFallbacks]). A failure with no copy holding the
     * bubble is written: a copy that swaps in later replaces it.
     *
     * [directHandle] is set when [msgHandle] is that propagated copy. Both
     * attempts share one hash, so the copy's failure is not the message's
     * while the DIRECT attempt is still in flight: the bubble goes back to it
     * ([PropagationFallbacks.directKeepsTheBubble]).
     */
    private suspend fun pollMessageState(
        localId: String,
        msgHandle: Long,
        initialDeadlineMs: Long = 60_000L,
        directHashHex: String? = null,
        directHandle: Long = 0L,
    ) {
        var interval = 200L          // start at 200ms for snappy LAN feedback
        val maxInterval = 5_000L     // cap at 5s
        val longDeadline = 600_000L  // 10 min max for large transfers
        var deadline = System.currentTimeMillis() + initialDeadlineMs
        var lastProgress = 0f

        while (System.currentTimeMillis() < deadline) {
            delay(interval)

            // If sendPropagatedCopy has taken over this message
            // (replaced the DB handle), this poll is stale. Exit without
            // touching state so the new poll is the sole owner.
            // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
            val dbHandle = messageDao.findById(localId)?.nativeHandle ?: 0L
            if (dbHandle != msgHandle) {
                Log.d(TAG, "pollState: handle changed for $localId ($msgHandle→$dbHandle) — exiting stale poll")
                return
            }

            val newState = RetichatBridge.messageGetState(msgHandle)
            val progress = RetichatBridge.messageGetProgress(msgHandle)

            // If the message is actively transferring (SENDING state with
            // increasing progress), switch to the long deadline and reset
            // the timer whenever progress advances.
            if (progress > lastProgress) {
                if (progress > 0.05f) {
                    // Resource transfer in progress — extend deadline
                    deadline = System.currentTimeMillis() + longDeadline
                }
                lastProgress = progress
            }

            Log.d(TAG, "pollState: id=$localId handle=$msgHandle state=$newState progress=$progress")

            // Never let a concurrent propagation FAILED (or any non-success state)
            // overwrite a direct SENT/DELIVERED that already landed in the DB.
            // DELIVERED may upgrade SENT; nothing else may downgrade success.
            val dbState = messageDao.findById(localId)?.state ?: 0
            if (dbState == RetichatBridge.MessageState.DELIVERED) {
                // Nothing outranks delivery. The router's DELIVERED report
                // (onMessageState) can upgrade the row while this poll
                // follows the propagated copy, whose SENT must not undo it.
                Log.d(TAG, "pollState: $localId already DELIVERED — stopping poll of handle=$msgHandle")
                return
            }
            if (directHashHex != null && isFailureState(newState)) {
                val copy = propagationFallbacks.onPolledState(directHashHex, newState)
                if (copy != null) {
                    Log.i(TAG, "pollState: DIRECT $localId ended in state=$newState — propagated copy takes over")
                    startPropagatedCopy(copy)
                } else if (messageDao.updateStateOnHandleUnlessSucceeded(localId, msgHandle, newState) == 1) {
                    // The copy started earlier (or never will: untracked) and
                    // does not hold the bubble. One that could not go out left
                    // the bubble to this attempt (propagatedCopyNotSent); one
                    // still on its way replaces this when it takes over.
                    Log.i(TAG, "pollState: DIRECT $localId ended in state=$newState, no copy holds the bubble")
                }
                return
            }
            if (directHandle != 0L && isFailureState(newState)) {
                propagatedCopyFailed(localId, directHandle, newState)
                return
            }
            if (isSuccessState(dbState)) {
                if (newState == RetichatBridge.MessageState.DELIVERED &&
                    dbState == RetichatBridge.MessageState.SENT) {
                    // DELIVERED upgrades SENT — allow it
                    messageDao.updateStateAndProgress(localId, newState, progress)
                } else if (!isSuccessState(newState)) {
                    // Non-success (e.g. prop FAILED) must not overwrite direct success
                    Log.d(TAG, "pollState: $localId DB=$dbState wins over handle=$msgHandle state=$newState — stopping")
                    return
                } else {
                    messageDao.updateStateAndProgress(localId, newState, progress)
                }
            } else {
                messageDao.updateStateAndProgress(localId, newState, progress)
            }

            if (isTerminalState(newState)) {
                Log.d(TAG, "pollState: terminal state $newState for $localId")
                return
            }
            // Gentle backoff: ×1.5 keeps checks frequent for the first few seconds
            interval = (interval * 3 / 2).coerceAtMost(maxInterval)
        }
        // Timed out — mark failed so the user isn't left in limbo, unless
        // the row succeeded meanwhile by another report.
        if (isSuccessState(messageDao.findById(localId)?.state ?: 0)) return
        Log.w(TAG, "pollState: timed out for $localId, marking FAILED")
        messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
    }

    /**
     * Start [send]'s propagated copy on IO; see [PropagationFallbacks]. Not on
     * the router's callback thread: the clone locks the DIRECT message, which
     * the router holds while it reports.
     */
    private fun startPropagatedCopy(send: PropagationFallbacks.Send) {
        scope.launch(Dispatchers.IO) {
            sendPropagatedCopy(
                send.messageId,
                directHandle = send.directHandle,
                // The DIRECT message itself, sent PROPAGATED: its fields,
                // attachments and packed timestamp, so its LXMF hash, and the
                // recipient drops whichever of the two arrives second. Until
                // 2026-09-24 the copy was a new message with a new timestamp,
                // and a recipient given both showed the message twice.
                createCopy = { RetichatBridge.messageClonePropagated(send.directHandle) },
            )
        }
    }

    /**
     * Send [localId] PROPAGATED through the user-configured (or
     * randomly-chosen) LXMF propagation node, and point the bubble at it.
     * Mirrors iOS `ChatRepository.retrySendViaPropNode`.
     *
     * Either a distro recipient's only send ([directHandle] 0), or the fallback
     * behind the DIRECT attempt [directHandle], started when the router asked
     * for it (0x10; AppLinks Timer P, which is 0 s when the link status is
     * already DISCONNECTED) or when the DIRECT attempt failed first
     * ([PropagationFallbacks]). The DIRECT attempt keeps running beside it.
     * Until 2026-09-24 Android waited 5 s on its own clock and then skipped
     * the copy when the message had already failed.
     *
     * [createCopy] makes the propagated message once the checks below pass: a
     * new message for a distro recipient, a clone of the DIRECT message for
     * the fallback. [onSent] runs once it is out; the distro send gives its
     * §17.11 sent-copy there. The DIRECT path sends its own, so a fallback
     * behind it must not send a second one.
     *
     * A copy that can not go out shows the message failed (iOS does the
     * same) once no DIRECT attempt is left ([propagatedCopyNotSent]); a DIRECT
     * success can still replace that.
     */
    private suspend fun sendPropagatedCopy(
        localId: String,
        directHandle: Long,
        createCopy: () -> Long,
        onSent: () -> Unit = {},
    ) {
        try {
            val current = messageDao.findById(localId) ?: return
            // Delivered already (a late proof of the DIRECT attempt): a copy
            // would only be a duplicate for the recipient to drop.
            if (isSuccessState(current.state)) return

            if (!stackReady()) {
                Log.w(TAG, "propagated: stack not ready — $localId not sent")
                propagatedCopyNotSent(localId, directHandle)
                return
            }

            val nodeHash = selectPropagationNode()
            if (nodeHash == null) {
                propagatedCopyNotSent(localId, directHandle)
                return
            }

            Log.i(
                TAG,
                "propagated: sending $localId (state=${current.state}) " +
                    "via ${nodeHash.toHex().take(16)}"
            )

            val propHandle = createCopy()
            if (propHandle == 0L) {
                Log.e(TAG, "propagated: no message for $localId: ${RetichatBridge.lastError()}")
                propagatedCopyNotSent(localId, directHandle)
                return
            }

            val sent = RetichatBridge.messageSendViaAppLinks(propHandle)
            if (!sent) {
                Log.e(TAG, "propagated: messageSendViaAppLinks failed: ${RetichatBridge.lastError()}")
                RetichatBridge.messageDestroy(propHandle)
                propagatedCopyNotSent(localId, directHandle)
                return
            }
            // The copy's own late proof upgrades the same bubble.
            rememberDeliveryTarget(propHandle, DeliveryTargets.Target.Row(localId))
            onSent()

            // Re-point the bubble at the propagated handle and continue polling,
            // BUT only if the direct send hasn't already succeeded.  If direct
            // won while we were creating the prop copy, discard the prop handle
            // and leave the success state untouched. One conditional UPDATE:
            // a DELIVERED written between a read and a write would be lost.
            // Until 2026-09-24 a copy that was already SENT here was skipped
            // and the bubble stayed on the direct attempt's SENDING arrow; the
            // bubble now shows the copy's state, unless the copy has already
            // failed: then it never takes the bubble, as if it had not gone out.
            val newState = RetichatBridge.messageGetState(propHandle)
            if (isFailureState(newState)) {
                Log.w(TAG, "propagated: copy of $localId failed at once (state=$newState)")
                propagatedCopyNotSent(localId, directHandle)
                return
            }
            if (messageDao.takeOverUnlessSucceeded(localId, propHandle, newState) == 0) {
                Log.d(TAG, "propagated: direct already succeeded for $localId — discarding prop handle")
                RetichatBridge.messageDestroy(propHandle)
                return
            }
            if (!isTerminalState(newState)) {
                // Followed on its own coroutine: a flush that sent this (a
                // queued message to a distro recipient) must not wait for it.
                scope.launch(Dispatchers.IO) {
                    try {
                        // PROPAGATED delivery can legitimately take several minutes
                        // (the propagation node buffers and re-delivers to the
                        // recipient when they next announce).  Use the long deadline
                        // so the poll doesn't mark FAILED before Rust delivers it.
                        // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
                        pollMessageState(
                            localId, propHandle, initialDeadlineMs = 600_000L,
                            directHandle = directHandle,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "propagated: state poll failed $localId: ${e.message}")
                        failUnlessSucceeded(localId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "propagated: exception for $localId: ${e.message}", e)
            propagatedCopyNotSent(localId, directHandle)
        }
    }

    /**
     * [localId]'s propagated copy could not go out, so it never took the
     * bubble. The message has failed only if the DIRECT attempt [directHandle]
     * (0: a distro recipient's only send, there is none) has ended without
     * success. While it is in flight the bubble stays on it and its poll,
     * still running, writes the outcome ([PropagationFallbacks.directKeepsTheBubble]).
     * Until 2026-09-24 the bubble showed FAILED with the DIRECT attempt still
     * running.
     */
    private suspend fun propagatedCopyNotSent(localId: String, directHandle: Long) {
        if (directHandle != 0L &&
            PropagationFallbacks.directKeepsTheBubble(RetichatBridge.messageGetState(directHandle))
        ) {
            Log.i(TAG, "propagated: copy of $localId not sent — the DIRECT attempt keeps the bubble")
            return
        }
        failUnlessSucceeded(localId)
    }

    /**
     * The propagated copy of [localId], which held the bubble, ended in
     * [copyState]. Both attempts share one hash, so this is the message's
     * failure only if the DIRECT attempt [directHandle] has ended without
     * success too. Otherwise the bubble goes back to it (one conditional
     * UPDATE: never over a success) and a plain poll follows it, whose failure
     * is written: its own poll left when the copy took the bubble over.
     * Until 2026-09-24 the copy's failure was shown while the DIRECT attempt
     * was still running.
     */
    private suspend fun propagatedCopyFailed(localId: String, directHandle: Long, copyState: Int) {
        val directState = RetichatBridge.messageGetState(directHandle)
        if (!PropagationFallbacks.directKeepsTheBubble(directState)) {
            Log.i(TAG, "pollState: propagated copy of $localId ended in state=$copyState, DIRECT in state=$directState — failed")
            messageDao.updateStateUnlessSucceeded(localId, copyState)
            return
        }
        if (messageDao.takeOverUnlessSucceeded(localId, directHandle, directState) == 0) return
        Log.i(TAG, "pollState: propagated copy of $localId ended in state=$copyState — back to the DIRECT attempt (state=$directState)")
        pollMessageState(localId, directHandle)
    }

    /**
     * Point the router at the propagation node and return it, or null when
     * the router refused it (logged). Shared by the propagated fallback and
     * the §17.11 sent-copy, which both go out PROPAGATED.
     */
    private fun selectPropagationNode(): ByteArray? {
        // Pick the propagation node deterministically.  Priority:
        //   1. explicit user override (Settings)
        //   2. legacy `lxmf_propagation_hash` pref
        //   3. derived `lxmf.propagation` destination of the configured
        //      RFed node identity (mirrors the RFed config blob's
        //      `destinations.lxmf.propagation` value)
        //   4. random pick from PropagationNodeManager's bundled list
        // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1: a random
        // node almost always has no path on a fresh install, so the
        // first PROPAGATED send hangs at state=1 until §1 fires.  The
        // RFed-derived hash is the only one we know is reachable
        // because we already have a path to its rfed.notify aspect.
        val derivedFromRfed: String = run {
            val rfedId = UserPreferences.getEffectiveRfedNodeIdentityHash(appContext)
            if (rfedId.length == 32) {
                com.newendian.retichat.service.FcmTokenRegistrar
                    .rnsDestHash(rfedId, "lxmf", listOf("propagation"))
                    .orEmpty()
            } else ""
        }
        val override = UserPreferences.getRfedLxmfPropOverride(appContext)
            .ifEmpty { UserPreferences.getLxmfPropagationHash(appContext) }
            .ifEmpty { derivedFromRfed }
        val nodeMgr = PropagationNodeManager(
            userConfiguredHash = override.ifEmpty { null }
        )
        val nodeHash = nodeMgr.primaryNode

        if (!RetichatBridge.routerSetPropagationNode(routerHandle, nodeHash)) {
            Log.w(TAG, "setPropagationNode failed: ${RetichatBridge.lastError()}")
            return null
        }
        return nodeHash
    }

    /** §17.11 sent-copies in flight, by LXMF hash hex, until a terminal state. */
    private val sentCopyHandles = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * RFed SPEC §17.11 "Sent-message sync": after the user's message to
     * [recipient] went out as the distro ([sentAs] is the source it actually
     * used), send the distro one copy so every sibling device can file it as
     * sent. Destination and source are the distro, signed with its key; text
     * only (attachments are not copied); 0xFB/0xFC/0xFD carry the marker, the
     * recipient and this device's own address, so this device recognises and
     * drops its own echo of the fan-out. PROPAGATED at once, because only
     * RFed answers for the distro address. iOS counterpart: ChatRepository.swift.
     *
     * Fire-and-forget: no bubble, and the copy never touches the user's
     * message state. Its outcome arrives through [onMessageState] and is
     * only logged, so a failed copy is visible without a retry (§3).
     */
    private fun sendDistroSentCopy(recipient: ByteArray, sentAs: ByteArray, title: String, content: String) {
        val recipientHex = recipient.toHex()
        if (!DistroCodec.shouldSendSentCopy(sentAs.toHex(), DistroManager.deliveryHashHex, recipientHex)) return
        val distro = DistroManager.deliveryHash ?: return
        val distroHandle = DistroManager.identityHandle
        val deviceHex = selfDestHash.toHex()
        if (distroHandle == 0L || !DistroCodec.isHex32(deviceHex)) {
            Log.w(TAG, "sent-copy: distro or device identity gone — no copy for ${recipientHex.take(8)}")
            return
        }
        // The router encrypts to the distro by recalling its public key. We
        // hold that key, so remember it rather than depend on having heard
        // RFed's announce of our own distro (§5: readiness before the send).
        if (!RetichatBridge.transportIdentityKnown(distro)) {
            val pub = RetichatBridge.identityPublicKey(distroHandle)
            if (pub == null || !RetichatBridge.identityRememberLxmfDelivery(distro, pub)) {
                Log.e(TAG, "sent-copy: could not remember the distro's key: ${RetichatBridge.lastError()}")
                return
            }
        }
        selectPropagationNode() ?: return
        val h = RetichatBridge.messageCreate(
            destHash = distro,
            srcHash = distro,
            content = content,
            title = title,
            method = RetichatBridge.DeliveryMethod.PROPAGATED,
            identityHandle = distroHandle,
        )
        if (h == 0L) {
            Log.e(TAG, "sent-copy: messageCreate failed: ${RetichatBridge.lastError()}")
            return
        }
        val marked =
            RetichatBridge.messageAddFieldString(h, LxmfFields.FIELD_CUSTOM_TYPE, LxmfFields.DISTRO_SENT_TYPE) &&
            RetichatBridge.messageAddFieldString(h, LxmfFields.FIELD_CUSTOM_DATA, recipientHex) &&
            RetichatBridge.messageAddFieldString(h, LxmfFields.FIELD_CUSTOM_META, deviceHex)
        if (!marked) {
            Log.e(TAG, "sent-copy: marking fields failed: ${RetichatBridge.lastError()}")
            RetichatBridge.messageDestroy(h)
            return
        }
        if (!RetichatBridge.messageSendViaAppLinks(h)) {
            Log.e(TAG, "sent-copy: messageSendViaAppLinks failed: ${RetichatBridge.lastError()}")
            RetichatBridge.messageDestroy(h)
            return
        }
        val hash = RetichatBridge.messageGetHash(h)?.toHex()
        if (hash == null) {
            Log.w(TAG, "sent-copy for ${recipientHex.take(8)} submitted without a hash — its outcome will not be logged")
            RetichatBridge.messageDestroy(h)
            return
        }
        Log.i(TAG, "sent-copy for ${recipientHex.take(8)} submitted (${hash.take(16)})")
        sentCopyHandles[hash] = h
        // A state that landed before the entry above had nobody listening.
        finishSentCopy(hash, RetichatBridge.messageGetState(h))
    }

    /**
     * Log a §17.11 sent-copy's terminal state and release its handle.
     * Returns true when [hashHex] is a sent-copy, so other state consumers
     * skip it.
     */
    private fun finishSentCopy(hashHex: String, state: Int): Boolean {
        if (!sentCopyHandles.containsKey(hashHex)) return false
        if (!isTerminalState(state)) return true
        val handle = sentCopyHandles.remove(hashHex) ?: return true
        if (isSuccessState(state)) {
            Log.i(TAG, "sent-copy ${hashHex.take(16)} accepted (state=$state)")
        } else {
            Log.w(TAG, "sent-copy ${hashHex.take(16)} ended in state=$state — sibling devices will not see this message")
        }
        RetichatBridge.messageDestroy(handle)
        return true
    }

    private suspend fun sendGroupMessage(
        chat: ChatEntity,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
    ) {
        val selfHex = selfDestHash.toHex()
        val groupIdHex = chat.groupIdHex ?: return

        val memberHexes = GroupMemberStatuses.acceptedMemberHexes(
            members = messageDao.groupMembersList(chat.id),
            selfHex = selfHex,
        )

        // Create the outbound message record once (shared ID for the group msg)
        val groupMsgId = "grp_${System.currentTimeMillis()}_${(0..999).random()}"

        messageDao.upsert(
            MessageEntity(
                id = groupMsgId,
                chatId = chat.id,
                senderHashHex = selfHex,
                content = content,
                timestamp = System.currentTimeMillis(),
                isOutbound = true,
                state = RetichatBridge.MessageState.SENDING,
            )
        )

        // Save outbound attachments to disk + DB so they display in the bubble
        if (attachments.isNotEmpty()) {
            saveOutboundAttachments(groupMsgId, attachments)
        }

        // Track delivery per member
        memberHexes.forEach { memberHex ->
            messageDao.upsertTracking(
                DeliveryTrackingEntity(
                    messageId = groupMsgId,
                    chatId = chat.id,
                    memberHashHex = memberHex,
                )
            )
        }

        // Fanout: send individually to each member with group metadata fields
        var allSent = true
        memberHexes.forEach { memberHex ->
            scope.launch(Dispatchers.IO) {
                val destHash = memberHex.hexToBytes()
                // Register the AppLinks spec synchronously before send so
                // the POB loop takes the AppLinks-owned DIRECT path.
                RetichatBridge.appLinkOpen(routerHandle, destHash, "lxmf", "delivery")

                val handle = RetichatBridge.messageCreate(
                    destHash = destHash,
                    srcHash = selfDestHash,
                    content = content,
                    method = RetichatBridge.DeliveryMethod.DIRECT,
                    identityHandle = identityHandle,
                )
                if (handle != 0L) {
                    // Attach group metadata
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_ID, groupIdHex)
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_SENDER, selfHex)

                    attachments.forEach { (name, data) ->
                        RetichatBridge.messageAddAttachment(handle, name, data)
                    }

                    val sent = RetichatBridge.messageSendViaAppLinks(handle)
                    if (sent) {
                        rememberDeliveryTarget(handle, DeliveryTargets.Target.GroupMember(groupMsgId, chat.id, memberHex))
                        // Poll for delivery proof per-member
                        pollGroupMemberDelivery(groupMsgId, chat.id, memberHex, handle)
                    } else {
                        Log.e(TAG, "sendGroupMessage: failed to send to $memberHex")
                    }
                }
            }
        }

        // Optimistically mark as SENT (individual proofs update tracking)
        messageDao.updateState(groupMsgId, RetichatBridge.MessageState.SENT)
    }

    /**
     * Poll a single group-member send until delivered or timed out.
     * When delivered, update tracking and check if all members are done.
     */
    private suspend fun pollGroupMemberDelivery(
        groupMsgId: String, chatId: String, memberHex: String, handle: Long,
    ) {
        var interval = 200L
        val maxInterval = 5_000L
        val deadline = System.currentTimeMillis() + 60_000L

        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            val state = RetichatBridge.messageGetState(handle)
            if (isTerminalState(state)) {
                if (state == RetichatBridge.MessageState.DELIVERED) {
                    recordGroupMemberDelivered(groupMsgId, chatId, memberHex)
                }
                return
            }
            interval = (interval * 3 / 2).coerceAtMost(maxInterval)
        }
    }

    private suspend fun recordGroupMemberDelivered(groupMsgId: String, chatId: String, memberHex: String) {
        messageDao.upsertTracking(
            DeliveryTrackingEntity(
                messageId = groupMsgId,
                chatId = chatId,
                memberHashHex = memberHex,
                delivered = true,
                deliveredAt = System.currentTimeMillis(),
            )
        )
        // Check if ALL members delivered
        val undelivered = messageDao.undeliveredFor(groupMsgId)
        if (undelivered.isEmpty()) {
            messageDao.updateState(groupMsgId, RetichatBridge.MessageState.DELIVERED)
            Log.i(TAG, "Group msg $groupMsgId: all members delivered")
        }
    }

    /** Called from the Rust delivery callback (background thread). */
    fun onMessageReceived(
        hash: ByteArray,
        srcHash: ByteArray,
        destHash: ByteArray,
        title: String,
        content: String,
        timestamp: Double,
        signatureValid: Boolean,
        fieldsRaw: ByteArray = ByteArray(0),
    ) {
        val fields = LxmfFields.decode(fieldsRaw)
        val groupId = fields.getString(LxmfFields.GROUP_ID)
        Log.i(TAG, "onMessageReceived: src=${srcHash.toHex().take(16)}, groupId=$groupId, content='${content.take(40)}'")
        // A distro identity transfer from another of our devices (RFed SPEC §17.9):
        // LXMF's custom pair with our type string; not a message to display —
        // offer to import it. Checked before anything else, as the web client does.
        if (fields.getString(LxmfFields.FIELD_CUSTOM_TYPE) == LxmfFields.DISTRO_TRANSFER_TYPE) {
            fields.getString(LxmfFields.FIELD_CUSTOM_DATA)?.let { keyHex ->
                RfedDistroClient.offerTransfer(srcHash.toHex(), keyHex)
                return
            }
        }
        scope.launch(Dispatchers.IO) {
            val srcHex = srcHash.toHex()
            val msgId = hash.toHex()

            // Auto-create contact if unknown.
            // Prefer FIELD_SENDER_NAME from the message fields (privacy-preserving,
            // only message recipients see it), fall back to truncated hash.
            val senderName = fields.getString(LxmfFields.FIELD_SENDER_NAME)
            if (contactDao.findByHash(srcHex) == null) {
                contactDao.upsert(
                    ContactEntity(
                        destHashHex = srcHex,
                        displayName = senderName ?: srcHex.take(8),
                    )
                )
            } else if (senderName != null) {
                // Update existing contact name if not manually set
                val existing = contactDao.findByHash(srcHex)
                if (existing != null && !existing.isNameManual && existing.displayName != senderName) {
                    contactDao.upsert(existing.copy(displayName = senderName))
                }
            }

            if (groupId != null) {
                handleGroupMessage(msgId, srcHex, content, timestamp, fields)
            } else {
                handleDirectMessage(msgId, srcHash, srcHex, content, timestamp, fields)
            }
        }
    }

    /**
     * A message that reached us through the distro fan-out (already decrypted
     * with the distro key by RfedDistroClient). The fan-out never hands over
     * the LXMF hash, so the id is derived from source, timestamp and content;
     * RfedDistroClient has already deduplicated on source+timestamp.
     */
    fun onDistroMessageReceived(srcHash: ByteArray, title: String, content: String, timestamp: Double) {
        val srcHex = srcHash.toHex()
        val msgId = DistroCodec.messageId(srcHex, timestamp, content)
        Log.i(TAG, "onDistroMessageReceived: src=${srcHex.take(16)} via=distro content='${content.take(40)}'")
        scope.launch(Dispatchers.IO) {
            if (contactDao.findByHash(srcHex) == null) {
                contactDao.upsert(ContactEntity(destHashHex = srcHex, displayName = srcHex.take(8)))
            }
            handleDirectMessage(msgId, srcHash, srcHex, content, timestamp, LxmfFields.decode(ByteArray(0)))
        }
    }

    /**
     * RFed SPEC §17.11: a message another of our devices sent as the distro
     * to [recipientHex], reaching us as the distro's sent-copy (RfedDistroClient
     * has already unwrapped it, deduplicated on source+timestamp, dropped our
     * own echo and checked the recipient). Filed as OUR outgoing message in
     * the direct chat with the recipient, the way iOS ChatRepository.swift
     * files it: sender is the distro (it is "me"), state SENT and never
     * DELIVERED (only the sending device could learn that), and the id is
     * derived like any distro fan-out message (DistroCodec.sentCopyMessageId)
     * so a copy that arrives both live and via /rfed/pull is stored once. No
     * contact is created and no notification is posted: the user wrote this
     * message.
     */
    fun onDistroSentCopy(recipientHex: String, title: String, content: String, timestamp: Double) {
        val distroHex = DistroManager.deliveryHashHex ?: run {
            Log.w(TAG, "onDistroSentCopy: no distro loaded — dropped copy for ${recipientHex.take(8)}")
            return
        }
        val recipient = DistroCodec.hexToBytes(recipientHex) ?: return
        val msgId = DistroCodec.sentCopyMessageId(distroHex, timestamp, content)
        Log.i(TAG, "onDistroSentCopy: to=${recipientHex.take(16)} content='${content.take(40)}'")
        scope.launch(Dispatchers.IO) {
            if (messageDao.findById(msgId) != null) {
                Log.d(TAG, "onDistroSentCopy: dup msgId=${msgId.take(16)}, skipping")
                return@launch
            }
            val chatId = directChatId(recipient)
            val existingChat = chatDao.findById(chatId)
            if (existingChat == null) {
                val contact = contactDao.findByHash(recipientHex)
                chatDao.upsert(
                    ChatEntity(
                        id = chatId,
                        isGroup = false,
                        name = contact?.displayName ?: recipientHex.take(8),
                        memberHashes = recipientHex,
                    )
                )
            } else if (existingChat.isArchived) {
                chatDao.unarchiveChat(chatId)
            }
            messageDao.upsert(
                MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    senderHashHex = distroHex,
                    content = content,
                    timestamp = (timestamp * 1000).toLong(),
                    isOutbound = true,
                    state = RetichatBridge.MessageState.SENT,
                )
            )
        }
    }

    // ---- Group message handling ----

    private suspend fun handleGroupMessage(
        msgId: String,
        srcHex: String,
        content: String,
        timestamp: Double,
        fields: LxmfFields,
    ) {
        val groupId = fields.getString(LxmfFields.GROUP_ID)
        val groupMembers = fields.getString(LxmfFields.GROUP_MEMBERS)
        val groupName = fields.getString(LxmfFields.GROUP_NAME)
        val groupSender = fields.getString(LxmfFields.GROUP_SENDER)
        val groupAction = fields.getString(LxmfFields.GROUP_ACTION)
        val groupRelayFor = fields.getString(LxmfFields.GROUP_RELAY_FOR)
        val groupRelaySeen = fields.getString(LxmfFields.GROUP_RELAY_SEEN)
        val groupMemberKeys = fields.getString(LxmfFields.GROUP_MEMBER_KEYS)

        // Determine the actual sender (may be relayed on behalf of another member)
        val actualSenderHex = groupSender ?: srcHex

        // Stranger filter: only accept invites from known contacts
        if (groupAction == GroupChatManager.Action.INVITE) {
            if (contactDao.findByHash(srcHex) == null) {
                Log.i(TAG, "Dropped group invite from stranger ${srcHex.take(8)}")
                return
            }
        }

        // Look up existing group chat by groupId
        var chat = chatDao.findByGroupId(groupId!!)

        if (groupAction == GroupChatManager.Action.INVITE && groupMembers != null && groupMemberKeys != null) {
            val invitedMembers = (groupMembers.split(",") + srcHex).toSet()
            groupMemberKeys.split(",").forEach { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size != 2) return@forEach
                val memberHash = parts[0].lowercase()
                val encodedPublicKey = parts[1]
                if (memberHash !in invitedMembers || memberHash.length != 32) return@forEach
                val hashBytes = memberHash.hexToBytes() ?: return@forEach
                val publicKey = runCatching { Base64.decode(encodedPublicKey, Base64.DEFAULT) }.getOrNull()
                    ?.takeIf { it.size == 64 } ?: return@forEach
                val publicKeyHex = publicKey.toHex()
                if (!RetichatBridge.identityRememberLxmfDelivery(hashBytes, publicKey)) {
                    Log.w(TAG, "Ignored mismatched group member key for ${memberHash.take(8)}")
                    return@forEach
                }
                val existing = contactDao.findByHash(memberHash)
                contactDao.upsert(
                    existing?.copy(publicKeyHex = publicKeyHex)
                        ?: ContactEntity(destHashHex = memberHash, displayName = memberHash.take(8), publicKeyHex = publicKeyHex)
                )
            }
        }

        if (chat == null && groupMembers != null && groupAction == GroupChatManager.Action.INVITE) {
            // Incoming invite — create the local group record in PENDING state
            // (self inviteStatus = invited; user must Accept/Decline via the UI)
            val name = groupName ?: "Group"
            val chatId = "group_${groupId.take(16)}"

            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    isGroup = true,
                    name = name,
                    memberHashes = groupMembers,
                    groupIdHex = groupId,
                )
            )

            (groupMembers.split(",") + srcHex).filter { it.isNotEmpty() }.distinct().forEach { memberHex ->
                val contact = contactDao.findByHash(memberHex)
                messageDao.upsertGroupMember(
                    GroupMemberEntity(
                        chatId = chatId,
                        destHashHex = memberHex,
                        displayName = contact?.displayName ?: memberHex.take(8),
                        inviteStatus = if (memberHex == srcHex) {
                            MemberStatus.ACCEPTED
                        } else {
                            MemberStatus.INVITED
                        },
                    )
                )
            }

            chat = chatDao.findById(chatId)
            Log.i(TAG, "Created PENDING group from invite: chatId=$chatId, groupId=$groupId")
        }

        if (chat == null) {
            Log.w(TAG, "handleGroupMessage: unknown groupId=$groupId, ignoring")
            return
        }

        when (groupAction) {
            GroupChatManager.Action.INVITE -> {
                // The authenticated invite source implicitly accepted when it
                // created and sent the invitation. Apply this on duplicate
                // pending invites as well.
                val inviter = messageDao.groupMembersList(chat.id)
                    .firstOrNull { it.destHashHex == srcHex }
                if (inviter == null) {
                    val contact = contactDao.findByHash(srcHex)
                    messageDao.upsertGroupMember(
                        GroupMemberEntity(
                            chatId = chat.id,
                            destHashHex = srcHex,
                            displayName = contact?.displayName ?: srcHex.take(8),
                            inviteStatus = MemberStatus.ACCEPTED,
                        )
                    )
                } else {
                    messageDao.setGroupMemberStatus(chat.id, srcHex, MemberStatus.ACCEPTED)
                }
                // Insert a system invite message (idempotent on inviteMsgId)
                val senderName = contactDao.findByHash(actualSenderHex)?.displayName
                    ?: actualSenderHex.take(8)
                val inviteMsgId = "inv_${groupId.take(16)}"
                val firstInviteChunk = messageDao.findById(inviteMsgId) == null
                messageDao.upsert(
                    MessageEntity(
                        id = inviteMsgId,
                        chatId = chat.id,
                        senderHashHex = actualSenderHex,
                        content = "$senderName invited you to \"${chat.name}\" — tap Accept or Decline below",
                        timestamp = (timestamp * 1000).toLong(),
                        isOutbound = false,
                        state = RetichatBridge.MessageState.DELIVERED,
                    )
                )
                if (firstInviteChunk && RetichatApp.activeChatId != chat.id) {
                    MessageNotificationHelper.notify(
                        appContext,
                        "Group invite",
                        "$senderName invited you to \"${chat.name}\"",
                        chat.id,
                    )
                }
            }

            GroupChatManager.Action.ACCEPT -> {
                Log.i(TAG, "${actualSenderHex.take(8)} accepted group ${chat.id}")
                messageDao.setGroupMemberStatus(chat.id, actualSenderHex, MemberStatus.ACCEPTED)

                // Insert a system "X joined the group" message (idempotent)
                val joinerName = contactDao.findByHash(actualSenderHex)?.displayName
                    ?: actualSenderHex.take(8)
                val sysId = "acc_${actualSenderHex.take(8)}_${groupId.take(8)}"
                messageDao.upsert(
                    MessageEntity(
                        id = sysId,
                        chatId = chat.id,
                        senderHashHex = actualSenderHex,
                        content = "$joinerName joined the group",
                        timestamp = (timestamp * 1000).toLong(),
                        isOutbound = false,
                        state = RetichatBridge.MessageState.DELIVERED,
                    )
                )
            }

            GroupChatManager.Action.LEAVE -> {
                Log.i(TAG, "${actualSenderHex.take(8)} left group ${chat.id}")
                messageDao.setGroupMemberStatus(chat.id, actualSenderHex, MemberStatus.LEFT)
                val leaverName = contactDao.findByHash(actualSenderHex)?.displayName
                    ?: actualSenderHex.take(8)
                messageDao.upsert(
                    MessageEntity(
                        id = msgId,
                        chatId = chat.id,
                        senderHashHex = actualSenderHex,
                        content = "$leaverName left the group",
                        timestamp = (timestamp * 1000).toLong(),
                        isOutbound = false,
                        state = RetichatBridge.MessageState.DELIVERED,
                    )
                )
            }

            GroupChatManager.Action.RELAY_REQUEST -> {
                val alreadySeen = groupRelaySeen
                    ?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                val acceptedMembers = GroupMemberStatuses.acceptedMemberHexes(
                    members = messageDao.groupMembersList(chat.id),
                    selfHex = selfHex(),
                )
                groupChatManager?.performRelay(
                    groupId   = groupId,
                    groupName = chat.name,
                    content   = content,
                    originalSender         = actualSenderHex,
                    alreadySeen            = alreadySeen,
                    requesterHex           = srcHex,
                    allAcceptedMembers     = acceptedMembers,
                ) ?: Log.w(TAG, "relay_req: stack offline, cannot relay")
            }

            GroupChatManager.Action.RELAY_DONE -> {
                Log.i(TAG, "Relay done confirmed for group ${chat.id} by ${srcHex.take(8)}")
                // Future: update per-member tracking for the relayed message
            }

            null -> {
                // Regular group content message — display it
                val attachments = fields.getFileAttachments()
                if (content.isNotBlank() || attachments.isNotEmpty()) {
                    messageDao.upsert(
                        MessageEntity(
                            id = msgId,
                            chatId = chat.id,
                            senderHashHex = actualSenderHex,
                            content = content,
                            timestamp = (timestamp * 1000).toLong(),
                            isOutbound = false,
                            state = RetichatBridge.MessageState.DELIVERED,
                        )
                    )
                    saveInboundAttachments(msgId, fields)

                    if (RetichatApp.activeChatId != chat.id) {
                        val contact = contactDao.findByHash(actualSenderHex)
                        val senderName = contact?.displayName ?: actualSenderHex.take(8)
                        val notifText = if (content.isNotBlank()) content
                                        else "\uD83D\uDCCE ${attachments.size} attachment(s)"
                        MessageNotificationHelper.notify(
                            appContext, "$senderName (${chat.name})", notifText, chat.id,
                        )
                    }
                }
            }

            else -> Log.w(TAG, "handleGroupMessage: unrecognised action=$groupAction")
        }
    }

    // ---- Group invite accept / decline ----

    /**
     * Reactive flow indicating whether [chatId] is a group with an
     * unaccepted invite (self member's inviteStatus is invited).
     */
    fun isPendingGroupInvite(chatId: String): Flow<Boolean> {
        val selfHex = selfDestHash.toHex()
        return messageDao.groupMembers(chatId).map { members ->
            GroupMemberStatuses.isPendingInvite(members, selfHex)
        }
    }

    /**
     * Accept a pending group invite: mark self accepted, broadcast
     * ACCEPT to all members, and insert a confirmation system message.
     */
    suspend fun acceptGroupInvite(chatId: String) {
        val chat = chatDao.findById(chatId) ?: return
        if (!chat.isGroup) return
        val groupId = chat.groupIdHex ?: return
        val selfHex = selfDestHash.toHex()

        val allMembers = messageDao.groupMembersList(chatId)
            .map { it.destHashHex }
            .filter { it.isNotEmpty() }
            .distinct()
        val missingKeys = allMembers.filter { it != selfHex }
            .filter { contactDao.findByHash(it)?.publicKeyHex?.length != 128 }
        if (missingKeys.isNotEmpty()) {
            Log.i(TAG, "acceptGroupInvite deferred: still receiving ${missingKeys.size} member key(s)")
            return
        }

        messageDao.setGroupMemberStatus(chatId, selfHex, MemberStatus.ACCEPTED)
        groupChatManager?.sendAccept(groupId, allMembers)
            ?: Log.w(TAG, "acceptGroupInvite: stack offline, accept will not be broadcast")

        messageDao.upsert(
            MessageEntity(
                id = "joined_${groupId.take(16)}",
                chatId = chatId,
                senderHashHex = selfHex,
                content = "You joined \"${chat.name}\"",
                timestamp = System.currentTimeMillis(),
                isOutbound = true,
                state = RetichatBridge.MessageState.DELIVERED,
            )
        )
        Log.i(TAG, "acceptGroupInvite: accepted ${chat.id} (${allMembers.size} members)")
    }

    /**
     * Decline a pending group invite: silently delete the local chat,
     * messages, and member records. (No LEAVE is sent because nobody
     * has confirmed us as a member yet.)
     */
    suspend fun declineGroupInvite(chatId: String) {
        val chat = chatDao.findById(chatId) ?: return
        if (!chat.isGroup) return
        Log.i(TAG, "declineGroupInvite: removing ${chat.id}")
        deleteChatLocal(chat)
    }

    private suspend fun handleDirectMessage(
        msgId: String,
        srcHash: ByteArray,
        srcHex: String,
        content: String,
        timestamp: Double,
        fields: LxmfFields,
    ) {
        // Dedup: the same LXMF message (deterministic hash) can arrive via
        // multiple transports in quick succession — PROPAGATED first, then
        // the sender's DIRECT backchannel.  The message hash is the primary
        // key; if we've already stored this message, skip silently to avoid
        // a duplicate bubble and a second notification.
        // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
        if (messageDao.findById(msgId) != null) {
            Log.d(TAG, "handleDirectMessage: dup msgId=${msgId.take(16)}, skipping")
            return
        }

        // Handle "STOP: <groupId>" — remove sender from that group
        // Lenient: case-insensitive, optional colon/dash, flexible whitespace
        val stopMatch = Regex("^\\s*stop[:\\-\\s]+([0-9a-f]{4,})\\s*$", RegexOption.IGNORE_CASE)
            .find(content.trim())
        if (stopMatch != null) {
            val prefix = stopMatch.groupValues[1].lowercase()
            val chat = chatDao.findByGroupIdPrefix(prefix + "%")
            if (chat != null) {
                Log.i(TAG, "STOP received from $srcHex for group ${chat.id}")
                messageDao.setGroupMemberStatus(chat.id, srcHex, MemberStatus.LEFT)
                val leaverName = contactDao.findByHash(srcHex)?.displayName ?: srcHex.take(8)
                // Insert a system message in the group chat
                messageDao.upsert(
                    MessageEntity(
                        id = msgId,
                        chatId = chat.id,
                        senderHashHex = srcHex,
                        content = "$leaverName left the group",
                        timestamp = (timestamp * 1000).toLong(),
                        isOutbound = false,
                        state = RetichatBridge.MessageState.DELIVERED,
                    )
                )
                return
            }
        }

        val chatId = directChatId(srcHash)
        val existingChat = chatDao.findById(chatId)
        if (existingChat == null) {
            val contact = contactDao.findByHash(srcHex)
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    isGroup = false,
                    name = contact?.displayName ?: srcHex.take(8),
                    memberHashes = srcHex,
                )
            )
        } else if (existingChat.isArchived) {
            chatDao.unarchiveChat(chatId)
        }

        messageDao.upsert(
            MessageEntity(
                id = msgId,
                chatId = chatId,
                senderHashHex = srcHex,
                content = content,
                timestamp = (timestamp * 1000).toLong(),
                isOutbound = false,
                state = RetichatBridge.MessageState.DELIVERED,
            )
        )

        // Save file attachments to disk + DB
        saveInboundAttachments(msgId, fields)

        // Show notification unless the user is viewing this chat
        if (RetichatApp.activeChatId != chatId) {
            val contact = contactDao.findByHash(srcHex)
            val senderName = contact?.displayName ?: srcHex.take(8)
            Log.i(TAG, "Posting notification: sender=$senderName")
            MessageNotificationHelper.notify(appContext, senderName, content, chatId)
        }
    }

    /**
     * Leave a group chat — broadcast a leave message to accepted members,
     * then delete the local chat state.
     */
    suspend fun leaveGroupChat(chatId: String) {
        val chat = chatDao.findById(chatId) ?: return
        if (!chat.isGroup) return
        val groupIdHex = chat.groupIdHex ?: return
        val selfHex = selfDestHash.toHex()

        val otherMembers = GroupMemberStatuses.acceptedMemberHexes(
            members = messageDao.groupMembersList(chatId),
            selfHex = selfHex,
        )

        if (groupChatManager == null) {
            deleteChatLocal(chat)
            return
        }

        // Broadcast leave to all members
        otherMembers.forEach { memberHex ->
            scope.launch(Dispatchers.IO) {
                val destHash = memberHex.hexToBytes()
                // Register the AppLinks spec synchronously before send.
                RetichatBridge.appLinkOpen(routerHandle, destHash, "lxmf", "delivery")

                val handle = RetichatBridge.messageCreate(
                    destHash = destHash,
                    srcHash = selfDestHash,
                    content = "left the group",
                    method = RetichatBridge.DeliveryMethod.DIRECT,
                    identityHandle = identityHandle,
                )
                if (handle != 0L) {
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_ID, groupIdHex)
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_SENDER, selfHex)
                    // A leave is signalled by having GROUP_ID + GROUP_SENDER + content="left the group"
                    // but no GROUP_MEMBERS (which distinguishes it from a create message)
                    RetichatBridge.messageSendViaAppLinks(handle)
                }
            }
        }

        deleteChatLocal(chat)
        Log.i(TAG, "Left group $chatId")
    }

    private fun selfHex(): String = selfDestHash.toHex()

    private suspend fun deleteChatLocal(chat: ChatEntity) {
        messageDao.attachmentsForChat(chat.id).forEach { attachment ->
            runCatching {
                if (attachment.localPath.isNotBlank()) {
                    File(attachment.localPath).delete()
                }
            }
        }
        messageDao.deleteAttachmentsForChat(chat.id)
        messageDao.deleteDeliveryTrackingForChat(chat.id)
        messageDao.deleteMessagesForChat(chat.id)
        messageDao.deleteGroupMembersForChat(chat.id)
        chatDao.delete(chat)
        UserPreferences.setChatMuted(appContext, chat.id, false)
        Log.i(TAG, "Deleted chat ${chat.id}")
    }

    /**
     * Called when we receive a delivery announce from the network.
     * Updates the contact's display name and, if a DM chat exists, its name too.
     * Does NOT overwrite a name that the user has manually set.
     */
    fun onAnnounceReceived(destHash: ByteArray, displayName: String?) {
        val hex = destHash.toHex()
        // RFed SPEC §17.10: the announce is the one place an address says it is
        // a distro. Remember it so the send path needs no lookup later.
        if (RetichatBridge.peerIsDistro(destHash) && !UserPreferences.isDistroContact(appContext, hex)) {
            UserPreferences.setDistroContact(appContext, hex, true)
            Log.i(TAG, "contact ${hex.take(8)} announced as a distro address")
        }
        if (displayName.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            val existing = contactDao.findByHash(hex)
            if (existing != null) {
                // Never overwrite a name the user has manually set
                if (existing.isNameManual) return@launch
                // Only update if the name actually changed
                if (existing.displayName != displayName) {
                    contactDao.upsert(existing.copy(displayName = displayName))
                    // Also update the DM chat name if one exists
                    val chatId = "dm_$hex"
                    val chat = chatDao.findById(chatId)
                    if (chat != null) {
                        chatDao.upsert(chat.copy(name = displayName))
                    }
                    Log.i(TAG, "Updated contact $hex name: '${existing.displayName}' → '$displayName'")
                }
            } else {
                // Ignore announces from unknown destinations – never auto-create contacts
                Log.d(TAG, "Ignored announce from unknown destination: $hex")
            }
        }
    }

    // ---- Attachments ----

    /**
     * Extract file attachments from LXMF fields, save each to disk,
     * and insert [AttachmentEntity] records in the database.
     */
    private suspend fun saveInboundAttachments(msgId: String, fields: LxmfFields) {
        val attachments = fields.getFileAttachments()
        if (attachments.isEmpty()) return

        for ((filename, data) in attachments) {
            try {
                val safeFilename = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destFile = File(attachmentDir, "${msgId}_$safeFilename")
                destFile.writeBytes(data)

                val mimeType = guessMimeType(filename)
                messageDao.insertAttachment(
                    AttachmentEntity(
                        messageId = msgId,
                        filename = filename,
                        mimeType = mimeType,
                        localPath = destFile.absolutePath,
                    )
                )
                Log.d(TAG, "Saved attachment: $filename (${data.size} bytes) for msg $msgId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save attachment: $filename", e)
            }
        }
    }

    /**
     * Save outbound attachments to disk + DB so they display in the conversation.
     */
    suspend fun saveOutboundAttachments(msgId: String, attachments: List<Pair<String, ByteArray>>) {
        for ((filename, data) in attachments) {
            try {
                val safeFilename = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destFile = File(attachmentDir, "${msgId}_$safeFilename")
                destFile.writeBytes(data)

                val mimeType = guessMimeType(filename)
                messageDao.insertAttachment(
                    AttachmentEntity(
                        messageId = msgId,
                        filename = filename,
                        mimeType = mimeType,
                        localPath = destFile.absolutePath,
                    )
                )
                Log.d(TAG, "Saved outbound attachment: $filename (${data.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save outbound attachment: $filename", e)
            }
        }
    }

    /** Get attachments for a message (for UI display). */
    suspend fun getAttachments(msgId: String): List<AttachmentEntity> =
        messageDao.attachmentsFor(msgId)

    /** Get attachments as a reactive flow. */
    fun attachmentsForFlow(msgId: String) = messageDao.attachmentsForFlow(msgId)

    /** Guess MIME type from file extension. */
    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    // ---- Helpers ----

    private fun directChatId(peerHash: ByteArray): String = "dm_${peerHash.toHex()}"

    private fun ContactEntity.toDomain() = Contact(
        destHash = destHashHex.hexToBytes(),
        displayName = displayName,
        publicKey = publicKeyHex?.hexToBytes(),
        addedAt = addedAt,
        isNameManual = isNameManual,
    )
}
