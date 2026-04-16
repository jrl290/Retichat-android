package com.retichat.app.data.repository

import android.content.Context
import android.util.Log
import com.retichat.app.RetichatApp
import com.retichat.app.bridge.LxmfFields
import com.retichat.app.bridge.RetichatBridge
import com.retichat.app.service.MessageNotificationHelper
import com.retichat.app.service.NetworkMonitor
import com.retichat.app.data.db.dao.ChatDao
import com.retichat.app.data.db.dao.ChatPreview
import com.retichat.app.data.db.dao.ContactDao
import com.retichat.app.data.db.dao.MessageDao
import com.retichat.app.data.db.entity.*
import com.retichat.app.data.model.*
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

    fun configure(selfHash: ByteArray, router: Long, identity: Long) {
        selfDestHash = selfHash
        routerHandle = router
        identityHandle = identity

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

        // Broadcast a "group create" message to all members (except self)
        val selfHex = selfDestHash.toHex()
        val otherMembers = sorted.filter { it.toHex() != selfHex }
        otherMembers.forEach { memberHash ->
            scope.launch(Dispatchers.IO) {
                val handle = RetichatBridge.messageCreate(
                    destHash = memberHash,
                    srcHash = selfDestHash,
                    content = "You've been added to the group \"$name\". Reply \"STOP: ${groupIdHex.take(6)}\" to leave.",
                    method = RetichatBridge.DeliveryMethod.DIRECT,
                    identityHandle = identityHandle,
                )
                if (handle != 0L) {
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_ID, groupIdHex)
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_MEMBERS, hashes)
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_NAME, name)
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_SENDER, selfHex)
                    RetichatBridge.messageSend(routerHandle, handle)
                }
            }
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
                val sent = RetichatBridge.messageSend(routerHandle, handle)
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
                // Guard: if the service was killed or network is down, queue for later
                if (routerHandle == 0L || identityHandle == 0L || !NetworkMonitor.isOnline.value) {
                    Log.i(TAG, "sendDirect: offline or not ready — queued for later")
                    messageDao.updateState(localId, RetichatBridge.MessageState.OUTBOUND)
                    return@launch
                }

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

                val sent = RetichatBridge.messageSend(routerHandle, msgHandle)
                Log.d(TAG, "sendDirect: messageSend result=$sent")
                if (!sent) {
                    Log.e(TAG, "sendDirect: messageSend FAILED: ${RetichatBridge.lastError()}")
                    messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
                    return@launch
                }

                val state = RetichatBridge.messageGetState(msgHandle)
                Log.d(TAG, "sendDirect: post-send state=$state")

                // Update the bubble with the native handle and current state
                messageDao.updateHandle(localId, msgHandle)
                messageDao.updateState(localId, state)

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
     * Poll the native message handle until it reaches a terminal state.
     * Uses exponential back-off: 200ms, 300ms, 450ms, … capped at 5s.
     *
     * For small (packet-based) messages the poll gives up after 60s.
     * For large transfers (resource-based) the deadline extends to 10 min
     * and is reset whenever transfer progress advances, so that active
     * transfers are never prematurely killed.
     */
    private suspend fun pollMessageState(localId: String, msgHandle: Long) {
        var interval = 200L          // start at 200ms for snappy LAN feedback
        val maxInterval = 5_000L     // cap at 5s
        val shortDeadline = 60_000L  // 60s for small messages
        val longDeadline = 600_000L  // 10 min max for large transfers
        var deadline = System.currentTimeMillis() + shortDeadline
        var lastProgress = 0f

        while (System.currentTimeMillis() < deadline) {
            delay(interval)
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

            messageDao.updateStateAndProgress(localId, newState, progress)

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

                    val sent = RetichatBridge.messageSend(routerHandle, handle)
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
        val groupId = fields.getString(LxmfFields.GROUP_ID)!!
        val groupMembers = fields.getString(LxmfFields.GROUP_MEMBERS)
        val groupName = fields.getString(LxmfFields.GROUP_NAME)
        val groupSender = fields.getString(LxmfFields.GROUP_SENDER)
        val groupRelayFor = fields.getString(LxmfFields.GROUP_RELAY_FOR)
        val groupRelayComplete = fields.getBool(LxmfFields.GROUP_RELAY_COMPLETE)
        val groupAck = fields.getBool(LxmfFields.GROUP_ACK)

        // Determine the actual sender (could be relayed)
        val actualSenderHex = groupSender ?: srcHex

        // Look up existing group chat by groupId
        var chat = chatDao.findByGroupId(groupId)

        if (chat == null && groupMembers != null) {
            // This is a "group create" message — build the local group
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

            // Populate group members
            groupMembers.split(",").forEach { memberHex ->
                val contact = contactDao.findByHash(memberHex)
                messageDao.upsertGroupMember(
                    GroupMemberEntity(
                        chatId = chatId,
                        destHashHex = memberHex,
                        displayName = contact?.displayName ?: memberHex.take(8),
                    )
                )
            }

            chat = chatDao.findById(chatId)
            Log.i(TAG, "Created group from incoming: chatId=$chatId, groupId=$groupId")
        }

        if (chat == null) {
            Log.w(TAG, "handleGroupMessage: unknown groupId=$groupId, ignoring")
            return
        }

        // Handle GROUP_ACK — mark the sender as acked (legacy)
        if (groupAck) {
            Log.i(TAG, "GROUP_ACK received from $srcHex for group ${chat.id}")
            messageDao.markGroupMemberAcked(chat.id, srcHex)
            return
        }

        // Handle STOP / leave — remove the sender from the group
        if (content.trim().equals("STOP", ignoreCase = true) || content == "left the group") {
            Log.i(TAG, "Member $actualSenderHex opted out of group ${chat.id}")
            messageDao.deleteGroupMember(chat.id, actualSenderHex)
            val updatedMembers = chat.memberHashes.split(",")
                .filter { it != actualSenderHex }
                .joinToString(",")
            chatDao.upsert(chat.copy(memberHashes = updatedMembers))

            // Insert a system-style message so the group sees they left
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
            return
        }

        // Handle relay-complete signal
        if (groupRelayComplete) {
            Log.i(TAG, "Relay complete signal received for group ${chat.id}")
            // The relayer confirms all members were delivered
            // Mark our tracking entries as delivered for the relevant message
            return
        }

        // Handle relay request (GROUP_RELAY_FOR is present)
        if (groupRelayFor != null) {
            Log.i(TAG, "Relay request from $srcHex for $groupRelayFor in group ${chat.id}")
            handleRelayRequest(chat, content, groupId, groupRelayFor, srcHex)
            return
        }

        // Regular group message — insert if content is non-empty or has attachments
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

            // Save file attachments to disk + DB
            saveInboundAttachments(msgId, fields)

            // Notification
            if (RetichatApp.activeChatId != chat.id) {
                val contact = contactDao.findByHash(actualSenderHex)
                val senderName = contact?.displayName ?: actualSenderHex.take(8)
                val chatName = chat.name
                val notifText = if (content.isNotBlank()) content else "\uD83D\uDCCE ${attachments.size} attachment(s)"
                MessageNotificationHelper.notify(
                    appContext, "$senderName ($chatName)", notifText, chat.id,
                )
            }
        }
    }

    /**
     * Handle a relay request — we've been asked to forward a message
     * to other group members on behalf of [originalSenderHex].
     */
    private suspend fun handleRelayRequest(
        chat: ChatEntity,
        content: String,
        groupIdHex: String,
        originalSenderHex: String,
        relayerHex: String,
    ) {
        val selfHex = selfDestHash.toHex()
        val allMembers = chat.memberHashes.split(",")

        // Forward to everyone except self, the original sender, and the person who relayed to us
        val targets = allMembers.filter {
            it != selfHex && it != originalSenderHex && it != relayerHex
        }

        targets.forEach { targetHex ->
            scope.launch(Dispatchers.IO) {
                val handle = RetichatBridge.messageCreate(
                    destHash = targetHex.hexToBytes(),
                    srcHash = selfDestHash,
                    content = content,
                    method = RetichatBridge.DeliveryMethod.DIRECT,
                    identityHandle = identityHandle,
                )
                if (handle != 0L) {
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_ID, groupIdHex)
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_SENDER, originalSenderHex)
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_RELAY_FOR, originalSenderHex)
                    RetichatBridge.messageSend(routerHandle, handle)
                }
            }
        }

        // Send relay-complete back to the requester
        scope.launch(Dispatchers.IO) {
            val handle = RetichatBridge.messageCreate(
                destHash = relayerHex.hexToBytes(),
                srcHash = selfDestHash,
                content = "",
                method = RetichatBridge.DeliveryMethod.DIRECT,
                identityHandle = identityHandle,
            )
            if (handle != 0L) {
                RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_ID, groupIdHex)
                RetichatBridge.messageAddFieldBool(handle, LxmfFields.GROUP_RELAY_COMPLETE, true)
                RetichatBridge.messageSend(routerHandle, handle)
            }
        }

        Log.i(TAG, "Relayed msg to ${targets.size} members, sent relay-complete to $relayerHex")
    }

    /**
     * Send a GROUP_ACK message to [targetHex] for the given group,
     * confirming that we support Retichat group chat.
     */
    private fun sendGroupAck(groupIdHex: String, targetHex: String) {
        scope.launch(Dispatchers.IO) {
            val handle = RetichatBridge.messageCreate(
                destHash = targetHex.hexToBytes(),
                srcHash = selfDestHash,
                content = "",
                method = RetichatBridge.DeliveryMethod.DIRECT,
                identityHandle = identityHandle,
            )
            if (handle != 0L) {
                RetichatBridge.messageAddFieldString(handle, LxmfFields.GROUP_ID, groupIdHex)
                RetichatBridge.messageAddFieldBool(handle, LxmfFields.GROUP_ACK, true)
                RetichatBridge.messageSend(routerHandle, handle)
                Log.i(TAG, "Sent GROUP_ACK to $targetHex for group $groupIdHex")
            }
        }
    }

    // ---- Direct message handling ----

    private suspend fun handleDirectMessage(
        msgId: String,
        srcHash: ByteArray,
        srcHex: String,
        content: String,
        timestamp: Double,
        fields: LxmfFields,
    ) {
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
                    RetichatBridge.messageSend(routerHandle, handle)
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
