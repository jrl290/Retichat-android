package com.newendian.retichat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring of member statuses and keys (RFed-spec Group.md, 2026-10-06) that
 * needs the native library or Android to run, so it is checked in the source, as
 * GroupModelTest and DeliveryPolicyTest check the rest of the group model: the
 * order in which the repository hands a message to the engine, where each
 * decision is made, and that nothing here waits or retries. The decisions
 * themselves run in [GroupStatusesTest] and [GroupStatusVectorsTest].
 */
class GroupStatusesWiringTest {
    private val main = File("src/main/kotlin/com/newendian/retichat")
    private val sources: Map<String, String> = main.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(main).path to it.readText() }
    private val repo = sources.getValue("data/repository/ChatRepository.kt")
    private val engine = sources.getValue("data/repository/GroupStatuses.kt")
    private val gcm = sources.getValue("service/GroupChatManager.kt")
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var )")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private fun count(pattern: String, source: String) = Regex(pattern).findAll(source).count()

    @Test
    fun theCarriedKeyIsRememberedBeforeAnythingLooksForItAndOnlyAfterTheGroupRuleKeptTheMessage() {
        val received = body(repo, "onMessageReceived")
        assertTrue(received.indexOf("DeliveryPolicy.groupMessage(") in 0 until received.indexOf("handleGroupMessage("))
        val handle = body(repo, "handleGroupMessage")
        val kept = handle.indexOf("groupStatuses.onKept(srcHex, groupAction, signature, senderKey)")
        assertTrue(kept >= 0)
        assertTrue("before the invite is taken", kept < handle.indexOf("GroupChatManager.Action.INVITE"))
        assertTrue("before the accept or leave is counted", kept < handle.indexOf("takeDirect("))
        // In the engine: the key first, then what was held for the source's key.
        val onKept = body(engine, "onKept")
        assertTrue(onKept.indexOf("remember(src, senderKey)") in 0 until onKept.indexOf("decideHeldLocked(src)"))
        assertTrue(onKept.contains("if (signature == Signature.VALIDATED && store.heldFrom(src) > 0)"))
        // The router's callback hands over both: the key a message carries and the message as received.
        assertTrue(received.contains("handleGroupMessage(msgId, srcHex, content, timestamp, fields, group, signature, senderKey, packed)"))
    }

    @Test
    fun everyAcceptAndLeaveThatCountsComesThroughOnePlaceWhichKeepsTheCreatorsCopyBeforeTheMemberMoves() {
        // takeChange is the one decision (direct message, element, held entry); applyChange the one change.
        assertEquals(3, count("takeChange\\(", engine))      // its definition, takeDirect, takeElement
        assertEquals(3, count("applyChange\\(", engine))     // its definition, takeChange, decideHeldLocked
        assertTrue(body(engine, "takeDirect").contains("takeChange("))
        assertTrue(body(engine, "takeElement").contains("takeChange("))
        assertTrue(body(engine, "decideHeldLocked").contains("applyChange("))
        val apply = body(engine, "applyChange")
        assertEquals(1, count("store\\.setMemberStatus\\(", engine))
        assertEquals(2, count("keepCopy\\(", engine))        // its definition and applyChange
        assertTrue(apply.indexOf("keepCopy(") in 0 until apply.indexOf("store.setMemberStatus("))
        assertTrue("only the creator keeps copies", apply.contains("chat.creatorHex == self"))
        // Every element is checked by the same group rule as a message arriving directly.
        assertTrue(body(engine, "takeElement").contains("DeliveryPolicy.groupMessage("))
        // Forwarded elements teach no display names: the engine never touches a name.
        assertFalse(engine.contains("acceptMessageName"))
        assertFalse(engine.contains("displayName"))
    }

    @Test
    fun theCreatorAnswersFromTheDirectHandlerAloneAndOnlyAnAcceptThatCountedAndCarriedItsKey() {
        assertEquals(2, count("answerAccept\\(", engine))    // its definition and takeDirect
        val direct = body(engine, "takeDirect")
        assertTrue(direct.contains("counted && arrival.action == GroupChatManager.Action.ACCEPT && arrival.senderKey != null"))
        assertTrue(direct.contains("answerAccept(arrival.chat, arrival.src)"))
        val answer = body(engine, "answerAccept")
        assertTrue(answer.contains("if (chat.creatorHex != self) return"))
        assertTrue("never its own copy nor the recipient's", answer.contains("it.memberHex != self && it.memberHex != memberHex"))
        assertTrue(answer.contains(".sortedBy { it.memberHex }"))
        // The repository sends it through the one group send path, once: nothing sends a status twice.
        assertTrue(repo.contains("manager.sendStatus(groupId, member, statuses)"))
        assertEquals(1, count("sendStatus\\(", repo))
    }

    @Test
    fun everyAcceptAndLeaveTheAppSendsIsBuiltInOneEnvelopeEachAndCarriesTheUsersOwnKeyReadFromTheIdentity() {
        assertEquals(1, count("GroupEnvelopes\\.accept\\(", gcm))
        assertEquals(1, count("GroupEnvelopes\\.leave\\(", gcm))
        assertTrue(body(gcm, "sendAccept").contains("GroupEnvelopes.accept(groupId, selfHex, ownKey)"))
        assertTrue(body(gcm, "sendAccept").contains("val ownKey = ownKeyEntry()"))
        assertTrue(body(gcm, "sendLeave").contains("val ownKey = ownKeyEntry()"))
        // From the identity, not the router: a leave sent with no connection carries it too.
        val own = body(gcm, "ownKeyEntry")
        assertTrue(own.contains("RetichatBridge.identityPublicKey(identityHandle)"))
        assertFalse(own.contains("routerHandle"))
        // No other path writes an accept or a leave.
        assertFalse(gcm.contains("GroupEntry.ACTION, Action.ACCEPT"))
        assertFalse(gcm.contains("GroupEntry.ACTION, Action.LEAVE"))
        // The status goes the way every group envelope goes (DIRECT first, one propagated copy as the fallback), and is not sent when it cannot be built.
        val status = body(gcm, "sendStatus")
        assertTrue(status.contains("sendChecked(memberHex, \"\")"))
        assertTrue(status.contains("GroupEnvelopes.write(handle, GroupEnvelopes.status(groupId, selfHex))"))
        assertTrue(status.contains("GroupFields.setStatuses(handle, statuses)"))
        assertTrue(body(gcm, "send").contains("sendChecked("))
        assertTrue(body(gcm, "sendChecked").contains("RetichatBridge.messageSendViaAppLinks(handle)"))
        // Key 10 is written by GroupFields alone.
        val sites = sources.filter { Regex("messageSetRetichatStatuses\\(").containsMatchIn(it.value) }.keys
        assertEquals(setOf("bridge/RetichatBridge.kt", "service/GroupFields.kt"), sites)
    }

    @Test
    fun aCreatorIsRecordedWhenAGroupIsMadeOrInvitedIntoAndNeverChangedByALaterInvite() {
        assertTrue(body(repo, "createGroupChat").contains("creatorHex = selfHex(),"))
        val invite = body(repo, "handleGroupInvite")
        assertTrue(invite.contains("creatorHex = srcHex,"))
        // A held group returns before any row is written: its first invite's source stays its creator.
        assertTrue(invite.indexOf("if (standing.chat != null) {") in 0 until invite.indexOf("creatorHex = srcHex,"))
        // The rule is told whether the source is the creator, from the group's record.
        assertTrue(body(repo, "groupStanding").contains("sourceIsCreator = chat?.creatorHex != null && chat.creatorHex == srcHex"))
        assertTrue(body(repo, "onMessageReceived").contains("sourceIsCreator = here.sourceIsCreator,"))
    }

    @Test
    fun whatWasHeldIsDecidedWhenAKeyArrivesAnInviteTheUsersAcceptAnAnnounceAKeyCarriedAVerifiedMessage() {
        assertTrue(body(repo, "handleGroupInvite").contains("groupStatuses.decideHeld()"))
        val accept = body(repo, "acceptGroupInvite")
        assertTrue("before the group fans out", accept.indexOf("groupStatuses.decideHeld()") in 0 until accept.indexOf("groupChatManager?.sendAccept("))
        assertTrue(accept.indexOf("groupStatuses.decideHeld()") < accept.indexOf("messageDao.setGroupMemberStatus(chatId, selfHex, MemberStatus.ACCEPTED)"))
        assertTrue(body(repo, "onAnnounceReceived").contains("groupStatuses.decideHeld(hex)"))
        assertTrue(body(engine, "onKept").contains("decideHeldLocked(src)"))
    }

    @Test
    fun theCopiesAndTheHeldEntriesGoWithTheGroup() {
        val delete = body(repo, "deleteChatLocal")
        assertTrue(delete.contains("groupStatusDao.deleteCopies(chat.id)"))
        assertTrue(delete.contains("groupStatusDao.deleteHeld(chat.id)"))
        assertTrue(delete.indexOf("groupStatusDao.deleteCopies(chat.id)") < delete.indexOf("chatDao.delete(chat)"))
        // One Room database, one DAO, handed to the repository by the application.
        assertTrue(File("src/main/kotlin/com/newendian/retichat/RetichatApp.kt").readText().contains("groupStatusDao = database.groupStatusDao(),"))
    }

    @Test
    fun theEngineIsOneDeviceOneLockOneEventAtATimeSoALeaveStaysFinalHoweverTwoMessagesRace() {
        for (entry in listOf("onKept", "decideHeld", "takeDirect", "takeStatuses")) {
            assertTrue(entry, body(engine, entry).contains("lock.withLock"))
        }
        // The locked work never takes the lock again (a Mutex is not reentrant): only private helpers run under it.
        for (helper in listOf("takeChange", "applyChange", "takeElement", "keepCopy", "answerAccept", "hold", "decideHeldLocked", "remember")) {
            assertFalse(helper, body(engine, helper).contains("lock.withLock"))
        }
    }

    @Test
    fun nothingHereRunsATimerOrARetryDesignPrinciples1And3() {
        val forbidden = Regex("\\bdelay\\(|\\bTimer\\b|schedule\\(|Thread\\.sleep|while \\(true\\)|\\.retry|withTimeout|\\bwithTimeoutOrNull")
        for (path in listOf(
            "data/repository/GroupStatuses.kt", "data/repository/RoomGroupStatusStore.kt", "service/GroupEnvelopes.kt",
            "bridge/GroupNative.kt", "bridge/UnpackedGroupMessage.kt", "data/db/dao/GroupStatusDao.kt",
        )) {
            assertFalse(path, forbidden.containsMatchIn(sources.getValue(path)))
        }
        val glue = File("../rust/retichat-jni/src/group_status.rs").readText().substringBefore("#[cfg(test)]")
        assertFalse(Regex("thread::|sleep|Instant|SystemTime|retry").containsMatchIn(glue))
        // The status goes once: no other call of the sender, and no loop around the one there is.
        assertFalse(body(gcm, "sendStatus").contains("repeat("))
    }
}
