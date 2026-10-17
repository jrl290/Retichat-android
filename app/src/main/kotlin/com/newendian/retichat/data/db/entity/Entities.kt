package com.newendian.retichat.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.newendian.retichat.MemberStatus
import com.newendian.retichat.names.ContactNames

/**
 * A contact, keyed by the lxmf.delivery hash its messages come from.
 *
 * Three independent name slots (LXMF-rust/DISPLAY_NAMES.md §5.1), shown
 * through the one resolver ([com.newendian.retichat.names.NameBook]):
 * [localName] is the user's own name for the contact (cleared by saving an
 * empty rename), [messageName] comes from field 0xD1 of the contact's
 * messages, [announceName] from the contact's announce. None falls back to
 * another; an empty slot is null. [messageNameAt] is the LXMF timestamp
 * (seconds) of the message that last set or cleared [messageName] (§5.2:
 * only a newer message is accepted). [legacyName] holds a name migrated from
 * before the slots whose origin is unknown (§5.4): never written afterwards,
 * dropped when a 0xD1 is accepted or an announce carrying a name arrives,
 * and last in the resolver.
 *
 * [isAllowlisted] is iOS's `ContactEntity.isAllowlisted`: the privacy filter
 * keeps direct messages and group invites only from allowlisted contacts
 * ([com.newendian.retichat.data.repository.DeliveryPolicy]). Contacts the
 * user adds and the listed members of a group held here (from an invite the
 * filter let through, as v0.1.8 does) are allowlisted; a row created for a
 * message that got through with the filter off is not.
 *
 * [isContact]: the user added this peer (Add Contact, New Conversation, a QR
 * code or lxma:// link), so it is listed as a contact. Every other row is
 * hidden ([com.newendian.retichat.data.db.ContactSql]): a group member is
 * accepted by association, a stranger's or a distro sender's conversation is
 * a conversation, and none of them is listed. The SQL default 1 is for the
 * rows that existed before the column (database 12): they stay contacts.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val destHashHex: String,
    val localName: String? = null,
    val messageName: String? = null,
    val messageNameAt: Double? = null,
    val announceName: String? = null,
    val legacyName: String? = null,
    val publicKeyHex: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isAllowlisted: Boolean = false,
    @ColumnInfo(defaultValue = "1") val isContact: Boolean = false,
)

/** The name slots the resolver reads (§5.3). */
fun ContactEntity.names(): ContactNames = ContactNames(localName, messageName, announceName, legacyName)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val isGroup: Boolean,
    val name: String,
    /**
     * A direct chat's peer hash. A group's member list: the GROUP_MEMBERS csv
     * exactly as its invite carried it (or as this device wrote it into its own
     * invite chunks, or as a group sync carried it), for a group admitted since
     * database 16, since an `accept` group sync must carry it verbatim (RFed SPEC
     * §17.14). A group held before then holds its members comma-separated.
     */
    val memberHashes: String,
    /** The random group identifier (hex) shared by all participants. */
    val groupIdHex: String? = null,
    /** Hex hash of the member currently relaying for us (null = no relay). */
    val currentRelayerHex: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false,
    /**
     * A group's creator (database 14; RFed-spec Group.md, "Member statuses and
     * keys", James 2026-10-06): this device's own hash for a group it created,
     * the invite's authenticated source for one it was invited into. A `status`
     * counts only from it, and only the creator answers an accept with one.
     * Null for a direct chat and for a group held before database 14: it
     * neither answers an accept nor counts a status.
     */
    val creatorHex: String? = null,
    /**
     * A group's own address (database 16; RFed SPEC §17.14,
     * GROUPS-DISTRO-DESIGN.md §8.1): the address the user acts as in it, and
     * whose member row alone says whether the user is pending or joined. The
     * distro D for a group created on a device that held D, admitted from an
     * invite that lists the D held then, or built from a group sync; the device
     * otherwise. Null for a direct chat and for a group held before database 16
     * ("held at upgrade"): that reads as the device, whatever else its list
     * names (ruling 1, no migration).
     */
    val ownMember: String? = null,
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
     * [com.newendian.retichat.names.SystemText.OTHER_DEVICE] for one about
     * another device of the user's ("Your other device joined the group").
     */
    val systemKind: String? = null,
    /**
     * The message's LXMF hash, 64 hex, where [id] is not it (REACTIONS-DESIGN.md
     * §6): an outgoing direct message (id `out_…`) once the send has its hash,
     * a Distro Fanout message (the unwrap's `hash`), a Distro Sync of a send
     * (its key 12). A reaction in a direct chat finds its target by it, or by
     * [id] where that is the hash. Null otherwise and for rows before database 17.
     */
    val lxmfHash: String? = null,
    /** A Group post's id, 32 hex (REACTIONS-DESIGN.md §4): Group reactions name it. Null for anything else. */
    val postId: String? = null,
    /**
     * The reactions to this message (REACTIONS-DESIGN.md §6): `{"<reactor 32
     * hex>": {"e": "👍", "t": <ms>}}`, `"e": ""` a removal kept so that an older
     * one cannot bring it back. Written only through ChatRepository's apply
     * ([com.newendian.retichat.data.repository.Reactions]). Null when nobody reacted.
     */
    val reactionsJson: String? = null,
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
     * - 3 = QUEUED  (typed while the app was starting; sent when the start
     *   finishes, as a queued DM is: the DM's pending clock)
     */
    val sendState: Int = SEND_STATE_SENT,
) {
    companion object {
        const val SEND_STATE_SENDING = 0
        const val SEND_STATE_SENT = 1
        const val SEND_STATE_FAILED = 2
        const val SEND_STATE_QUEUED = 3
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
    /**
     * The post timestamp (ms) of the post whose 0xD1 last set or cleared
     * [channelName]. An older post pulled later does not undo a newer name.
     */
    val nameAt: Long = 0,
)

/**
 * The channel send rule's persisted state (DISPLAY_NAMES.md §4.2), kept per
 * channel and posting identity ([postingHex]: the `lxmf.delivery` hash the
 * posts are signed with, the distro's when the device holds one, otherwise
 * the device's; RFed SPEC §17.12): the digest of the Channel Display Name last
 * included in a post as that identity (hex; the empty name's digest after a
 * clear), and when. Readers have never had a name from a new posting
 * identity, so one this device has no row for starts from none and its first
 * post carries the name.
 *
 * A row from before database 13 has `postingHex` empty: it was made when the
 * device posted as itself, the only posting identity then, and is the
 * device's from the first time the device looks for its own state
 * ([com.newendian.retichat.data.db.dao.ChannelDao.adoptLegacyNameState]).
 */
@Entity(tableName = "channel_name_state", primaryKeys = ["channelId", "postingHex"])
data class ChannelNameStateEntity(
    val channelId: String,
    val postingHex: String,
    val lastDigestHex: String,
    val lastIncludedAt: Long,
)

/**
 * RFed SPEC §17.12 rule 5's record: per channel, the last membership action
 * ([op], `"join"` or `"leave"`) this device made or applied, with its time
 * ([atMs], the action's own milliseconds since the epoch). Persisted, and kept
 * after a leave: a leave's record is what stops an older join from coming
 * back. Written for every join and leave the user makes here, whether or not
 * the device holds a distro.
 */
@Entity(tableName = "channel_membership")
data class ChannelMembershipEntity(
    @PrimaryKey val channelId: String,
    val op: String,
    val atMs: Long,
)

/**
 * One message this device owes its distro (RFed SPEC §17.12 "On the sending
 * device", §17.11): kept here until the propagation node proves the upload.
 * [id] is `channel:<channel hash>` for a membership message (one per channel:
 * a later action replaces it) or `sent:<LXMF message hash>` for a sent-copy.
 * [packed] is the LXMF message as the distro packed and signed it when the
 * user acted, never packed again; [msgHash] is its LXMF message hash, which is
 * what tells one message from another under the same [id]. [distro] is the
 * distro's `lxmf.delivery` hash and [distroKey] its public key, so a sent-copy
 * is still encrypted to its own distro after the device gives that distro up.
 * A channel entry adds [op], [name] and [atMs]; a sent entry adds [toHex].
 * [seq] orders what is owed, oldest first.
 *
 * [sealed] and [syncSig] (database 15) are the distro sync proof (RFed SPEC
 * §17.13): [packed] encrypted to the distro once, when it was owed, and the
 * distro's signature over those bytes, written in the same write as the rest.
 * Both or neither, and null for every row owed before version 15: such a row
 * is built the legacy way and wakes the distro's devices once.
 */
@Entity(tableName = "distro_outbox")
data class DistroOutboxEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val distro: String,
    val distroKey: String,
    val msgHash: String,
    val packed: ByteArray,
    val label: String,
    val op: String? = null,
    val name: String? = null,
    val atMs: Long? = null,
    val toHex: String? = null,
    val seq: Long,
    val sealed: ByteArray? = null,
    val syncSig: ByteArray? = null,
)

