package com.newendian.retichat

import com.newendian.retichat.data.db.entity.GroupMemberEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMemberStatusTest {
    private val selfHex = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val invitedHex = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val acceptedHex = "cccccccccccccccccccccccccccccccc"
    private val leftHex = "dddddddddddddddddddddddddddddddd"

    @Test
    fun pendingInviteRequiresSelfToRemainInvited() {
        val invitedMembers = listOf(
            member(selfHex, MemberStatus.INVITED),
            member(invitedHex, MemberStatus.INVITED),
        )
        val acceptedMembers = listOf(
            member(selfHex, MemberStatus.ACCEPTED),
            member(invitedHex, MemberStatus.INVITED),
        )

        assertTrue(GroupMemberStatuses.isPendingInvite(invitedMembers, selfHex))
        assertFalse(GroupMemberStatuses.isPendingInvite(acceptedMembers, selfHex))
    }

    @Test
    fun acceptedTargetsExcludeSelfAndNonAcceptedMembers() {
        val members = listOf(
            member(selfHex, MemberStatus.ACCEPTED),
            member(invitedHex, MemberStatus.INVITED),
            member(acceptedHex, MemberStatus.ACCEPTED),
            member(leftHex, MemberStatus.LEFT),
            member("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", MemberStatus.DECLINED),
        )

        assertEquals(
            listOf(acceptedHex),
            GroupMemberStatuses.acceptedMemberHexes(members, selfHex),
        )
    }

    @Test
    fun labelsCoverIosMemberStatuses() {
        assertEquals("Invited", MemberStatus.displayLabel(MemberStatus.INVITED))
        assertEquals("Accepted", MemberStatus.displayLabel(MemberStatus.ACCEPTED))
        assertEquals("Left", MemberStatus.displayLabel(MemberStatus.LEFT))
        assertEquals("Declined", MemberStatus.displayLabel(MemberStatus.DECLINED))
    }

    private fun member(destHashHex: String, inviteStatus: String): GroupMemberEntity {
        return GroupMemberEntity(
            chatId = "group_test",
            destHashHex = destHashHex,
            displayName = destHashHex.take(8),
            inviteStatus = inviteStatus,
        )
    }
}