package com.newendian.retichat.data.repository

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
    }

    @Test
    fun theFlushSharesTheSendPath() {
        val flush = body(repo, "flushPendingMessages")
        assertTrue(flush.contains("dispatch("))
        assertTrue(flush.contains("attachments = queuedAttachments(msg.id)"))
        assertFalse(flush.contains("RetichatBridge.messageCreate"))
        assertTrue(body(repo, "dispatch").contains("claimPendingOutbound"))
    }
}
