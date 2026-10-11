package com.newendian.retichat.data.repository

import com.newendian.retichat.MemberStatus
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.names.Signature
import com.newendian.retichat.service.GroupChatManager

/**
 * Which inbound LXMF messages the app keeps (DISPLAY_NAMES.md §7, "Android
 * privacy filter", "Group trust rule" and "Group model").
 *
 * The privacy filter is enforced here, in the app, and the router's own
 * stranger filter is kept off. The router's allowlist can only name a
 * source, and an allowlist in the router would also have to be rebuilt in
 * memory at every start before the first delivery. Until 2026-09-27 the
 * router filter was on with an allowlist nothing filled, and every
 * router-delivered message was dropped, contacts' included.
 *
 * Groups follow James's model (2026-10-01): "Groups start by invite. If the
 * invite doesn't come from someone on the allowlist, it is ignored. If the
 * invite is accepted, the other group members are considered allowed." and
 * "There are no membership changes for a group. One person starts the group
 * with the membership list. Each person can accept or reject. And each
 * person can leave at any time. Once the group is rejected/left, that person
 * cannot rejoin." The source a rule asks about is always the packet's own
 * LXMF source, never GROUP_SENDER. Retichat-js `shouldProcessGroupMessage`
 * is the same rule.
 */
object DeliveryPolicy {
    /**
     * A direct message, or a group invite's source: allowed when the filter is
     * off, or when [contactExists] and the contact is [allowlisted].
     */
    fun allowlisted(filterStrangers: Boolean, contactExists: Boolean, allowlisted: Boolean): Boolean =
        !filterStrangers || (contactExists && allowlisted)

    /** Where the user stands in the group a message names. */
    enum class Held {
        /** No such group here. */
        NONE,
        /**
         * Invited, not accepted yet: the group relays for nobody, takes no
         * status, and lets nobody through the privacy filter (the members pass
         * from the user's accept, RFed-spec Group.md "The privacy filter").
         */
        PENDING,
        /** Created or accepted by the user. */
        JOINED,
    }

    private val HEX32 = Regex("^[0-9a-f]{32}$")

    /**
     * The invite parser's space (GROUPS-DISTRO-DESIGN.md §10.2; the vectors'
     * `invite_space`): Unicode Zs and U+0009, exactly Swift's
     * `CharacterSet.whitespaces` and LXMF-rust `is_invite_space`, and no other.
     * Kotlin's `trim()` also took newlines, U+000B-U+000D, U+001C-U+001F,
     * U+0085, U+2028 and U+2029, so an entry wrapped in one named D here and
     * not on iOS or in a group sync's `members`.
     */
    fun isInviteSpace(c: Char): Boolean = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /** A member hash as it must travel, 32 lowercase hex; anything else is no hash (null). */
    fun hash(value: String?): String? = value?.trim(::isInviteSpace)?.takeIf { HEX32.matches(it) }

    /** The members an invite lists (GROUP_MEMBERS): its well-formed hashes, once each. */
    fun members(csv: String?): List<String> =
        csv.orEmpty().split(",").mapNotNull { hash(it) }.distinct()

    /**
     * The message names a member other than its packet source [srcHex]: it
     * carries a GROUP_SENDER that is not the source's own hash (one that is
     * no well-formed hash cannot be).
     */
    fun namesOther(groupSender: String?, srcHex: String): Boolean =
        groupSender != null && hash(groupSender) != srcHex

    /** [status] is a current member's: on the list and not left. */
    fun isCurrent(status: String?): Boolean = status == MemberStatus.INVITED || status == MemberStatus.ACCEPTED

