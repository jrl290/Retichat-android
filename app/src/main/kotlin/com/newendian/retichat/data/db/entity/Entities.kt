package com.newendian.retichat.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.newendian.retichat.MemberStatus

/**
 * A contact, keyed by the lxmf.delivery hash its messages come from.
 *
 * Three independent name slots (LXMF-rust/DISPLAY_NAMES.md §5.1), shown
 * through the one resolver ([com.newendian.retichat.names.NameBook]):
 * [localName] is the user's own name for the contact (cleared by saving an
 * empty rename), [messageName] comes from field 0xD1 of the contact's
 * messages, [announceName] from the contact's announce. None falls back to
 * another; an empty slot is null.
 *
 * [isAllowlisted] is iOS's `ContactEntity.isAllowlisted`: the privacy filter
 * keeps direct messages and group invites only from allowlisted contacts
 * ([com.newendian.retichat.data.repository.DeliveryPolicy]). Contacts the
 * user adds, group co-members and inviters are allowlisted; a contact row
 * created for a message that got through with the filter off is not.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val destHashHex: String,
    val localName: String? = null,
    val messageName: String? = null,
    val announceName: String? = null,
    val publicKeyHex: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isAllowlisted: Boolean = false,
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
    /**
     * Null for an ordinary message. [com.newendian.retichat.names.SystemText.MEMBER]
     * for a system line about [senderHashHex] ("… joined the group"): the
     * text holds no name, and the member's name is resolved when shown.
     */
    val systemKind: String? = null,
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
    /**
     * Unused since 2026-09-27 (written ""): member names are resolved from
     * the contacts when shown (DISPLAY_NAMES.md §5.3). Kept so the table
     * needs no rebuild.
     */
    val displayName: String = "",
    /** One of MemberStatus: invited, accepted, left, declined. */
    val inviteStatus: String = MemberStatus.ACCEPTED,
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
 * [type] is currently: TCPClientInterface.
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

/**
 * A poster seen in an RFed channel (DISPLAY_NAMES.md §4.2, §5.1).
 *
 * [channelName] is the name that sender's posts in this channel carry
 * (field 0xD1, accepted only after the key binding and signature checks); it
 * never becomes the contact's messageName. [firstSeenAt] (local ms) feeds the
 * send rule: a sender first seen after our last name inclusion gets the name
 * again.
 */
@Entity(tableName = "channel_senders", primaryKeys = ["channelId", "senderHex"])
data class ChannelSenderEntity(
    val channelId: String,
    val senderHex: String,
    val channelName: String? = null,
    val firstSeenAt: Long,
)

/**
 * The channel send rule's persisted state (DISPLAY_NAMES.md §4.2): the digest
 * of the Channel Display Name last included in a post here (hex; the empty
 * name's digest after a clear), and when.
 */
@Entity(tableName = "channel_name_state")
data class ChannelNameStateEntity(
    @PrimaryKey val channelId: String,
    val lastDigestHex: String,
    val lastIncludedAt: Long,
)
