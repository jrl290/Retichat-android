package com.newendian.retichat

import com.newendian.retichat.data.db.entity.GroupMemberEntity

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
}