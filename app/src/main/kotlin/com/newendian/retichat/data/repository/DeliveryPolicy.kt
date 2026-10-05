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
        /** Invited, not accepted yet: the group relays for nobody, and its chat asks nothing of the members. */
        PENDING,
        /** Created or accepted by the user. */
        JOINED,
    }

    private val HEX32 = Regex("^[0-9a-f]{32}$")

    /** A member hash as it must travel, 32 lowercase hex; anything else is no hash (null). */
    fun hash(value: String?): String? = value?.trim()?.takeIf { HEX32.matches(it) }

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
    ): Boolean {
        val forged = signature == Signature.INVALID
        val proven = signature == Signature.VALIDATED
        if (action == GroupChatManager.Action.INVITE) return sourceAllowed && !closed && !forged
        if (held == Held.NONE) return false
        if (action == null) return true
        return when (action) {
            GroupChatManager.Action.RELAY_REQUEST ->
                held == Held.JOINED && sourceStatus == MemberStatus.ACCEPTED && proven
            // A source whose key is not here yet (SOURCE_UNKNOWN) is counted
            // as v0.1.8 counts it. The risk: anyone who knows the group id can
            // forge a listed member's leave, and a leave is final. The next
            // release closes it by carrying the member's key in each accept
            // and leave.
            GroupChatManager.Action.ACCEPT, GroupChatManager.Action.LEAVE ->
                isCurrent(sourceStatus) && !namesOther && !forged
            else -> held == Held.JOINED && isCurrent(sourceStatus) && proven
        }
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
}