/**
 * The creator's copy of a member's answer (database 14; RFed-spec Group.md,
 * "The creator's answer: status"): per group ([chatId]) and member
 * ([memberHex]), the packed LXMF message ([packed]: `destination | source |
 * signature | payload`, exactly as received) of the newest accept or leave
 * that counted ([action]). A leave is kept for good: nothing replaces it. The
 * creator answers each keyed accept with the other members' copies. Only the
 * creator keeps any, only for a listed member that has not left, and none over
 * 2144 bytes; they go with the group.
 */
@Entity(tableName = "group_statuses", primaryKeys = ["chatId", "memberHex"])
data class GroupStatusEntity(
    val chatId: String,
    val memberHex: String,
    val action: String,
    val packed: ByteArray,
)

/**
 * An accept or leave held until its source's key is here (database 14;
 * RFed-spec Group.md, "Verifying an accept or leave": from the switch, around
 * 2026-10-26, a message no key can verify is not counted yet). [packed] is the
 * message exactly as received; it is checked again, with [GroupStatusEntity]'s
 * bound, when a key arrives (an invite, the user's accept, an announce, a
 * verified message of the member's, a key an accept or status element carries)
 * and counted, dropped or kept. [msgHash] is its LXMF message hash, so one
 * message is held once. Bounded (128 in all, 4 for one member of a group), as
 * anyone can send one for a member whose key is not here; they go with the group.
 */