    /**
     * Whether a group message is processed.
     *
     * - An invite: only from a source the privacy filter allows
     *   ([sourceAllowed]), never for a group the user rejected or left
     *   ([closed]), and never with a signature that fails.
     * - Anything else only for a group held here.
     * - A plain message (no action): kept, whoever sent it ([groupAuthor]
     *   decides whose it is shown as).
     * - An accept or a leave: only from a current member of the list
     *   ([sourceStatus] is the packet source's status there, null when it is
     *   not listed) that names nobody else ([namesOther]), and never with a
     *   signature that fails.
     * - A relay request: only for a group the user joined, from a member that
     *   accepted it, with a validated signature.
     * - A status (RFed-spec Group.md, "Receiving a status", James 2026-10-06):
     *   the creator's answer to the user's accept, only from the group's
     *   creator ([sourceIsCreator]: the invite's authenticated source, which
     *   the group records; false for a group held before that was recorded),
     *   only for a group the user joined, and only with a validated signature:
     *   the creator's key is held, as every member's is when the user accepts.
     *   Whether the creator has left decides nothing: a status is a carrier,
     *   and each element in it is checked on its own.
     * - Any other action: only from a current member of a joined group, with
     *   a validated signature.
     *
     * [signature] is the message's [Signature] reason.
     */
    fun groupMessage(
        action: String?,
        sourceAllowed: Boolean,
        held: Held,
        sourceStatus: String?,
        namesOther: Boolean,
        closed: Boolean,
        signature: Int,
        sourceIsCreator: Boolean = false,
    ): Boolean {
        val forged = signature == Signature.INVALID
        val proven = signature == Signature.VALIDATED
        if (action == GroupChatManager.Action.INVITE) return sourceAllowed && !closed && !forged
        if (held == Held.NONE) return false
        if (action == null) return true
        return when (action) {
            GroupChatManager.Action.RELAY_REQUEST ->
                held == Held.JOINED && sourceStatus == MemberStatus.ACCEPTED && proven
            GroupChatManager.Action.STATUS ->
                held == Held.JOINED && sourceIsCreator && proven
            // A message that is not verified passes this rule and is decided
            // by what it is worth (GroupStatuses.verdict): an accept or leave
            // carries its sender's own key, so a source nobody held a key for
            // is verified by the key it carries (validated, or invalid and
            // dropped here). One that no key can verify (a client from before
            // 2026-10-06 sends none) is counted until the switch
            // (LxmfFields.GROUP_ENTRIES_IN_RETICHAT_FIELD, around 2026-10-26),
            // as v0.1.8 counts it, and from then on is held until its sender's
            // key arrives. Only the forged one is dropped here.
            GroupChatManager.Action.ACCEPT, GroupChatManager.Action.LEAVE ->
                isCurrent(sourceStatus) && !namesOther && !forged
            else -> held == Held.JOINED && isCurrent(sourceStatus) && proven
        }
    }

    /**
     * The three rules that come first for a group message, on the distro path
     * and the direct path alike (GROUPS-DISTRO-DESIGN.md §6.3), before the
     * action's own rule: null when none drops it, else why (for the log line).
     *
     * - A forged message ([signature] invalid) is dropped, in every group: it
     *   is never held and never notified. D's key is announced, so anyone can
     *   encrypt a post to D claiming a member's source.
     * - A message naming a self address ([selves]) as its GROUP_SENDER while
     *   its packet source [srcHex] is not one is never the user's own: dropped.
     * - A group message from the distro itself ([echoes]: the D this device
     *   holds, and the group's own distro) is the user's own echo: sends exclude
     *   every self address, so it is unexpected, and dropped.
     * - A group message from another self address (a known sibling, another
     *   device of D) whose signature is not validated is dropped, whatever its
     *   action (RFed SPEC §17.14): a device of D is never another member, and
     *   only a validly signed message from it is the user's own act, which goes
     *   on. Until 2026-10-11 only such a post was dropped
     *   ([GroupPost.UNPROVEN_OWN]); its accept, leave, status or invite went on.
     */
    fun firstRules(signature: Int, srcHex: String, groupSender: String?, selves: Set<String>, echoes: Set<String>): String? {
        if (signature == Signature.INVALID) return "its signature is invalid (forged)"
        val named = hash(groupSender)
        if (named != null && named in selves && srcHex !in selves) {
            return "it names the user (${named.take(8)}) as its sender, but its source ${srcHex.take(8)} is not the user's"
        }
        if (srcHex in echoes) return "its source is the user's own distro: the user's own echo"
        if (srcHex in selves && signature != Signature.VALIDATED) {
            return "its source ${srcHex.take(8)} is one of the user's own devices and its signature is not validated (signature=$signature): only a validly signed message from it is the user's own"
        }
        return null
    }

