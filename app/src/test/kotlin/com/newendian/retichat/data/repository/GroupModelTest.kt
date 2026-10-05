package com.newendian.retichat.data.repository

import com.newendian.retichat.ClosedGroups
import com.newendian.retichat.GroupMemberStatuses
import com.newendian.retichat.MemberStatus
import com.newendian.retichat.data.db.entity.GroupMemberEntity
import com.newendian.retichat.data.repository.DeliveryPolicy.Held
import com.newendian.retichat.names.Signature
import com.newendian.retichat.service.GroupChatManager.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * James's group model and trust rule (DISPLAY_NAMES.md §7, 2026-10-01): the
 * members are the creator's list, fixed; accept, reject and leave are each
 * member's own and final; an invite counts only from a source the privacy
 * filter allows, allows nobody until the user accepts, and is ignored for a
 * group the user rejected or left; relays only for an accepted member of a
 * joined group; a stranger's post is its own.
 */
class GroupModelTest {
    private val self = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val inviter = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val member = "cccccccccccccccccccccccccccccccc"
    private val stranger = "dddddddddddddddddddddddddddddddd"

    private fun rule(
        action: String?,
        held: Held = Held.JOINED,
        sourceStatus: String? = MemberStatus.ACCEPTED,
        sourceAllowed: Boolean = true,
        namesOther: Boolean = false,
        closed: Boolean = false,
        signature: Int = Signature.VALIDATED,
    ) = DeliveryPolicy.groupMessage(action, sourceAllowed, held, sourceStatus, namesOther, closed, signature)

    // ── Invites ──────────────────────────────────────────────────────

    @Test
    fun anInviteCountsOnlyFromAnAllowedSourceAndNeverForAGroupTheUserRejectedOrLeft() {
        assertTrue(rule(Action.INVITE, held = Held.NONE, sourceStatus = null))
        assertFalse(rule(Action.INVITE, held = Held.NONE, sourceStatus = null, sourceAllowed = false))
        // "Once the group is rejected/left, that person cannot rejoin."
        assertFalse(rule(Action.INVITE, held = Held.NONE, sourceStatus = null, closed = true))
        // A forged source (its key is here and did not sign it) invites nobody.
        assertFalse(rule(Action.INVITE, held = Held.NONE, sourceStatus = null, signature = Signature.INVALID))
    }

    // ── Accept and leave: the source's own ───────────────────────────

    @Test
    fun anAcceptOrLeaveCountsOnlyFromACurrentMemberAboutItself() {
        for (action in listOf(Action.ACCEPT, Action.LEAVE)) {
            for (held in listOf(Held.PENDING, Held.JOINED)) {
                assertTrue(rule(action, held = held, sourceStatus = MemberStatus.INVITED))
                assertTrue(rule(action, held = held, sourceStatus = MemberStatus.ACCEPTED))
                // Not on the list: never a member.
                assertFalse(rule(action, held = held, sourceStatus = null))
                // Left (a decline arrives as a leave): final.
                assertFalse(rule(action, held = held, sourceStatus = MemberStatus.LEFT))
                // Naming someone else (GROUP_SENDER not the source's own).
                assertFalse(rule(action, held = held, namesOther = true))
                assertFalse(rule(action, held = held, signature = Signature.INVALID))
            }
            assertFalse(rule(action, held = Held.NONE, sourceStatus = null))
        }
    }

    @Test
    fun aMessageNamesSomeoneElseUnlessItsSenderIsThePacketSource() {
        assertFalse(DeliveryPolicy.namesOther(null, member))
        assertFalse(DeliveryPolicy.namesOther(member, member))
        assertTrue(DeliveryPolicy.namesOther(stranger, member))
        // Not 32 lowercase hex: it cannot be the source's own.
        assertTrue(DeliveryPolicy.namesOther(member.uppercase(), member))
        assertTrue(DeliveryPolicy.namesOther(member.take(31), member))
    }

