package com.retichat.app.data.model

/**
 * Domain-level representation of a contact (someone we can chat with).
 */
data class Contact(
    val destHash: ByteArray,
    val displayName: String,
    val publicKey: ByteArray? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val destHashHex: String get() = destHash.toHex()

    override fun equals(other: Any?) =
        other is Contact && destHash.contentEquals(other.destHash)

    override fun hashCode() = destHash.contentHashCode()
}

/**
 * Domain-level chat (1:1 or group).
 */
data class Chat(
    val id: String,
    val isGroup: Boolean,
    val name: String,
    val members: List<ByteArray>,          // dest hashes in canonical order
    val groupSecret: ByteArray? = null,    // null for 1:1
    val lastMessage: String? = null,
    val lastMessageTime: Long = 0,
    val unreadCount: Int = 0,
)

/**
 * A single chat message (inbound or outbound).
 */
data class ChatMessage(
    val id: String,                        // LXMF hash hex
    val chatId: String,
    val senderHash: ByteArray,
    val content: String,
    val timestamp: Long,
    val isOutbound: Boolean,
    val state: Int = 0,                    // mirrors LXMessage state constants
    val progress: Float = 0f,
    val attachments: List<Attachment> = emptyList(),
    val groupRelayFor: ByteArray? = null,  // if relayed on behalf of someone
) {
    override fun equals(other: Any?) = other is ChatMessage && id == other.id
    override fun hashCode() = id.hashCode()
}

/**
 * File attachment carried in a message.
 */
data class Attachment(
    val filename: String,
    val data: ByteArray,
    val mimeType: String = guessMime(filename),
) {
    override fun equals(other: Any?) =
        other is Attachment && filename == other.filename

    override fun hashCode() = filename.hashCode()
}

/**
 * Group member descriptor.
 */
data class GroupMember(
    val destHash: ByteArray,
    val displayName: String,
    val isLowBandwidth: Boolean = false,
) {
    override fun equals(other: Any?) =
        other is GroupMember && destHash.contentEquals(other.destHash)

    override fun hashCode() = destHash.contentHashCode()
}

// ---- Helpers ----

fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun guessMime(name: String): String = when {
    name.endsWith(".png", true)  -> "image/png"
    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
    name.endsWith(".gif", true)  -> "image/gif"
    name.endsWith(".webp", true) -> "image/webp"
    name.endsWith(".mp4", true)  -> "video/mp4"
    name.endsWith(".pdf", true)  -> "application/pdf"
    else -> "application/octet-stream"
}
