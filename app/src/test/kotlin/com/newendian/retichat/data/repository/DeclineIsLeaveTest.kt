package com.newendian.retichat.data.repository

import com.newendian.retichat.GroupMemberStatuses
import com.newendian.retichat.MemberStatus
import com.newendian.retichat.data.db.entity.GroupMemberEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A decline is the user's leave (James, 2026-10-02: "Make the decline message
 * the same as the leave message"), and Android's leave is the web's and
 * iOS's: GROUP_ACTION `leave` with GROUP_SENDER the user and no content (audit
 * L5: until 2026-10-05 it was a plain post "left the group" with no action,
 * which no other client records as a leave, and a decline sent nothing).
 * Both go to every listed member that has not left, and both are final.
 */
class DeclineIsLeaveTest {
    private val self = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val inviter = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val member = "cccccccccccccccccccccccccccccccc"
    private val stranger = "dddddddddddddddddddddddddddddddd"

    private fun m(hex: String, status: String) = GroupMemberEntity(chatId = "group_x", destHashHex = hex, inviteStatus = status)

    @Test
    fun theUsersLeaveGoesToEveryListedMemberThatHasNotLeft() {
        val members = listOf(
            m(self, MemberStatus.INVITED), m(inviter, MemberStatus.ACCEPTED),
            m(member, MemberStatus.INVITED), m(stranger, MemberStatus.LEFT),
        )
        assertEquals(listOf(inviter, member), GroupMemberStatuses.leaveTargets(members, self))
    }

    // ── Wiring (needs the native library to run, so checked in the source) ──

    private val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
    private val gcm = File("src/main/kotlin/com/newendian/retichat/service/GroupChatManager.kt").readText()
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var )")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun aDeclineIsTheUsersLeaveAndBothAreFinal() {
        assertTrue(body(repo, "declineGroupInvite").contains("quitGroup(chatId,"))
        assertTrue(body(repo, "leaveGroupChat").contains("quitGroup(chatId,"))
        val quit = body(repo, "quitGroup")
        assertTrue(quit.contains("GroupMemberStatuses.leaveTargets("))
        assertTrue(quit.contains("UserPreferences.closeGroup(appContext, groupIdHex)"))
        assertTrue(quit.contains("groupChatManager?.sendLeave(groupIdHex, targets)"))
        assertTrue(quit.indexOf("sendLeave(") < quit.indexOf("deleteChatLocal(chat)"))
        // The leave is the web's and iOS's: GROUP_ACTION leave, no content (audit L5).
        // and carries the user's own key, as an accept does (GroupEnvelopes: GROUP_ID, the
        // action, GROUP_SENDER the user, GROUP_MEMBER_KEYS the user's own key).
        val leave = body(gcm, "sendLeave")
        assertTrue(leave.contains("send(target, \"\")"))
        assertTrue(leave.contains("GroupEnvelopes.write(handle, GroupEnvelopes.leave(groupId, selfHex, ownKey))"))
        val envelope = File("src/main/kotlin/com/newendian/retichat/service/GroupEnvelopes.kt").readText()
        assertTrue(envelope.contains("control(groupId, GroupChatManager.Action.LEAVE, selfHex, ownKey)"))
        // Nothing sends the old plain "left the group" post.
        assertFalse(repo.contains("content = \"left the group\",\n                    method"))
    }

    @Test
    fun theDeclineAsksBeforeItIsFinal() {
        val screen = File("src/main/kotlin/com/newendian/retichat/ui/conversation/ConversationScreen.kt").readText()
        assertTrue(screen.contains("onClick = { showDeclineConfirm = true }"))
        assertTrue(screen.contains("You won't be able to join this group later."))
        assertFalse(screen.contains("onClick = { viewModel?.declineInvite(); onBack() }"))
        val sheet = File("src/main/kotlin/com/newendian/retichat/ui/conversation/ChatInfoSheet.kt").readText()
        assertTrue(sheet.contains("you won't be able to rejoin this group."))
    }

    /** James, 2026-10-05: deleting a group conversation means leaving it. */
    @Test
    fun deletingAGroupConversationIsLeavingIt() {
        val delete = body(repo, "deleteChat")
        val leave = delete.indexOf("if (chat.isGroup) return quitGroup(chatId, \"deleted\")")
        assertTrue(leave >= 0)
        assertTrue("a group never reaches the local-only delete", leave < delete.indexOf("deleteChatLocal(chat)"))
        val sheet = File("src/main/kotlin/com/newendian/retichat/ui/conversation/ChatInfoSheet.kt").readText()
        assertTrue(sheet.contains("if (isGroup) \"Delete and leave this group?\" else \"Delete this conversation?\""))
        assertTrue(sheet.contains("\"Deleting a group conversation leaves the group: the members are told you left, \""))
        // Leaving says the conversation goes too.
        assertTrue(sheet.contains("\"The members are told you left, and this conversation is deleted."))
    }
}