    @Test
    fun anAcceptOrLeaveMovesOnlyAListedMemberAndALeaveIsFinal() {
        assertEquals(MemberStatus.ACCEPTED, GroupMemberStatuses.statusChange(MemberStatus.INVITED, Action.ACCEPT))
        assertNull(GroupMemberStatuses.statusChange(MemberStatus.ACCEPTED, Action.ACCEPT))
        assertEquals(MemberStatus.LEFT, GroupMemberStatuses.statusChange(MemberStatus.INVITED, Action.LEAVE))
        assertEquals(MemberStatus.LEFT, GroupMemberStatuses.statusChange(MemberStatus.ACCEPTED, Action.LEAVE))
        // A member that left never comes back; nobody is added.
        assertNull(GroupMemberStatuses.statusChange(MemberStatus.LEFT, Action.ACCEPT))
        assertNull(GroupMemberStatuses.statusChange(MemberStatus.DECLINED, Action.ACCEPT))
        assertNull(GroupMemberStatuses.statusChange(null, Action.ACCEPT))
        assertNull(GroupMemberStatuses.statusChange(null, Action.LEAVE))
    }

    // ── Relays and other actions ─────────────────────────────────────

    @Test
    fun aRelayRequestCountsOnlyFromAnAcceptedMemberOfAJoinedGroup() {
        assertTrue(rule(Action.RELAY_REQUEST))
        assertFalse(rule(Action.RELAY_REQUEST, held = Held.PENDING))
        assertFalse(rule(Action.RELAY_REQUEST, sourceStatus = MemberStatus.INVITED))
        assertFalse(rule(Action.RELAY_REQUEST, sourceStatus = MemberStatus.LEFT))
        assertFalse(rule(Action.RELAY_REQUEST, sourceStatus = null, sourceAllowed = true))
        assertFalse(rule(Action.RELAY_REQUEST, signature = Signature.SOURCE_UNKNOWN))
        // Any other action: a current member of a joined group.
        assertTrue(rule(Action.RELAY_DONE, sourceStatus = MemberStatus.INVITED))
        assertFalse(rule(Action.RELAY_DONE, held = Held.PENDING))
        assertFalse(rule(Action.RELAY_DONE, sourceStatus = null))
    }

    // ── Plain posts ──────────────────────────────────────────────────

    @Test
    fun aPlainPostIsKeptForAHeldGroupWhoeverSentIt() {
        assertTrue(rule(null, held = Held.JOINED, sourceStatus = null, sourceAllowed = false))
        assertTrue(rule(null, held = Held.PENDING, sourceStatus = null, sourceAllowed = false))
        assertFalse(rule(null, held = Held.NONE, sourceStatus = null))
    }

    @Test
    fun aStrangersPostIsItsOwnNeverTheMemberItNames() {
        fun author(src: String, held: Held, status: String?, listed: Boolean = true, sig: Int = Signature.VALIDATED) =
            DeliveryPolicy.groupAuthor(member, src, held, status, listed, sig)
        // A current member of a joined group relays a listed member's post.
        assertEquals(member, author(inviter, Held.JOINED, MemberStatus.ACCEPTED))
        // Anyone else speaks for itself.
        assertEquals(stranger, author(stranger, Held.JOINED, null))
        assertEquals(inviter, author(inviter, Held.PENDING, MemberStatus.ACCEPTED))
        assertEquals(inviter, author(inviter, Held.JOINED, MemberStatus.LEFT))
        assertEquals(inviter, author(inviter, Held.JOINED, MemberStatus.ACCEPTED, listed = false))
        assertEquals(inviter, author(inviter, Held.JOINED, MemberStatus.ACCEPTED, sig = Signature.SOURCE_UNKNOWN))
        assertEquals(inviter, DeliveryPolicy.groupAuthor(null, inviter, Held.JOINED, MemberStatus.ACCEPTED, false, Signature.VALIDATED))
    }

    // ── Hashes and lists ─────────────────────────────────────────────

