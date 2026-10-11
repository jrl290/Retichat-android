package com.newendian.retichat.data.repository

import com.newendian.retichat.GroupMemberStatuses
import com.newendian.retichat.MemberStatus
import com.newendian.retichat.bridge.GroupEntry
import com.newendian.retichat.bridge.GroupNative
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.data.db.entity.ChatEntity
import com.newendian.retichat.data.db.entity.GroupMemberEntity
import com.newendian.retichat.data.model.toHex
import com.newendian.retichat.names.Signature
import com.newendian.retichat.service.GroupChatManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MEMBER STATUSES AND KEYS: RFed-spec Group.md, "Member statuses and keys
 * (2026-10-06)"; LXMF-rust/DISPLAY_NAMES.md §10 key 10; approved by James on
 * 2026-10-06. The Android port of the web's `lib/group_status.js` and of what
 * its `app.js` does with it (Retichat-js, branch `group-statuses`), checked
 * against the shared vectors (LXMF-rust `tests/group_status_vectors.json`).
 *
 * Two problems are closed: a member who accepts late never learns of the
 * accepts that came before the invite, and an accept or leave from a sender
 * whose key the receiver never got cannot be verified, yet a leave counts as
 * final. In short:
 *
 *  - Every accept and leave (a decline is a leave) carries exactly the
 *    sender's own `hash:base64key` ([GroupEnvelopes]). The native layer binds
 *    it (the key's `lxmf.delivery` destination is the entry's hash, which is the
 *    message's LXMF source) and, for a source nobody held a key for, checks the
 *    signature under it, so the message arrives validated or invalid, never
 *    unverifiable for want of a key.
 *  - [verdict]: verified counts under the group rules; forged (invalid under a
 *    held or a bound key) is ignored; unverifiable (no key held, none carried
 *    that binds) counts until the switch (`GROUP_ENTRIES_IN_RETICHAT_FIELD`,
 *    around 2026-10-26) and is held from it, decided when the key arrives.
 *  - The creator (recorded per group: this device's own hash at creation, the
 *    invite's source otherwise) keeps each member's newest counted accept or
 *    leave as the packed message it received, and answers an accept that
 *    counted and carried a binding key with one `status`: every other listed
 *    member's copy, by member hash ascending. It never answers a keyless
 *    accept or a leave, answers each accept once, and never again.
 *  - A `status` counts only from the group's creator, in a group the user
 *    accepted; each element is checked on its own as if it had arrived
 *    directly, and one that fails is skipped, so the creator can only pass on
 *    what the members signed. A group held before this change has no creator
 *    recorded: it neither answers nor counts a status.
 *
 * No timers and no retries (DESIGN_PRINCIPLES §1, §3): nothing here waits, and
 * a status that does not leave is not sent again.
 */
object GroupStatuses {
    /** The longest payload of an accept or leave worth keeping or passing on (`STATUS_PAYLOAD_LIMIT`). */
    const val STATUS_PAYLOAD_LIMIT = 2048

    /** destination (16) + source (16) + signature (64). */
    const val PACKED_HEADER_LENGTH = 96

    /** The longest packed accept or leave: an element over this is skipped, and a creator keeps no copy of one. */
    const val STATUS_PACKED_LIMIT = PACKED_HEADER_LENGTH + STATUS_PAYLOAD_LIMIT

    /** Entries held for a key, in all (`GroupStore.HELD_LIMIT`). */
    const val HELD_LIMIT = 128

    /** Entries held for one member of one group (`GroupStore.HELD_PER_MEMBER`). */
    const val HELD_PER_MEMBER = 4

    /** What an accept or leave is worth. */
    enum class Verdict { COUNT, IGNORE, HOLD }

    /**
     * What an accept or leave that passed the group rule is worth, from its
     * [signature] ([Signature]) and whether the switch has happened
     * ([switched]: `GROUP_ENTRIES_IN_RETICHAT_FIELD`):
     *
     * | signature | before the switch | from the switch |
     * |---|---|---|
     * | validated | count | count |
     * | invalid | ignore | ignore |
     * | source unknown | count | hold |
     *
     * Anything else is not counted.
     */
    fun verdict(signature: Int, switched: Boolean): Verdict = when (signature) {
        Signature.VALIDATED -> Verdict.COUNT
        Signature.SOURCE_UNKNOWN -> if (switched) Verdict.HOLD else Verdict.COUNT
        else -> Verdict.IGNORE
    }

    /** One GROUP_MEMBER_KEYS entry, `hash:base64-public-key`: the form an invite chunk uses. */
    fun keyEntry(hashHex: String, publicKey: ByteArray): String =
        "$hashHex:${java.util.Base64.getEncoder().encodeToString(publicKey)}"
}

/** The creator's copy of a member's newest counted accept or leave. */
class StatusCopy(val memberHex: String, val action: String, val packed: ByteArray)

/** An accept or leave held until its source's key is here. [id] is the store's, 0 before it is held. */
class HeldEntry(
    val id: Long = 0,
    val chatId: String,
    val srcHex: String,
    val action: String,
    val msgHash: String,
    val packed: ByteArray,
)

/** What [GroupStatusEngine] keeps and changes (Room in the app, memory in the JVM tests). */
interface GroupStatusStore {
    /** The group held here with id [groupId] (a chat that is a group), or null. */
    suspend fun chatByGroupId(groupId: String): ChatEntity?

    suspend fun chatById(chatId: String): ChatEntity?

    suspend fun members(chatId: String): List<GroupMemberEntity>

    suspend fun setMemberStatus(chatId: String, memberHex: String, status: String)

    /** The system line about a member who joined or left: [id] is its row, [timestampMs] the message's own time. */
    suspend fun memberLine(chatId: String, memberHex: String, joined: Boolean, id: String, timestampMs: Long)

    /** [memberHex] passes the privacy filter from now on: a member of a joined group, by association. */
    suspend fun allow(memberHex: String)

    /** Keep [publicKey] as [memberHex]'s key, on a hidden contact row, as an invite chunk's key is kept. */
    suspend fun keepKey(memberHex: String, publicKey: ByteArray)

    suspend fun copy(chatId: String, memberHex: String): StatusCopy?

    suspend fun putCopy(chatId: String, memberHex: String, action: String, packed: ByteArray)

    /** The group's copies, by member hash ascending. */
    suspend fun copies(chatId: String): List<StatusCopy>

    /** Every held entry, oldest first. */
    suspend fun held(): List<HeldEntry>

    suspend fun heldCount(): Int

    suspend fun heldCountFor(chatId: String, srcHex: String): Int

    suspend fun heldFrom(srcHex: String): Int

    suspend fun isHeld(msgHash: String): Boolean

    suspend fun hold(entry: HeldEntry)

    suspend fun release(entry: HeldEntry)
}

/** A member's own accept or leave as it reached this device directly, after the group rule kept it. */
class Arrival(
    /** The LXMF message hash (hex). */
    val msgId: String,
    /** The LXMF source (hex): the member it speaks for. */
    val src: String,
    val chat: ChatEntity,
    /** [GroupChatManager.Action.ACCEPT] or [GroupChatManager.Action.LEAVE]. */
    val action: String,
    /** The message's own time, milliseconds. */
    val timestampMs: Long,
    /** [Signature], with the carried key already applied by the native layer. */
    val signature: Int,
    /** The 64-byte key it carries for its own sender when that binds; a creator answers only an accept that has one. */
    val senderKey: ByteArray?,
    /** The message exactly as received; null for a message over [GroupStatuses.STATUS_PACKED_LIMIT]. */
    val packed: ByteArray?,
)

/** What a `status` did, element by element. */
data class StatusTally(val elements: Int, val counted: Int, val held: Int, val skipped: Int)

/**
 * The decisions of the group follow-up (see [GroupStatuses]), apart from Room,
 * the JNI and the UI, which are ports: [store], [native] and [answer]. The one
 * place that decides what an accept or leave is worth and what it changes, for
 * the message arriving directly ([takeDirect]), for each element of a `status`
 * ([takeStatuses]) and for an entry held for a key ([decideHeld]), so the
 * three cannot differ.
 *
 * Everything runs under one lock, one event at a time: the receive path
 * launches a coroutine for each message, and a leave must stay final however
 * two messages race.
 */
class GroupStatusEngine(
    private val store: GroupStatusStore,
    private val native: GroupNative,
    private val selfHex: () -> String,
    /** Send the creator's `status` [statuses] to [memberHex] (fire and forget; once per accept). */
    private val answer: suspend (chat: ChatEntity, memberHex: String, statuses: List<ByteArray>) -> Unit,
    /** `GROUP_ENTRIES_IN_RETICHAT_FIELD`: from the switch an unverifiable accept or leave is held, not counted. */
    private val switched: Boolean = LxmfFields.GROUP_ENTRIES_IN_RETICHAT_FIELD,
    private val log: (String) -> Unit = {},
    /**
     * Who the user is in [ChatEntity]'s group (GROUPS-DISTRO-DESIGN.md §9.1):
     * its own address, whose row says pending or joined, and every self
     * address. The creator test is "creator is self", so any device of a
     * distro that created the group answers (§12.1); an element about any self
     * address is skipped as the user's own; no self address is allowlisted.
     * The device alone by default (a group with no distro).
     */
    private val selves: suspend (ChatEntity) -> GroupSelf = { GroupSelf(selfHex(), null, setOf(selfHex())) },
    /** Any self address of this device, in any group: its key is never remembered (§9.2). */
    private val isSelfAnywhere: (String) -> Boolean = { it == selfHex() },
) {
    private val lock = Mutex()

    private enum class Taken { COUNTED, HELD, IGNORED }

    // ---- What any group message the rule kept brings ----

    /**
     * Before a group message the rule kept ([DeliveryPolicy.groupMessage]) is
     * handled: the key an accept or leave carries for its sender ([senderKey]) is
     * remembered as an invite chunk's key is, after the rule and never for a
     * message the rule dropped, and when the message verified ([signature]
     * validated) the accepts and leaves held for its source's key are decided
     * now, before this message, so they count in the order they came.
     */
    suspend fun onKept(src: String, action: String?, signature: Int, senderKey: ByteArray?) = lock.withLock {
        if ((action == GroupChatManager.Action.ACCEPT || action == GroupChatManager.Action.LEAVE) && senderKey != null) {
            remember(src, senderKey)
        }
        if (signature == Signature.VALIDATED && store.heldFrom(src) > 0) decideHeldLocked(src)
    }

    /**
     * A key may have arrived for [source] (an announce) or for any member
     * ([source] null: an invite brought keys, the user is accepting): decide
     * the entries held for it. Cheap when none is held.
     */
    suspend fun decideHeld(source: String? = null) = lock.withLock {
        if (source == null || store.heldFrom(source) > 0) decideHeldLocked(source)
    }

    // ---- An accept or leave arriving directly ----

    /**
     * A member's own accept or leave the group rule kept: it counts, is
     * ignored or is held by its signature ([GroupStatuses.verdict]); what
     * counts moves the member (a leave is final), keeps the creator's copy and
     * lets an accepted member through the privacy filter. The creator answers
     * an accept that counted and carries a binding key, and no other
     * (RFed-spec Group.md, "The creator's answer"). Returns whether it counted.
     */
    suspend fun takeDirect(arrival: Arrival): Boolean = lock.withLock {
        val taken = takeChange(arrival.chat, arrival.src, arrival.action, arrival.msgId, arrival.timestampMs, arrival.signature, arrival.packed, "directly")
        val counted = taken == Taken.COUNTED
        if (counted && arrival.action == GroupChatManager.Action.ACCEPT && arrival.senderKey != null) {
            answerAccept(arrival.chat, arrival.src)
        }
        counted
    }

    // ---- A status ----

    /**
     * A `status` the group rule took (only from the group's creator, for a
     * group the user joined, signed by it): the creator's answer to the user's
     * accept, [elements] its GROUP_STATUSES as decoded, the members' own accepts
     * and leaves as the creator received them. Each element is checked on its
     * own as if it had arrived directly ([takeElement]); one that fails is
     * skipped, so the creator can pass on only what the members signed; one
     * that passes counts under the rules of the message arriving directly, so
     * order does not matter and a leave stays final. An honest creator sends at
     * most one element for each member but the two it leaves out, so no more
     * than the member list's length are read: each costs a signature check.
     */
    suspend fun takeStatuses(chat: ChatEntity, elements: List<Any?>?): StatusTally = lock.withLock {
        val groupId = chat.groupIdHex ?: return@withLock StatusTally(0, 0, 0, 0)
        val all = elements ?: emptyList()
        val members = store.members(chat.id)
        val memberCount = members.map { it.destHashHex }.distinct().size
        var counted = 0
        var held = 0
        var skipped = all.size - minOf(all.size, memberCount)
        val joined = GroupMemberStatuses.held(true, members, selves(chat).own) == DeliveryPolicy.Held.JOINED
        all.take(memberCount).forEachIndexed { index, element ->
            val result = if (joined) takeElement(chat, groupId, element) else "the user has not accepted the group"
            when (result) {
                COUNTED -> { counted++; log("Element $index of the status for group ${groupId.take(8)}: counted") }
                HELD -> { held++; log("Element $index of the status for group ${groupId.take(8)}: held until its key is here") }
                else -> {
                    skipped++
                    log("Skipped element $index of the status for group ${groupId.take(8)}: $result")
                }
            }
        }
        log("Status for group ${groupId.take(8)}: ${all.size} element(s), $counted counted, $held held, $skipped skipped")
        StatusTally(all.size, counted, held, skipped)
    }

    /**
     * One element of a `status`, checked as if it had arrived directly: it
     * must be a packed LXMF message (97..=2144 bytes) that unpacks, about this
     * group, an accept or a leave, from a source that is a member listed for the
     * group and has not left, naming nobody else, and not about this device. Its
     * signature is checked as any message's is (the native layer: under the key
     * held for its source, or the one it carries once bound), and the group rule
     * and the verdict are those of the message arriving directly. Its
     * destination is the creator, not this device: it is the member's own
     * statement to the creator, and is not asked. Forwarded elements do not
     * teach display names. Returns [COUNTED], [HELD] (unverifiable from the
     * switch) or why it was skipped.
     */
    private suspend fun takeElement(chat: ChatEntity, groupId: String, element: Any?): String {
        if (element !is ByteArray) return "not a bin"
        if (element.size <= GroupStatuses.PACKED_HEADER_LENGTH || element.size > GroupStatuses.STATUS_PACKED_LIMIT) {
            return "${element.size} bytes is not the size of an accept or leave"
        }
        val unpacked = native.unpack(element) ?: return "not an LXMF message"
        val fields = LxmfFields.decodeOrEmpty(unpacked.fieldsRaw)
        val about = fields.group(GroupEntry.ID) ?: return "no group id"
        if (about != groupId) return "about another group"
        val action = fields.group(GroupEntry.ACTION)
        if (action != GroupChatManager.Action.ACCEPT && action != GroupChatManager.Action.LEAVE) {
            return "the action is ${action ?: "none"}, not an accept or a leave"
        }
        val src = unpacked.source.toHex()
        val self = selves(chat)
        if (self.isSelf(src)) return "about the user (${src.take(8)} is a self address)"
        val members = store.members(chat.id)
        val allowed = DeliveryPolicy.groupMessage(
            action = action,
            sourceAllowed = false,
            held = GroupMemberStatuses.held(true, members, self.own),
            sourceStatus = GroupMemberStatuses.statusOf(members, src),
            namesOther = DeliveryPolicy.namesOther(fields.group(GroupEntry.SENDER), src),
            closed = false,
            signature = unpacked.signature,
        )
        if (!allowed) {
            return if (unpacked.signature == Signature.INVALID) "its signature does not verify: it is not its source's"
            else "its source is not a member listed for the group that has not left, or it names another"
        }
        // The key it carries is remembered as the one an accept arriving directly brings.
        unpacked.senderKey?.let { remember(src, it) }
        val taken = takeChange(
            chat, src, action, unpacked.hash.toHex(), (unpacked.timestamp * 1000).toLong(), unpacked.signature, element, "in a status",
        )
        return if (taken == Taken.COUNTED) COUNTED else HELD
    }

    // ---- The one place that decides ----

    /**
     * An accept or leave of member [src] that passed the group rule (a listed
     * member that has not left, about itself, not forged), decided by its
     * signature ([GroupStatuses.verdict]). [packed] is the message as received.
     */
    private suspend fun takeChange(
        chat: ChatEntity,
        src: String,
        action: String,
        msgId: String,
        timestampMs: Long,
        signature: Int,
        packed: ByteArray?,
        via: String,
    ): Taken = when (GroupStatuses.verdict(signature, switched)) {
        GroupStatuses.Verdict.HOLD -> { hold(chat, src, action, msgId, packed); Taken.HELD }
        GroupStatuses.Verdict.IGNORE -> {
            log("Group ${chat.groupIdHex.orEmpty().take(8)}: $action of ${src.take(12)} arriving $via ignored: its signature does not verify (signature=$signature)")
            Taken.IGNORED
        }
        GroupStatuses.Verdict.COUNT -> {
            // Greppable for the staging probe (test-harnesses/staging/probe_phone_group_statuses.mjs).
            log("Group ${chat.groupIdHex.orEmpty().take(8)}: $action of ${src.take(12)} arriving $via counted, ${if (signature == Signature.VALIDATED) "verified" else "unverified"} (signature=$signature)")
            applyChange(chat, src, action, msgId, timestampMs, packed)
            Taken.COUNTED
        }
    }

    /**
     * Every accept or leave that counts comes through here (arriving, held and
     * decided later, or an element of a `status`), so this is where the creator
     * keeps its copy, before the member's status moves: a repeated accept,
     * which changes nothing, is the newest copy too.
     */
    private suspend fun applyChange(
        chat: ChatEntity,
        src: String,
        action: String,
        msgId: String,
        timestampMs: Long,
        packed: ByteArray?,
    ) {
        val self = selves(chat)
        val members = store.members(chat.id)
        if (packed != null && self.isSelf(chat.creatorHex)) {
            keepCopy(chat, GroupMemberStatuses.statusOf(members, src), src, action, packed)?.let {
                log("No copy kept of the $action of ${src.take(12)} for group ${chat.id}: $it")
            }
        }
        val next = GroupMemberStatuses.statusChange(GroupMemberStatuses.statusOf(members, src), action)
        if (next == null) {
            log("group ${chat.id}: $action from ${src.take(8)} changes nothing")
            return
        }
        store.setMemberStatus(chat.id, src, next)
        val accepted = action == GroupChatManager.Action.ACCEPT
        log("${src.take(8)} ${if (accepted) "accepted" else "left"} group ${chat.id}")
        // In a group the user joined, a member's accept passes the privacy
        // filter, as the user's own accept made every member. A pending group
        // lets nobody through: the members pass from the user's accept (and the
        // creator's answer to it brings back what the filter dropped).
        if (accepted && !self.isSelf(src) && GroupMemberStatuses.held(true, members, self.own) == DeliveryPolicy.Held.JOINED) {
            store.allow(src)
        }
        val groupId = chat.groupIdHex.orEmpty()
        store.memberLine(
            chat.id, src, accepted,
            id = if (accepted) "acc_${src.take(8)}_${groupId.take(8)}" else msgId,
            timestampMs = timestampMs,
        )
    }

    /**
     * Keep [packed] as member [src]'s copy for the creator's answer: the
     * newest accept or leave of that member that counted, one per member,
     * replaced by the next that counts. A leave is final here as everywhere:
     * nothing replaces it, and nothing is kept for a member that left. Returns
     * null when kept, else why not (the sender is on no list, it left, the
     * copy is over [GroupStatuses.STATUS_PACKED_LIMIT]).
     */
    private suspend fun keepCopy(chat: ChatEntity, status: String?, src: String, action: String, packed: ByteArray): String? {
        if (action != GroupChatManager.Action.ACCEPT && action != GroupChatManager.Action.LEAVE) {
            return "it is neither an accept nor a leave"
        }
        if (status == null) return "its sender is on no list"
        if (status == MemberStatus.LEFT) return "its sender left, for good"
        if (packed.isEmpty()) return "there is no packed message"
        if (packed.size > GroupStatuses.STATUS_PACKED_LIMIT) {
            return "it is over ${GroupStatuses.STATUS_PACKED_LIMIT} bytes, which no accept or leave is"
        }
        if (store.copy(chat.id, src)?.action == GroupChatManager.Action.LEAVE) return "a leave is kept: nothing replaces it"
        store.putCopy(chat.id, src, action, packed)
        return null
    }

    // ---- The creator's answer ----

    /**
     * The creator's answer to an accept that counted and carried its sender's
     * key: one `status` to member [memberHex] alone, holding the copy kept of
     * each other listed member's newest counted accept or leave (neither this
     * device's own nor the recipient's), by member hash ascending. A member that
     * has not answered is absent: still invited. An empty list is a valid
     * answer. Only the creator answers: any other device sends nothing. The
     * creator test is "creator is self" (GROUPS-DISTRO-DESIGN.md §12.1): when the
     * creator is the user's distro, every device of it that holds the group
     * answers, each with the copies it kept, and no copy of a self address goes.
     */
    private suspend fun answerAccept(chat: ChatEntity, memberHex: String) {
        val self = selves(chat)
        if (!self.isSelf(chat.creatorHex)) return
        val listed = store.members(chat.id).map { it.destHashHex }.toSet()
        val statuses = store.copies(chat.id)
            .filter { !self.isSelf(it.memberHex) && it.memberHex != memberHex && it.memberHex in listed }
            .sortedBy { it.memberHex }
            .map { it.packed }
        answer(chat, memberHex, statuses)
        log("Status for group ${chat.id} with ${statuses.size} element(s) to ${memberHex.take(12)}")
    }

    // ---- Held until a key is here ----

    /**
     * An accept or leave no key can verify, from the switch: held with what its
     * signature covers (the packed message, as received), and decided once the
     * key is here ([decideHeld]). Bounded, as anyone can send one for a member
     * whose key is not here yet: a copy over the limit, one held already, past
     * [GroupStatuses.HELD_PER_MEMBER] for the member or [GroupStatuses.HELD_LIMIT]
     * in all is dropped, and those already held stay.
     */
    private suspend fun hold(chat: ChatEntity, src: String, action: String, msgId: String, packed: ByteArray?) {
        val refused = when {
            packed == null || packed.isEmpty() -> "what its signature covers was not kept"
            store.isHeld(msgId) -> "it is held already"
            store.heldCountFor(chat.id, src) >= GroupStatuses.HELD_PER_MEMBER -> "${GroupStatuses.HELD_PER_MEMBER} are held for this member already"
            store.heldCount() >= GroupStatuses.HELD_LIMIT -> "${GroupStatuses.HELD_LIMIT} are held already"
            else -> null
        }
        if (refused != null) {
            log("Dropped $action for group ${chat.id} from ${src.take(12)}: no key for it here to check its signature, and it cannot be held ($refused)")
            return
        }
        store.hold(HeldEntry(chatId = chat.id, srcHex = src, action = action, msgHash = msgId, packed = packed!!))
        log("Holding $action for group ${chat.id} from ${src.take(12)} until its key is here to check its signature")
    }

    /**
     * Decide every held accept or leave (of [source] only, when given) whose
     * source's key is now here: counted when its signature verifies, dropped
     * when it does not (it was not that member's). One whose key is still
     * missing stays held; one whose group is gone was released with it. In the
     * order they came.
     */
    private suspend fun decideHeldLocked(source: String?) {
        for (entry in store.held()) {
            if (source != null && entry.srcHex != source) continue
            val unpacked = native.unpack(entry.packed)
            if (unpacked == null) {
                store.release(entry)
                log("Dropped held ${entry.action} for group ${entry.chatId} from ${entry.srcHex.take(12)}: it no longer unpacks")
                continue
            }
            if (unpacked.signature == Signature.SOURCE_UNKNOWN) continue
            store.release(entry)
            val chat = store.chatById(entry.chatId)
            if (chat == null) continue
            if (unpacked.signature != Signature.VALIDATED) {
                log("Dropped held ${entry.action} for group ${entry.chatId} from ${entry.srcHex.take(12)}: its signature does not verify with that member's key, so it is not the member's")
                continue
            }
            log("Held ${entry.action} for group ${entry.chatId} from ${entry.srcHex.take(12)} verifies: counted")
            applyChange(chat, entry.srcHex, entry.action, entry.msgHash, (unpacked.timestamp * 1000).toLong(), entry.packed)
        }
    }

    // ---- Keys ----

    /**
     * Keep [key] as [src]'s: the native layer remembers it only when it
     * produces [src] as an `lxmf.delivery` destination, and the member's hidden
     * contact row holds it (the user's accept waits for every member's key). This
     * device's own key is never kept.
     */
    private suspend fun remember(src: String, key: ByteArray) {
        if (isSelfAnywhere(src)) return
        if (!native.rememberKey(src, key)) {
            log("Ignored mismatched member key for ${src.take(8)}")
            return
        }
        store.keepKey(src, key)
    }

    private companion object {
        const val COUNTED = "counted"
        const val HELD = "held"
    }
}
