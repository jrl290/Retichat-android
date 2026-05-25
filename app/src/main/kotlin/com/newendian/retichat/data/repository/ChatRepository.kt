package com.newendian.retichat.data.repository

import android.content.Context
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.service.ConnectionStateManager
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
            GroupChatManager(scope, selfHash, router, identity)
        } else null

        // When the service comes up (or network returns), flush queued messages
        if (router != 0L) {
            scope.launch(Dispatchers.IO) { flushPendingMessages() }
        }

        // Mark any stale GENERATING/OUTBOUND/SENDING messages as FAILED.
        // These are leftovers from a previous session that never completed.
        scope.launch(Dispatchers.IO) {
            messageDao.failStaleOutbound()
        }
    }

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
            )
        )
    }

    suspend fun findContact(destHash: ByteArray): Contact? =
        contactDao.findByHash(destHash.toHex())?.toDomain()

    /** Rename a contact and update the corresponding DM chat name. */
    suspend fun renameContact(destHashHex: String, newName: String) {
        val existing = contactDao.findByHash(destHashHex)
        if (existing != null) {
            contactDao.upsert(existing.copy(displayName = newName))
        }
        // Also update the 1:1 chat display name
        val chatId = "dm_$destHashHex"
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
                    acked = isSelf,   // self is always acked
                )
            )
        }

        // Broadcast invites to all members (except self) via GroupChatManager
        val allMemberHexes = sorted.map { it.toHex() }
        groupChatManager?.sendInvites(groupIdHex, name, allMemberHexes) ?: run {
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
     * Retry all messages that were queued while offline (state == OUTBOUND,
     * no native handle). Called when network becomes available or when the
     * service (re-)initialises.
     */
    private suspend fun flushPendingMessages() {
        if (routerHandle == 0L || identityHandle == 0L) return
        if (!NetworkMonitor.isOnline.value) return

        val pending = messageDao.pendingOutbound()
        if (pending.isEmpty()) return
        Log.i(TAG, "flushPending: ${pending.size} message(s) queued")

        for (msg in pending) {
            val chat = chatDao.findById(msg.chatId) ?: continue
            if (chat.isGroup) {
                // Group messages are fan-out; re-sending properly would need
                // the original member list.  For now, mark failed so the user
                // can resend manually.
                messageDao.updateState(msg.id, RetichatBridge.MessageState.FAILED)
                continue
            }

            val destHash = chat.memberHashes.hexToBytes()
            try {
                val handle = RetichatBridge.messageCreate(
                    destHash = destHash,
                    srcHash = selfDestHash,
                    content = msg.content,
                    method = RetichatBridge.DeliveryMethod.DIRECT,
                    identityHandle = identityHandle,
                )
                if (handle == 0L) {
                    messageDao.updateState(msg.id, RetichatBridge.MessageState.FAILED)
                    continue
                }
                val sent = RetichatBridge.messageSendViaAppLinks(handle)
                if (!sent) {
                    messageDao.updateState(msg.id, RetichatBridge.MessageState.FAILED)
                    continue
                }
                val state = RetichatBridge.messageGetState(handle)
                messageDao.updateHandle(msg.id, handle)
                messageDao.updateState(msg.id, state)
                if (!isTerminalState(state)) {
                    pollMessageState(msg.id, handle)
                }
                Log.d(TAG, "flushPending: sent queued msg ${msg.id}")
            } catch (e: Exception) {
                Log.e(TAG, "flushPending: failed ${msg.id}: ${e.message}")
                messageDao.updateState(msg.id, RetichatBridge.MessageState.FAILED)
            }
        }
    }

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
                // Guard: stack may have been torn down (e.g. app briefly backgrounded).
                // Re-acquire synchronously so the message goes out immediately rather
                // than sitting in OUTBOUND until the user re-opens the app.
                if (routerHandle == 0L || identityHandle == 0L) {
                    if (!NetworkMonitor.isOnline.value) {
                        Log.i(TAG, "sendDirect: offline — queued for later")
                        messageDao.updateState(localId, RetichatBridge.MessageState.OUTBOUND)
                        return@launch
                    }
                    Log.i(TAG, "sendDirect: stack not ready — re-acquiring")
                    messageDao.updateState(localId, RetichatBridge.MessageState.OUTBOUND)
                    val ok = StackRuntime.acquire(appContext)
                    if (!ok || routerHandle == 0L || identityHandle == 0L) {
                        Log.e(TAG, "sendDirect: re-acquire failed — message stays queued")
                        return@launch
                    }
                    // configure() was called by bootstrap; pending messages are flushed
                    // by flushPendingMessages() inside configure(). Our OUTBOUND message
                    // will be picked up there — no need to continue here.
                    return@launch
                }

                val readinessFailed = ConnectionStateManager.appLinkStatus(destHash) ==
                    RetichatBridge.AppLinkStatus.DISCONNECTED

                // Ensure an APP_LINK to the peer is opening (idempotent).
                // Keep the direct cascade unchanged; only the propagation
                // fallback delay changes. A currently DISCONNECTED peer skips
                // the local 5 s wait and starts the propagated copy immediately
                // in parallel with the fresh direct attempt.
                // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
                ConnectionStateManager.openConversation(destHash)
                Log.d(TAG, "sendDirect: openConversation OK, submitting DIRECT")

                val msgHandle = RetichatBridge.messageCreate(
                    destHash = destHash,
                    srcHash = selfDestHash,
                    content = content,
                    method = RetichatBridge.DeliveryMethod.DIRECT,
                    identityHandle = identityHandle,
                )
                if (msgHandle == 0L) {
                    val err = RetichatBridge.lastError()
                    Log.e(TAG, "sendDirect: messageCreate FAILED: $err")
                    messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
                    return@launch
                }
                Log.d(TAG, "sendDirect: messageCreate OK handle=$msgHandle")

                attachments.forEach { (name, data) ->
                    RetichatBridge.messageAddAttachment(msgHandle, name, data)
                }

                val sent = RetichatBridge.messageSendViaAppLinks(msgHandle)
                Log.d(TAG, "sendDirect: messageSendViaAppLinks result=$sent")
                if (!sent) {
                    Log.e(TAG, "sendDirect: messageSendViaAppLinks FAILED: ${RetichatBridge.lastError()}")
                    messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
                    return@launch
                }

                val state = RetichatBridge.messageGetState(msgHandle)
                Log.d(TAG, "sendDirect: post-send state=$state")

                // Update the bubble with the native handle and current state
                messageDao.updateHandle(localId, msgHandle)
                messageDao.updateState(localId, state)

                // Mirror iOS: normally wait 5 s before the propagated copy,
                // but if the current readiness was already red, start the
                // propagation copy immediately while the direct cascade keeps running.
                schedulePropagationFallback(
                    localId,
                    destHash,
                    content,
                    attachments,
                    immediate = readinessFailed,
                )

                // 3) Poll until the native message reaches a terminal state
                //    (SENT, DELIVERED, FAILED, REJECTED, CANCELLED).
                //    The proof arrives async via the link; we need to notice it.
                if (!isTerminalState(state)) {
                    pollMessageState(localId, msgHandle)
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendDirect: exception: ${e.message}", e)
                messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
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
     */
    private suspend fun pollMessageState(
        localId: String,
        msgHandle: Long,
        initialDeadlineMs: Long = 60_000L,
    ) {
        var interval = 200L          // start at 200ms for snappy LAN feedback
        val maxInterval = 5_000L     // cap at 5s
        val longDeadline = 600_000L  // 10 min max for large transfers
        var deadline = System.currentTimeMillis() + initialDeadlineMs
        var lastProgress = 0f

        while (System.currentTimeMillis() < deadline) {
            delay(interval)

            // If schedulePropagationFallback has taken over this message
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
        // Timed out — mark failed so the user isn't left in limbo
        Log.w(TAG, "pollState: timed out for $localId, marking FAILED")
        messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
    }

    /**
    * Direct→propagated fallback. Mirrors iOS
     * `ChatRepository.schedulePropFallback(directHashHex:)`.
     *
    * Normally five seconds after a DIRECT send, if the bubble is still in a
    * non-terminal pre-delivery state (GENERATING/OUTBOUND/SENDING) we create
    * a PROPAGATED copy and dispatch it to the user-configured (or
    * randomly-chosen) LXMF propagation node. If current readiness was already
    * DISCONNECTED (red) when send started, the delay collapses to zero while
    * the direct cascade continues in parallel. Polling continues on the new
    * handle so the bubble updates as the propagated copy progresses.
     */
    private fun schedulePropagationFallback(
        localId: String,
        destHash: ByteArray,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
        immediate: Boolean = false,
    ) {
        scope.launch(Dispatchers.IO) {
            val fallbackDelayMs = if (immediate) 0L else 5_000L
            if (fallbackDelayMs > 0L) delay(fallbackDelayMs)

            val current = messageDao.findById(localId) ?: return@launch
            // Skip fallback if direct already reached SENT/DELIVERED or a
            // terminal failure state.
            if (current.state == RetichatBridge.MessageState.SENT ||
                current.state == RetichatBridge.MessageState.DELIVERED ||
                isTerminalState(current.state)
            ) {
                return@launch
            }

            if (routerHandle == 0L || identityHandle == 0L) {
                Log.w(TAG, "fallback: stack not ready, skipping")
                return@launch
            }

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
                Log.w(TAG, "fallback: setPropagationNode failed: ${RetichatBridge.lastError()}")
                return@launch
            }

            Log.i(
                TAG,
                "fallback: ${if (immediate) "immediate" else "5s"} trigger for $localId (state=${current.state}); " +
                    "sending PROPAGATED via ${nodeHash.toHex().take(16)}"
            )

            val propHandle = RetichatBridge.messageCreate(
                destHash = destHash,
                srcHash = selfDestHash,
                content = content,
                method = RetichatBridge.DeliveryMethod.PROPAGATED,
                identityHandle = identityHandle,
            )
            if (propHandle == 0L) {
                Log.e(TAG, "fallback: messageCreate failed: ${RetichatBridge.lastError()}")
                return@launch
            }
            attachments.forEach { (name, data) ->
                RetichatBridge.messageAddAttachment(propHandle, name, data)
            }

            val sent = RetichatBridge.messageSendViaAppLinks(propHandle)
            if (!sent) {
                Log.e(TAG, "fallback: messageSendViaAppLinks failed: ${RetichatBridge.lastError()}")
                RetichatBridge.messageDestroy(propHandle)
                return@launch
            }

            // Re-point the bubble at the propagated handle and continue polling,
            // BUT only if the direct send hasn't already succeeded.  If direct
            // won while we were creating the prop copy, discard the prop handle
            // and leave the success state untouched.
            val afterSend = messageDao.findById(localId)
            if (afterSend != null && isSuccessState(afterSend.state)) {
                Log.d(TAG, "fallback: direct already succeeded (state=${afterSend.state}) for $localId — discarding prop handle")
                RetichatBridge.messageDestroy(propHandle)
                return@launch
            }

            messageDao.updateHandle(localId, propHandle)
            val newState = RetichatBridge.messageGetState(propHandle)
            // Only write prop initial state if direct hasn't won
            if (!isSuccessState(newState)) {
                messageDao.updateState(localId, newState)
            }
            if (!isTerminalState(newState)) {
                // PROPAGATED delivery can legitimately take several minutes
                // (the propagation node buffers and re-delivers to the
                // recipient when they next announce).  Use the long deadline
                // so the poll doesn't mark FAILED before Rust delivers it.
                // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
                pollMessageState(localId, propHandle, initialDeadlineMs = 600_000L)
            }
        }
    }

    private suspend fun sendGroupMessage(
        chat: ChatEntity,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
    ) {
        val selfHex = selfDestHash.toHex()
        val groupIdHex = chat.groupIdHex ?: return

        // Send to all group members except self
        val allMembers = chat.memberHashes.split(",")
        val memberHexes = allMembers.filter { it != selfHex }

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
                return
            }
            interval = (interval * 3 / 2).coerceAtMost(maxInterval)
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
        scope.launch(Dispatchers.IO) {
            val srcHex = srcHash.toHex()
            val msgId = hash.toHex()

            // Auto-create contact if unknown
            if (contactDao.findByHash(srcHex) == null) {
                contactDao.upsert(
                    ContactEntity(
                        destHashHex = srcHex,
                        displayName = srcHex.take(8),
                    )
                )
            }

            if (groupId != null) {
                handleGroupMessage(msgId, srcHex, content, timestamp, fields)
            } else {
                handleDirectMessage(msgId, srcHash, srcHex, content, timestamp, fields)
            }
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

        if (chat == null && groupMembers != null && groupAction == GroupChatManager.Action.INVITE) {
            // Incoming invite — create the local group record in PENDING state
            // (self.acked = false; user must Accept/Decline via the UI)
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

            groupMembers.split(",").forEach { memberHex ->
                val contact = contactDao.findByHash(memberHex)
                messageDao.upsertGroupMember(
                    GroupMemberEntity(
                        chatId = chatId,
                        destHashHex = memberHex,
                        displayName = contact?.displayName ?: memberHex.take(8),
                        acked = false,   // nobody is "accepted" yet from our POV
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
                // Insert a system invite message (idempotent on inviteMsgId)
                val senderName = contactDao.findByHash(actualSenderHex)?.displayName
                    ?: actualSenderHex.take(8)
                val inviteMsgId = "inv_${groupId.take(16)}"
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
                if (RetichatApp.activeChatId != chat.id) {
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
                messageDao.markGroupMemberAcked(chat.id, actualSenderHex)

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
                messageDao.deleteGroupMember(chat.id, actualSenderHex)
                val updatedMembers = chat.memberHashes.split(",")
                    .filter { it != actualSenderHex }
                    .joinToString(",")
                chatDao.upsert(chat.copy(memberHashes = updatedMembers))
                messageDao.upsert(
                    MessageEntity(
                        id = msgId,
                        chatId = chat.id,
                        senderHashHex = actualSenderHex,
                        content = "left the group",
                        timestamp = (timestamp * 1000).toLong(),
                        isOutbound = false,
                        state = RetichatBridge.MessageState.DELIVERED,
                    )
                )
            }

            GroupChatManager.Action.RELAY_REQUEST -> {
                val alreadySeen = groupRelaySeen
                    ?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                val allMembers = chat.memberHashes.split(",")
                groupChatManager?.performRelay(
                    groupId   = groupId,
                    groupName = chat.name,
                    content   = content,
                    originalSender         = actualSenderHex,
                    alreadySeen            = alreadySeen,
                    requesterHex           = srcHex,
                    allAcceptedMembers     = allMembers,
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
     * unaccepted invite (self member's `acked` flag is false).
     */
    fun isPendingGroupInvite(chatId: String): Flow<Boolean> {
        val selfHex = selfDestHash.toHex()
        return messageDao.groupMembers(chatId).map { members ->
            members.any { it.destHashHex == selfHex && !it.acked }
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

        messageDao.markGroupMemberAcked(chatId, selfHex)

        val allMembers = chat.memberHashes.split(",").filter { it.isNotEmpty() }
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
        // Remove all members for this chat
        messageDao.groupMembersList(chatId).forEach {
            messageDao.deleteGroupMember(chatId, it.destHashHex)
        }
        chatDao.delete(chat)
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
                messageDao.deleteGroupMember(chat.id, srcHex)
                val updatedMembers = chat.memberHashes.split(",")
                    .filter { it != srcHex }
                    .joinToString(",")
                chatDao.upsert(chat.copy(memberHashes = updatedMembers))
                // Insert a system message in the group chat
                messageDao.upsert(
                    MessageEntity(
                        id = msgId,
                        chatId = chat.id,
                        senderHashHex = srcHex,
                        content = "left the group",
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
     * Leave a group chat — broadcast a leave message to all members,
     * then archive the chat locally.
     */
    suspend fun leaveGroupChat(chatId: String) {
        val chat = chatDao.findById(chatId) ?: return
        if (!chat.isGroup) return
        val groupIdHex = chat.groupIdHex ?: return
        val selfHex = selfDestHash.toHex()
        val otherMembers = chat.memberHashes.split(",").filter { it != selfHex }

        // Broadcast leave to all members
        otherMembers.forEach { memberHex ->
            scope.launch(Dispatchers.IO) {
                val handle = RetichatBridge.messageCreate(
                    destHash = memberHex.hexToBytes(),
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

        // Remove self from local member list and archive
        val updatedMembers = otherMembers.joinToString(",")
        chatDao.upsert(chat.copy(memberHashes = updatedMembers, isArchived = true))
        messageDao.deleteGroupMember(chatId, selfHex)
        Log.i(TAG, "Left group $chatId")
    }

    /**
     * Called when we receive a delivery announce from the network.
     * Updates the contact's display name and, if a DM chat exists, its name too.
     */
    fun onAnnounceReceived(destHash: ByteArray, displayName: String?) {
        if (displayName.isNullOrBlank()) return
        val hex = destHash.toHex()
        scope.launch(Dispatchers.IO) {
            val existing = contactDao.findByHash(hex)
            if (existing != null) {
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
    )
}