@Entity(tableName = "group_held")
data class GroupHeldEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val chatId: String,
    val srcHex: String,
    val action: String,
    val msgHash: String,
    val packed: ByteArray,
)

/**
 * A held arrival (database 16; RFed SPEC §17.14, GROUPS-DISTRO-DESIGN.md §6.5):
 * a group message, or a `post` group sync ([isPostSync]), that reached this
 * device through the distro [distro] while its group [groupId] was not joined
 * here, when that was the sole reason it would have been dropped. [blob] is the
 * fan-out blob exactly as received; when the group becomes joined here it is fed
 * to the distro path again, in arrival order ([rowId]), and judged as a new
 * arrival. [msgHash] is its LXMF hash and [srcHex] its LXMF source; [blobDigest]
 * is the SHA-256 of [blob], which stands for its signature (the unwrap does not
 * report it; another signature is another blob), so a copy with another
 * signature never shadows one held, and a live copy and a pull copy of one blob
 * are held once. [validated] is the receipt's verdict: a validated copy replaces
 * an unverified one with the same hash. Bounded by counts and bytes
 * ([com.newendian.retichat.data.repository.HeldArrivals]); never recorded as
 * seen; forgotten when the group is closed here or the device gives up [distro].
 */
@Entity(tableName = "group_arrivals")
data class GroupArrivalEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val groupId: String,
    val srcHex: String,
    val msgHash: String,
    val blobDigest: String,
    val validated: Boolean,
    val distro: String,
    val isPostSync: Boolean,
    val blob: ByteArray,
)

/** A held arrival without its blob, for the bounds ([GroupArrivalEntity]); [size] is the blob's length. */
data class GroupArrivalHead(
    val rowId: Long,
    val groupId: String,
    val srcHex: String,
    val msgHash: String,
    val blobDigest: String,
    val validated: Boolean,
    val distro: String,
    val isPostSync: Boolean,
    val size: Int,
)
