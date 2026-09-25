package com.newendian.retichat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ordering rules of ChatRepository's 1:1 send path that need the native
 * library to run, so they are checked in the source (as
 * ConnectionStateManagerTest does for its own).
 */
class SendOrderingContractTest {
    private val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
    private val stackRuntime = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()

    /** Class members start at four spaces: a KDoc, a comment, or a declaration. */
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var )")

    /** The first `fun [name](`, up to the member after it. */
    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun theRouterStartsThePropagatedCopyNotAnAppTimer() {
        assertFalse(repo.contains("schedulePropagationFallback"))
        assertFalse(repo.contains("fallbackDelayMs"))
        assertTrue(body(repo, "onMessageState").contains("propagationFallbacks.onState(hashHex, state)"))
    }

    @Test
    fun routerReportsNeverCallBackIntoTheRouter() {
        // The router reports 0x10 and FAILED holding its lock and the
        // message's: a message call from that thread deadlocks.
        val onState = body(repo, "onMessageState")
        assertFalse(onState.contains("RetichatBridge.message"))
        assertFalse(onState.contains("sendPropagatedCopy("))
        assertTrue(body(repo, "startPropagatedCopy").contains("scope.launch(Dispatchers.IO)"))
    }

    @Test
    fun queuedMessagesWaitForTheEndOfInitialization() {
        assertFalse(
            "configure() runs mid-bootstrap, before the message-state callback",
            body(repo, "configure").contains("flushPendingMessages"),
        )
        val ready = stackRuntime.indexOf("isReady = ok")
        val flush = stackRuntime.indexOf("if (ok) app.repository.onStackReady()")
        assertTrue(ready >= 0 && flush > ready)
        assertTrue(body(repo, "onStackReady").contains("flushPendingMessages()"))
    }

    @Test
    fun sendsAndTheFlushGateOnTheReadySignal() {
        assertTrue(body(repo, "stackReady").contains("StackRuntime.isReady"))
        assertTrue(body(repo, "sendDirectMessage").contains("if (!stackReady())"))
        assertTrue(body(repo, "flushPendingMessages").contains("!stackReady()"))
    }

    @Test
    fun aSendThatQueuedLooksAtTheQueueAgain() {
        // Check after enqueue: the ready flush may have read the queue before
        // this row became OUTBOUND.
        val send = body(repo, "sendDirectMessage")
        val queued = send.indexOf("updateState(localId, RetichatBridge.MessageState.OUTBOUND)")
        val recheck = send.indexOf("if (StackRuntime.isReady) flushPendingMessages()")
        assertTrue(queued >= 0 && recheck > queued)
    }

    @Test
    fun aDirectSendIsFollowedForItsFallback() {
        // Without these the router's 0x10 and FAILED are only held, never
        // acted on, and nothing is propagated: the reported bug.
        val dispatch = body(repo, "dispatch")
        val sent = dispatch.indexOf("RetichatBridge.messageSendViaAppLinks(msgHandle)")
        val track = dispatch.indexOf("propagationFallbacks.track(")
        assertTrue(sent >= 0 && track > sent)
        val poll = body(repo, "pollMessageState")
        assertTrue(poll.contains("if (directHashHex != null && isFailureState(newState))"))
        assertTrue(poll.contains("propagationFallbacks.onPolledState(directHashHex, newState)"))
        assertTrue(body(repo, "dispatch").contains("pollMessageState(localId, msgHandle, directHashHex = hashHex)"))
    }

    @Test
    fun successIsStickyInOneStatement() {
        // A read then a write lets a DELIVERED land between them and be lost.
        assertTrue(body(repo, "failUnlessSucceeded").contains("updateStateUnlessSucceeded("))
        assertTrue(body(repo, "sendPropagatedCopy").contains("takeOverUnlessSucceeded("))
        assertTrue(body(repo, "propagatedCopyFailed").contains("takeOverUnlessSucceeded("))
    }

    @Test
    fun theFlushSharesTheSendPath() {
        val flush = body(repo, "flushPendingMessages")
        assertTrue(flush.contains("dispatch("))
        assertTrue(flush.contains("attachments = queuedAttachments(msg.id)"))
        assertFalse(flush.contains("RetichatBridge.messageCreate"))
        assertTrue(body(repo, "dispatch").contains("claimPendingOutbound"))
    }

    @Test
    fun theFallbackIsACloneOfTheDirectMessage() {
        // Same LXMF hash as the DIRECT message, so a recipient given both
        // drops the second. A new message had a new timestamp, so a new hash,
        // and was shown twice.
        val start = body(repo, "startPropagatedCopy")
        assertTrue(start.contains("RetichatBridge.messageClonePropagated(send.directHandle)"))
        assertFalse(start.contains("messageCreate"))
        assertFalse("the clone carries the attachments", start.contains("queuedAttachments"))
        assertFalse(start.contains("messageAddAttachment"))
        assertFalse(body(repo, "sendPropagatedCopy").contains("RetichatBridge.messageCreate"))
        assertTrue(body(repo, "dispatch").contains("PropagationFallbacks.Send(localId, directHandle = msgHandle)"))
    }

    @Test
    fun theCopysFailureGoesBackToTheDirectAttemptInFlight() {
        val copy = body(repo, "sendPropagatedCopy")
        assertTrue(
            "the copy's poll is given the DIRECT handle",
            Regex("pollMessageState\\(\\s*localId, propHandle, initialDeadlineMs = 600_000L,\\s*directHandle = directHandle,")
                .containsMatchIn(copy),
        )
        val poll = body(repo, "pollMessageState")
        assertTrue(poll.contains("if (directHandle != 0L && isFailureState(newState))"))
        assertTrue(poll.contains("propagatedCopyFailed(localId, directHandle, newState)"))
        val failed = body(repo, "propagatedCopyFailed")
        // The message's failure is written only once the DIRECT state says it
        // has ended: after that check, once, and before any hand-back.
        val check = failed.indexOf("if (!PropagationFallbacks.directKeepsTheBubble(directState))")
        val fail = failed.indexOf("messageDao.updateStateUnlessSucceeded(localId, copyState)")
        val handBack = failed.indexOf("takeOverUnlessSucceeded(localId, directHandle, directState)")
        assertTrue(check >= 0 && fail > check && handBack > fail)
        assertEquals(1, Regex("updateStateUnlessSucceeded\\(").findAll(failed).count())
        assertTrue("its failure is written", failed.indexOf("pollMessageState(localId, directHandle)") > handBack)
    }

    @Test
    fun aCopyThatDidNotGoOutFailsOnlyWithNoDirectAttemptLeft() {
        val copy = body(repo, "sendPropagatedCopy")
        // Stack not ready, no node, no message, send refused, failed at once, threw.
        assertEquals(6, Regex("propagatedCopyNotSent\\(localId, directHandle\\)").findAll(copy).count())
        assertEquals("only the copy's poll failing", 1, Regex("failUnlessSucceeded\\(").findAll(copy).count())
        val notSent = body(repo, "propagatedCopyNotSent")
        // Nothing may fail the message before the DIRECT attempt is asked.
        val keeps = notSent.indexOf("PropagationFallbacks.directKeepsTheBubble(")
        assertTrue(keeps >= 0 && notSent.indexOf("failUnlessSucceeded(") > keeps)
        assertEquals(1, Regex("failUnlessSucceeded\\(").findAll(notSent).count())
        assertFalse("no write before the DIRECT attempt is asked", notSent.substring(0, keeps).contains("messageDao."))
    }

    @Test
    fun aDirectFailureWithNoCopyHoldingTheBubbleIsWritten() {
        // Otherwise the bubble spins on once the copy has started and ended.
        val poll = body(repo, "pollMessageState")
        assertTrue(poll.contains("} else if (messageDao.updateStateOnHandleUnlessSucceeded(localId, msgHandle, newState) == 1) {"))
        // Only while the row still points at this DIRECT handle: once a copy
        // has taken it over, the DIRECT failure is not the bubble's outcome.
        val dao = File("src/main/kotlin/com/newendian/retichat/data/db/dao/MessageDao.kt").readText()
        assertTrue(dao.contains("WHERE id = :id AND nativeHandle = :handle AND state NOT IN (0x04, 0x08)"))
    }
}