    /**
     * Whether a group message the rule dropped on the distro path is held
     * instead (GROUPS-DISTRO-DESIGN.md §6.5): only when the group not being
     * joined here is the sole reason. Never an invite or a relay request, never
     * for a group joined ([held]), closed or filtered here, never a forged one or
     * one whose packet source is a self address. For a group held pending, the
     * rule evaluated as if it were joined must keep it ([keptIfJoined]); for one
     * not held here nothing about membership can be checked, so only a proven
     * message (its signature validated) is held.
     */
    fun holdable(
        action: String?,
        held: Held,
        closed: Boolean,
        filtered: Boolean,
        signature: Int,
        sourceIsSelf: Boolean,
        keptIfJoined: Boolean,
    ): Boolean {
        if (action == GroupChatManager.Action.INVITE || action == GroupChatManager.Action.RELAY_REQUEST) return false
        if (held == Held.JOINED || closed || filtered || sourceIsSelf) return false
        if (signature == Signature.INVALID) return false
        return when (held) {
            Held.NONE -> signature == Signature.VALIDATED
            Held.PENDING -> keptIfJoined
            Held.JOINED -> false
        }
    }

    /** A plain post the group rule kept, by its packet source (GROUPS-DISTRO-DESIGN.md §9.2). */
    enum class GroupPost {
        /** From a self address (a known sibling), its signature validated: the user's own, stored outgoing, SENT. */
        OWN,
        /** From a self address, its signature not validated: not the user's to show, and not kept. */
        UNPROVEN_OWN,
        /** Anyone else's, with text or an attachment: stored as theirs. */
        MEMBER,
        /** Anyone else's, with neither: nothing to store, and not kept. */
        EMPTY;

        /** Stored: its `grp:` key may be recorded (§6.2); otherwise it is judged again on its next copy. */
        val kept: Boolean get() = this == OWN || this == MEMBER
    }

    /** [GroupPost] of a post from [sourceIsSelf] with [signature], carrying text or an attachment ([hasContent]). */
    fun groupPost(sourceIsSelf: Boolean, signature: Int, hasContent: Boolean): GroupPost = when {
        sourceIsSelf && signature == Signature.VALIDATED -> GroupPost.OWN
        sourceIsSelf -> GroupPost.UNPROVEN_OWN
        hasContent -> GroupPost.MEMBER
        else -> GroupPost.EMPTY
    }

    /**
     * Whom a kept group message is shown as from: its GROUP_SENDER only when
     * a current member of a joined group relays it ([sourceStatus]), signed
     * by that member, and it names a member on the list ([senderListed]).
     * Anyone else's message is its packet source's own: a stranger's, a
     * pending group's member's, an allowlisted contact's that is no member.
     */
    fun groupAuthor(
        groupSender: String?,
        srcHex: String,
        held: Held,
        sourceStatus: String?,
        senderListed: Boolean,
        signature: Int,
    ): String {
        val named = hash(groupSender) ?: return srcHex
        val trusted = held == Held.JOINED && isCurrent(sourceStatus) && signature == Signature.VALIDATED
        return if (trusted && senderListed) named else srcHex
    }

