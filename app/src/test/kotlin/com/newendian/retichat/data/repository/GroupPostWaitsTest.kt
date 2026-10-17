package com.newendian.retichat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Start-up (James's UI rulings, 2026-10-11; GROUPS-DISTRO-DESIGN.md §8.7): a
 * group post waits exactly as a DM does. It is stored at once with the DM's
 * pending clock, goes through the DM queue when the start finishes, as the
 * group's own address resolved then and to the members accepted then, or is
 * shown failed, the DM failed state, when it can no longer be signed then. This
 * is ordering (DESIGN_PRINCIPLES §5), not a retry. Until 2026-10-11 the
 * composer was greyed at start-up, and a group row the queue found was failed
 * ("Group messages are fan-out ... mark failed so the user can resend").
 *
 * The repository is Android (Room, the JNI): its source is what is pinned; the
 * decision itself is [GroupSelves.post], and the queue's SQL is QueuedPostsSqlTest's.
 */
class GroupPostWaitsTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val repo = src("data/repository/ChatRepository.kt")
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    /** Each of [parts] in [text], in this order. */
    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (part in parts) {
            val next = text.indexOf(part, at + 1)
            assertTrue("\"$part\" after the one before it", next > at)
            at = next
        }
    }

    @Test
    fun aPostWaitsUntilTheStartFinishesIsSentOnceItHasAndOnlyAFormerDistroRefusesIt() {
        val d = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"
        val device = "0123456789abcdef0123456789abcdef"
        for (started in listOf(false, true)) {
            // The start has not finished: the device address is not known yet, so nothing is.
            assertEquals(GroupSelves.Post.WAIT, GroupSelves.post(GroupSelves.signer(d, "", d), started))
            assertEquals(GroupSelves.Post.WAIT, GroupSelves.post(GroupSelves.Signer.NOT_READY, started))
            // A former distro: refused, started or not; the bar is shown, never a wait.
            assertEquals(GroupSelves.Post.REFUSE, GroupSelves.post(GroupSelves.signer(d, device, null), started))
        }
        for (signer in listOf(GroupSelves.signer(d, device, d), GroupSelves.signer(device, device, null), GroupSelves.signer(null, device, d))) {
            // Configured but not yet ready (the DM's own test): it waits as a DM does.
            assertEquals(signer.toString(), GroupSelves.Post.WAIT, GroupSelves.post(signer, started = false))
            assertEquals(signer.toString(), GroupSelves.Post.SEND, GroupSelves.post(signer, started = true))
        }
    }

    @Test
    fun atStartUpThePostIsHeldBeforeTheGroupSignerIsAskedAndOnlyAFormerDistroRefusesIt() {
        val send = body(repo, "sendGroupMessage")
        inOrder(
            send,
            "when (GroupSelves.post(signerKind(chat), stackReady())) {",
            "GroupSelves.Post.WAIT -> {",
            "holdGroupPost(chat, content, attachments)",
            "return null",
            "val signer = groupSigner(chat) ?: run {",
            "return GroupPostRefused(",
            "messageDao.upsert(",
        )
    }

    @Test
    fun theHeldPostIsAQueuedRowWithItsAttachmentsThatWaitsForTheStartAsADmDoes() {
        val hold = body(repo, "holdGroupPost")
        // Stored with its attachments before it is queued, so the queue never takes it without them.
        inOrder(
            hold,
            "state = RetichatBridge.MessageState.GENERATING,",
            "saveOutboundAttachments(groupMsgId, attachments)",
            "messageDao.updateState(groupMsgId, RetichatBridge.MessageState.OUTBOUND)",
            "StackRuntime.waitForStart(appContext, \"sendGroupMessage\") { flushPendingMessages() }",
        )
        // The DM waits through the same.
        inOrder(
            body(repo, "sendDirectMessage"),
            "if (!stackReady()) {",
            "messageDao.updateState(localId, RetichatBridge.MessageState.OUTBOUND)",
            "StackRuntime.waitForStart(appContext, \"sendDirect\") { flushPendingMessages() }",
        )
        // GENERATING and OUTBOUND are the DM's pending clock.
        val screen = src("ui/conversation/ConversationScreen.kt")
        assertTrue(screen.contains("RetichatBridge.MessageState.GENERATING -> \"\\u23F3\""))
        assertTrue(screen.contains("RetichatBridge.MessageState.OUTBOUND   -> \"\\u23F3\""))
    }

    @Test
    fun theStartWaitIsTheDmQueuesOwn() {
        val runtime = src("service/StackRuntime.kt")
        val wait = body(runtime, "waitForStart")
        // The row is queued before this is called: a start that finished meanwhile flushed without it.
        inOrder(
            wait,
            "if (isReady) flush()",
            "if (!NetworkMonitor.isOnline.value)",
            "if (isStarting)",
            "holding(context) { ready ->",
            "if (isReady) flush()",
        )
        assertFalse("no timer", wait.contains("delay("))
    }

    @Test
    fun theQueueReleasesAGroupPostThroughTheGroupSendAsTheOwnAddressResolvedThen() {
        val flush = body(repo, "flushPendingMessages")
        assertTrue(flush.contains("releaseHeldGroupPost(chat, msg)"))
        assertFalse(flush.contains("mark failed so the user"))
        assertFalse(flush.contains("Group messages are fan-out"))
        val release = body(repo, "releaseHeldGroupPost")
        inOrder(release, "val signer = groupSigner(chat)", "messageDao.claimQueuedGroupPost(msg.id,", "fanOutGroupPost(")
        // Failed, the DM failed state, when it cannot be signed then (a former distro): nothing is sent.
        assertTrue(release.contains("if (signer == null || groupIdHex == null) RetichatBridge.MessageState.FAILED else RetichatBridge.MessageState.SENDING"))
        inOrder(release, "if (signer == null || groupIdHex == null) {", "return", "fanOutGroupPost(")
        // Taken from the queue once: a second flush finds it gone.
        val dao = src("data/db/dao/MessageDao.kt")
        assertTrue(dao.contains("UPDATE messages SET state = :state, senderHashHex = :sender WHERE id = :id AND isOutbound = 1 AND state = 0x01 AND nativeHandle = 0"))
        // The post sent at once goes through the same fan-out, its sync and the members decided there.
        // With its post id (REACTIONS-DESIGN.md §4), the queued post's kept on its row.
        assertTrue(body(repo, "sendGroupMessage").contains("fanOutGroupPost(chat, groupIdHex, groupMsgId, content, attachments, postedAt, signer, postId)"))
        assertTrue(release.contains("fanOutGroupPost(chat, groupIdHex, msg.id, msg.content, queuedAttachments(msg.id), msg.timestamp, signer, msg.postId)"))
        // The third: a Group reaction goes the way a post goes, with no row (REACTIONS-DESIGN.md §4).
        assertEquals(3, Regex(Regex.escape("fanOutGroupPost(chat, ")).findAll(repo).count())
        assertTrue(body(repo, "sendGroupReaction").contains("fanOutGroupPost(chat, groupIdHex, groupMsgId = null,"))
    }
}
