package com.newendian.retichat.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val destHashHex: String,
    val displayName: String,
    val publicKeyHex: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val isGroup: Boolean,
    val name: String,
    /** Comma-separated hex dest hashes in canonical order. */
    val memberHashes: String,
    /** The random group identifier (hex) shared by all participants. */
    val groupIdHex: String? = null,
    /** Hex hash of the member currently relaying for us (null = no relay). */
    val currentRelayerHex: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,        // LXMF hash hex
    val chatId: String,
    val senderHashHex: String,
    val content: String,
    val timestamp: Long,
    val isOutbound: Boolean,
    val state: Int = 0,
    val nativeHandle: Long = 0,        // for tracking outbound progress
    val progress: Float = 0f,          // resource transfer progress 0..1
)

@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val messageId: String,
    val filename: String,
    val mimeType: String,
    /** Path to the cached file in internal storage. */
    val localPath: String,
)

@Entity(tableName = "group_members")
data class GroupMemberEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val chatId: String,
    val destHashHex: String,
    val displayName: String,
    val acked: Boolean = false,
)

@Entity(tableName = "delivery_tracking")
data class DeliveryTrackingEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val messageId: String,
    val chatId: String,
    val memberHashHex: String,
    val delivered: Boolean = false,
    val deliveredAt: Long? = null,
)

/**
 * A persisted Reticulum network interface configuration.
 *
 * [type] is one of: TCPClientInterface, TCPServerInterface, UDPInterface,
 * AutoInterface, I2PInterface.
 *
 * [configJson] holds the type-specific key/value pairs as a JSON string,
 * e.g. {"target_host":"192.168.1.1","target_port":"4242"}.
 */
@Entity(tableName = "interfaces")
data class InterfaceConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val enabled: Boolean = true,
    val configJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A subscribed RFed channel.
 *
 * [id] is the 32-char hex of the 16-byte channel identity hash, also used
 * as the primary key for inbound blob lookup. [channelName] is the
 * user-supplied name from which the channel keypair is deterministically
 * derived (`SHA256(name)` seed). [rfedNodeIdentityHashHex] is the RFed
 * node hosting the subscription — used to derive `rfed.{channel,delivery,
 * notify}` destinations.
 *
 * [stampCost] is the latest known PoW cost from `/rfed/subscribe`
 * (null = unknown / never refreshed; 0 = disabled).
 */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val channelName: String,
    val rfedNodeIdentityHashHex: String,
    val stampCost: Int? = null,
    val joinedAt: Long = System.currentTimeMillis(),
    val lastMessageTime: Long = 0L,
    val isArchived: Boolean = false,
)

/**
 * A message received on (or sent to) an RFed channel.
 *
 * [id] is `sourceHashHex|timestampMs` (deterministic per sender+ts) so
 * duplicate fanout deliveries and PULL replays are deduplicated cheaply.
 */
@Entity(tableName = "channel_messages")
data class ChannelMessageEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val sourceHashHex: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val isOutbound: Boolean,
    val signatureValidated: Boolean = false,
    /**
     * Send state for outbound channel messages. Inbound rows always default
     * to [SEND_STATE_SENT] (the indicator is only rendered for outbound).
     *
     * - 0 = SENDING (in flight; indicator suppressed in UI)
     * - 1 = SENT    (rfed accepted the packet — single check)
     * - 2 = FAILED  (both send attempts failed — red X)
     */
    val sendState: Int = SEND_STATE_SENT,
) {
    companion object {
        const val SEND_STATE_SENDING = 0
        const val SEND_STATE_SENT = 1
        const val SEND_STATE_FAILED = 2
    }
}