    @Test
    fun anInvitesMembersAreIts32LowercaseHexHashesOnly() {
        assertEquals(listOf(self, member), DeliveryPolicy.members("$self, $member,${member.uppercase()},xyz,,$self,${member}0"))
        assertEquals(emptyList<String>(), DeliveryPolicy.members(null))
        assertNull(DeliveryPolicy.hash("0123456789ABCDEF0123456789abcdef"))
        assertEquals(member, DeliveryPolicy.hash(" $member "))
    }

    private fun m(hex: String, status: String) = GroupMemberEntity(chatId = "group_x", destHashHex = hex, inviteStatus = status)

    @Test
    fun theUsersStandingIsJoinedOnlyOnceItsOwnRowIsAccepted() {
        assertEquals(Held.NONE, GroupMemberStatuses.held(false, emptyList(), self))
        assertEquals(Held.PENDING, GroupMemberStatuses.held(true, listOf(m(self, MemberStatus.INVITED), m(inviter, MemberStatus.ACCEPTED)), self))
        assertEquals(Held.JOINED, GroupMemberStatuses.held(true, listOf(m(self, MemberStatus.ACCEPTED)), self))
        assertEquals(Held.PENDING, GroupMemberStatuses.held(true, listOf(m(inviter, MemberStatus.ACCEPTED)), self))
        assertEquals(MemberStatus.LEFT, GroupMemberStatuses.statusOf(listOf(m(member, MemberStatus.ACCEPTED), m(member, MemberStatus.LEFT)), member))
        assertNull(GroupMemberStatuses.statusOf(listOf(m(member, MemberStatus.ACCEPTED)), stranger))
    }

    @Test
    fun aRejectedOrLeftGroupIsRecordedNewestLastAndBounded() {
        assertEquals(listOf("g1"), ClosedGroups.record(emptyList(), "g1"))
        assertEquals(listOf("g2", "g1"), ClosedGroups.record(listOf("g1", "g2"), "g1"))
        val full = (1..ClosedGroups.LIMIT).map { "g$it" }
        val next = ClosedGroups.record(full, "new")
        assertEquals(ClosedGroups.LIMIT, next.size)
        assertEquals("g2", next.first())
        assertEquals("new", next.last())
    }

    // ── Wiring (needs the native library to run, so checked in the source) ──

    private val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var )")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun theRuleAsksAboutThePacketSourceNeverGroupSender() {
        val received = body(repo, "onMessageReceived")
        assertTrue(received.contains("groupStanding(groupId, srcHex)"))
        assertTrue(received.contains("namesOther = DeliveryPolicy.namesOther(fields.group(GroupEntry.SENDER), srcHex)"))
        assertTrue(received.contains("closed = here.closed"))
        assertTrue(body(repo, "groupStanding").contains("UserPreferences.isGroupClosed(appContext, groupId)"))
        // No member's status is changed for GROUP_SENDER.
        assertFalse(repo.contains("groupSender ?: srcHex"))
        assertTrue(body(repo, "handleGroupMessage").contains("messageDao.setGroupMemberStatus(chat.id, srcHex, next)"))
    }

    @Test
    fun anInviteAllowsNobodyAndAHeldGroupKeepsItsList() {
        val invite = body(repo, "handleGroupInvite")
        assertFalse(invite.contains("ensureAllowlistedContact("))
        assertFalse(invite.contains("setAllowlisted("))
        assertTrue(invite.contains("ensureContact(memberHash)"))
        val held = invite.indexOf("if (standing.chat != null)")
        val create = invite.indexOf("chatDao.upsert(")
        assertTrue("a held group returns before anything is created", held in 0 until create)
        // The user's accept (and the creation) allows the members.
        assertTrue(body(repo, "acceptGroupInvite").contains("ensureAllowlistedContact(it)"))
        assertTrue(body(repo, "createGroupChat").contains("ensureAllowlistedContact(hexHash)"))
        // A member's own accept allows it only in a joined group.
        assertTrue(body(repo, "handleGroupMessage")
            .contains("if (accepted && standing.held == DeliveryPolicy.Held.JOINED && srcHex != selfHex())"))
    }
}
