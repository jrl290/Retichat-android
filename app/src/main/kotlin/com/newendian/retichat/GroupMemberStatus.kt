package com.newendian.retichat

import com.newendian.retichat.data.db.entity.GroupMemberEntity
import com.newendian.retichat.data.repository.DeliveryPolicy
import com.newendian.retichat.service.GroupChatManager

object MemberStatus {
    const val INVITED = "invited"
    const val ACCEPTED = "accepted"
    const val LEFT = "left"
    const val DECLINED = "declined"

    fun displayLabel(status: String): String = when (status) {
        ACCEPTED -> "Accepted"
        INVITED -> "Invited"
        LEFT -> "Left"
        DECLINED -> "Declined"
        else -> status
    }
}

object GroupMemberStatuses {
    fun isPendingInvite(members: List<GroupMemberEntity>, selfHex: String): Boolean {
        return members.any {
            it.destHashHex == selfHex && it.inviteStatus == MemberStatus.INVITED
        }
    }

    fun acceptedMemberHexes(members: List<GroupMemberEntity>, selfHex: String): List<String> {
        return members.asSequence()
            .filter { it.destHashHex != selfHex }
            .filter { it.inviteStatus == MemberStatus.ACCEPTED }
            .map { it.destHashHex }
            .distinct()
            .toList()
    }

    /**
     * [hex]'s status in a group's member list, null when it is not listed. A
     * member listed twice counts by its most final status (left, then
     * accepted).
     */
    fun statusOf(members: List<GroupMemberEntity>, hex: String): String? {
        val statuses = members.filter { it.destHashHex == hex }.map { it.inviteStatus }
        return when {
            statuses.isEmpty() -> null
            MemberStatus.LEFT in statuses || MemberStatus.DECLINED in statuses -> MemberStatus.LEFT
            MemberStatus.ACCEPTED in statuses -> MemberStatus.ACCEPTED
            else -> statuses.first()
        }
    }

    /** Where the user ([selfHex]) stands in a group held here: joined once its own row is accepted. */
    fun held(groupExists: Boolean, members: List<GroupMemberEntity>, selfHex: String): DeliveryPolicy.Held = when {
        !groupExists -> DeliveryPolicy.Held.NONE
        statusOf(members, selfHex) == MemberStatus.ACCEPTED -> DeliveryPolicy.Held.JOINED
        else -> DeliveryPolicy.Held.PENDING
    }

    /**
     * What a member's own accept or leave ([action]) moves it to from
     * [current], or null when it changes nothing: a hash not on the list
     * never becomes a member, a member that left (a decline arrives as a
     * leave) stays left, and an accept from a member already accepted is
     * nothing new.
     */
    fun statusChange(current: String?, action: String): String? {
        val next = when (action) {
            GroupChatManager.Action.ACCEPT -> MemberStatus.ACCEPTED
            GroupChatManager.Action.LEAVE -> MemberStatus.LEFT
            else -> return null
        }
        if (!DeliveryPolicy.isCurrent(current)) return null
        return if (current == next) null else next
    }

    /**
     * Who the user's leave goes to, a decline's included: every member on the
     * list but the user and the members that left. A member still invited is
     * sent it too: it may have accepted already, or accept later, and must
     * not go on counting the user as a member.
     */
    fun leaveTargets(members: List<GroupMemberEntity>, selfHex: String): List<String> =
        members.map { it.destHashHex }.distinct()
            .filter { it != selfHex && DeliveryPolicy.isCurrent(statusOf(members, it)) }
}

/**
 * The groups the user rejected or left, for good (James, 2026-10-01: "Once
 * the group is rejected/left, that person cannot rejoin"): a later invite to
 * one of them is ignored, from anyone, so nothing offers it again. Kept
 * oldest first and bounded, as the web keeps it (Retichat-js
 * `GroupStore.close`, the last 500).
 */
object ClosedGroups {
    const val LIMIT = 500

    /** [entries] with [groupId] recorded as the newest, the oldest dropped past [LIMIT]. */
    fun record(entries: List<String>, groupId: String): List<String> =
        (entries.filter { it != groupId } + groupId).takeLast(LIMIT)
}