    /**
     * RFed SPEC §17.9: the private key a distro identity transfer on the
     * device address carries (LXMF's custom pair with the transfer type), or
     * null when [fields] carry none. It is offered only when the privacy
     * filter lets its sender through ([allowlisted]), as the web offers it.
     */
    fun distroTransferKey(fields: LxmfFields): String? =
        if (fields.getString(LxmfFields.FIELD_CUSTOM_TYPE) == LxmfFields.DISTRO_TRANSFER_TYPE) {
            fields.getString(LxmfFields.FIELD_CUSTOM_DATA)
        } else null

    /**
     * RFed SPEC §17.11: a distro sent copy is addressed to the distro and
     * reaches a device only through the distro fan-out (RfedDistroClient),
     * never on the device's own address. One that arrives there is forged or
     * misrouted, and kept it would show the user's own message as someone's
     * incoming one. It is dropped first, whoever sent it, filter on or off,
     * as iOS and the web drop it.
     */
    fun isDistroSentCopy(fields: LxmfFields): Boolean =
        fields.getString(LxmfFields.FIELD_CUSTOM_TYPE) == LxmfFields.DISTRO_SENT_TYPE

    /** What a message that arrived directly, on the device's own address, is ([directRoute]). */
    sealed class DirectRoute {
        /**
         * Refused whole: a map in its fields repeats a key ([LxmfFields.Received.Refused];
         * RFed SPEC §17.14 "Repeated keys"). Dropped with one log line before anything
         * else is looked at, never a DM, a group, a transfer or a sent copy.
         */
        data class Refused(val why: String) : DirectRoute()
        /** A distro sent copy ([isDistroSentCopy]): dropped with a log line. */
        object SentCopy : DirectRoute()
        /** A distro identity transfer carrying [key] ([distroTransferKey]): offered when the filter lets its sender through. */
        data class Transfer(val key: String) : DirectRoute()
        /** A GROUP_ID that is not a str of 32 hex characters: refused with a log line, never a DM or a group. */
        object BadGroupId : DirectRoute()
        /** A group message for [groupId], 32 lowercase hex. */
        data class Group(val groupId: String) : DirectRoute()
        /** Everything else: a direct message, under the privacy filter. */
        object Direct : DirectRoute()
    }

    /**
     * [directRoute] for fields as a receive path reads them
     * ([LxmfFields.receive]): a message whose fields repeat a key is
     * [DirectRoute.Refused], before anything else is looked at.
     */
    fun directRoute(received: LxmfFields.Received): DirectRoute = when (received) {
        is LxmfFields.Received.Refused -> DirectRoute.Refused(received.why)
        is LxmfFields.Received.Read -> directRoute(received.fields)
    }

    /**
     * How a message that arrived directly is classified, in RFed SPEC §17.14's
     * order ("On the direct path too"): a GROUP_ID that is not 32 hex (another
     * length, not hex, a bin, an int, nil) is refused first; then a distro sent
     * copy; an identity transfer; a group message, by its group id; then a
     * direct message. So a transfer or a sent copy that also carries a
     * malformed GROUP_ID is refused, as on iOS (cd2282f) and the web (3a0efa6);
     * until 2026-10-11 this path looked at the sent-copy and transfer markers
     * first, and offered such a transfer or dropped such a copy. A
     * `rfed.distro.channel` or `rfed.distro.group` marker means nothing to the
     * group id ([LxmfFields.directGroupEntries]): beside a GROUP_ID it leaves it
     * a group message, as on iOS and the web.
     */
    fun directRoute(fields: LxmfFields): DirectRoute {
        val entries = fields.directGroupEntries()
        if (entries.hasGroupId && entries.groupId == null) return DirectRoute.BadGroupId
        if (isDistroSentCopy(fields)) return DirectRoute.SentCopy
        distroTransferKey(fields)?.let { return DirectRoute.Transfer(it) }
        return entries.groupId?.let { DirectRoute.Group(it) } ?: DirectRoute.Direct
    }
}
