package com.retichat.app.data.repository

import com.retichat.app.bridge.MessageCallback
import com.retichat.app.bridge.RetichatBridge
import com.retichat.app.data.db.dao.ChatDao
import com.retichat.app.data.db.dao.ChatPreview
import com.retichat.app.data.db.dao.ContactDao
import com.retichat.app.data.db.dao.MessageDao
import com.retichat.app.data.db.entity.*
import com.retichat.app.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val attachmentDir: File,
    private val scope: CoroutineScope,
) {
    // ---- Own identity (set by ReticulumService after init) ----

    var selfDestHash: ByteArray = ByteArray(0)
        private set
    var routerHandle: Long = 0L
        private set

    fun configure(selfHash: ByteArray, router: Long) {
        selfDestHash = selfHash
        routerHandle = router
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

    // ---- Chats ----

    fun chatPreviews(): Flow<List<ChatPreview>> = chatDao.chatPreviews()

    suspend fun getOrCreateDirectChat(contact: Contact): String {
        val chatId = directChatId(contact.destHash)
        if (chatDao.findById(chatId) == null) {
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    isGroup = false,
                    name = contact.displayName,
                    memberHashes = contact.destHashHex,
                )
            )
        }
        return chatId
    }

    suspend fun createGroupChat(
        name: String,
        members: List<ByteArray>,
        secret: ByteArray,
    ): String {
        // Canonical order: sorted by dest hash hex
        val sorted = members.sortedBy { it.toHex() }
        val hashes = sorted.joinToString(",") { it.toHex() }
        val chatId = "group_${secret.toHex().take(16)}"

        chatDao.upsert(
            ChatEntity(
                id = chatId,
                isGroup = true,
                name = name,
                memberHashes = hashes,
                groupSecretHex = secret.toHex(),
            )
        )

        sorted.forEach { hash ->
            val contact = findContact(hash)
            messageDao.upsertGroupMember(
                GroupMemberEntity(
                    chatId = chatId,
                    destHashHex = hash.toHex(),
                    displayName = contact?.displayName ?: hash.toHex().take(8),
                )
            )
        }
        return chatId
    }

    // ---- Messages ----

    fun messagesForChat(chatId: String): Flow<List<MessageEntity>> =
        messageDao.messagesForChat(chatId)

    /**
     * Send a text message in a 1:1 or group chat.
     */
    suspend fun sendMessage(chatId: String, content: String, attachmentFiles: List<Pair<String, ByteArray>> = emptyList()) {
        val chat = chatDao.findById(chatId) ?: return
        val selfHex = selfDestHash.toHex()

        if (chat.isGroup) {
            sendGroupMessage(chat, content, attachmentFiles)
        } else {
            val destHash = chat.memberHashes.hexToBytes()
            sendDirectMessage(chatId, destHash, content, attachmentFiles)
        }
    }

    private suspend fun sendDirectMessage(
        chatId: String,
        destHash: ByteArray,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
    ) {
        val msgHandle = RetichatBridge.messageCreate(
            destHash = destHash,
            srcHash = selfDestHash,
            content = content,
            method = RetichatBridge.DeliveryMethod.DIRECT,
        )
        if (msgHandle == 0L) return

        attachments.forEach { (name, data) ->
            RetichatBridge.messageAddAttachment(msgHandle, name, data)
        }

        RetichatBridge.messageSend(routerHandle, msgHandle)

        val hash = RetichatBridge.messageGetHash(msgHandle) ?: ByteArray(0)
        val msgId = hash.toHex().ifEmpty { "out_${System.currentTimeMillis()}" }

        messageDao.upsert(
            MessageEntity(
                id = msgId,
                chatId = chatId,
                senderHashHex = selfDestHash.toHex(),
                content = content,
                timestamp = System.currentTimeMillis(),
                isOutbound = true,
                state = RetichatBridge.messageGetState(msgHandle),
                nativeHandle = msgHandle,
            )
        )
    }

    private suspend fun sendGroupMessage(
        chat: ChatEntity,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
    ) {
        val selfHex = selfDestHash.toHex()
        val memberHexes = chat.memberHashes.split(",").filter { it != selfHex }

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

        // Fanout: send individually to each member
        memberHexes.forEach { memberHex ->
            val destHash = memberHex.hexToBytes()
            messageDao.upsertTracking(
                DeliveryTrackingEntity(
                    messageId = groupMsgId,
                    chatId = chat.id,
                    memberHashHex = memberHex,
                )
            )

            scope.launch(Dispatchers.IO) {
                val handle = RetichatBridge.messageCreate(
                    destHash = destHash,
                    srcHash = selfDestHash,
                    content = content,
                    method = RetichatBridge.DeliveryMethod.DIRECT,
                )
                if (handle != 0L) {
                    // TODO: add group metadata fields to the message
                    attachments.forEach { (name, data) ->
                        RetichatBridge.messageAddAttachment(handle, name, data)
                    }
                    RetichatBridge.messageSend(routerHandle, handle)
                }
            }
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
    ) {
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

            // Determine chat ID (TODO: detect group messages from fields)
            val chatId = directChatId(srcHash)
            if (chatDao.findById(chatId) == null) {
                val contact = contactDao.findByHash(srcHex)
                chatDao.upsert(
                    ChatEntity(
                        id = chatId,
                        isGroup = false,
                        name = contact?.displayName ?: srcHex.take(8),
                        memberHashes = srcHex,
                    )
                )
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
