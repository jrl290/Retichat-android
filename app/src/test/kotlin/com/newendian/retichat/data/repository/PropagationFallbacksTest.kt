package com.newendian.retichat.data.repository

import com.newendian.retichat.bridge.RetichatBridge.MessageState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The propagated copy of a 1:1 DIRECT send starts on the router's reports, as
 * on iOS: 0x10 or a DIRECT failure, whichever comes first, once per message.
 */
class PropagationFallbacksTest {
    private fun send(id: String) = PropagationFallbacks.Send(id, directHandle = 1L)

    @Test
    fun fallbackRequestStartsTheCopyOnceAndKeepsFollowingTheDirectSend() {
        val fallbacks = PropagationFallbacks()
        val m1 = send("out_1")
        assertNull(fallbacks.track("h1", m1))
        assertSame(m1, fallbacks.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
        assertNull("one copy per message", fallbacks.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
        assertTrue("the DIRECT send keeps running beside the copy", fallbacks.isTracked("h1"))
        assertNull(fallbacks.onPolledState("h1", MessageState.FAILED))
        assertFalse(fallbacks.isTracked("h1"))
    }

    @Test
    fun directFailureAfterTheFallbackRequestStartsNothingMore() {
        val fallbacks = PropagationFallbacks()
        fallbacks.track("h1", send("out_1"))
        fallbacks.onState("h1", MessageState.PROP_FALLBACK_REQUESTED)
        assertNull(fallbacks.onState("h1", MessageState.FAILED))
        assertFalse("no longer tracked", fallbacks.isTracked("h1"))
    }

    @Test
    fun directFailureBeforeAnyFallbackRequestStartsTheCopy() {
        // The reported bug: a DIRECT send that failed inside Android's old 5 s
        // window was never propagated.
        val fallbacks = PropagationFallbacks()
        val m1 = send("out_1")
        fallbacks.track("h1", m1)
        assertSame(m1, fallbacks.onState("h1", MessageState.FAILED))
    }

    @Test
    fun rejectedAndCancelledActLikeFailed() {
        val fallbacks = PropagationFallbacks()
        val m1 = send("out_1")
        val m2 = send("out_2")
        fallbacks.track("h1", m1)
        fallbacks.track("h2", m2)
        assertSame(m1, fallbacks.onState("h1", MessageState.REJECTED))
        assertSame(m2, fallbacks.onState("h2", MessageState.CANCELLED))
        assertNull(fallbacks.onState("h1", MessageState.FAILED))
    }

    @Test
    fun theFirstOfPollAndReportStartsTheCopy() {
        val fallbacks = PropagationFallbacks()
        val m1 = send("out_1")
        fallbacks.track("h1", m1)
        assertSame(m1, fallbacks.onPolledState("h1", MessageState.FAILED))
        assertNull(fallbacks.onState("h1", MessageState.FAILED))
    }

    @Test
    fun deliveryStopsTrackingWithoutACopy() {
        val fallbacks = PropagationFallbacks()
        fallbacks.track("h1", send("out_1"))
        assertNull(fallbacks.onState("h1", MessageState.DELIVERED))
        assertNull(fallbacks.onPolledState("h1", MessageState.FAILED))
        fallbacks.track("h2", send("out_2"))
        assertNull(fallbacks.onState("h2", MessageState.SENT))
        assertNull(fallbacks.onPolledState("h2", MessageState.FAILED))
    }

    @Test
    fun anEarlyFallbackRequestIsReplayedWhenTheSendIsTracked() {
        // The link status was DISCONNECTED: Timer P fired at t=0, before the
        // app had the message's hash.
        val fallbacks = PropagationFallbacks()
        val m1 = send("out_1")
        assertNull(fallbacks.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
        assertSame(m1, fallbacks.track("h1", m1))
        assertNull("the DIRECT send is still followed", fallbacks.onState("h1", MessageState.FAILED))
    }

    @Test
    fun anEarlyFailureIsReplayedWhenTheSendIsTracked() {
        val fallbacks = PropagationFallbacks()
        val m1 = send("out_1")
        fallbacks.onState("h1", MessageState.FAILED)
        assertSame(m1, fallbacks.track("h1", m1))
        assertNull(fallbacks.onPolledState("h1", MessageState.FAILED))
    }

    @Test
    fun anEarlyRequestThenFailureStartsOneCopy() {
        val fallbacks = PropagationFallbacks()
        val m1 = send("out_1")
        fallbacks.onState("h1", MessageState.PROP_FALLBACK_REQUESTED)
        fallbacks.onState("h1", MessageState.FAILED)
        assertSame(m1, fallbacks.track("h1", m1))
        assertNull(fallbacks.onPolledState("h1", MessageState.FAILED))
    }

    @Test
    fun anEarlySuccessDropsWhatWasHeld() {
        val fallbacks = PropagationFallbacks()
        fallbacks.onState("h1", MessageState.PROP_FALLBACK_REQUESTED)
        fallbacks.onState("h1", MessageState.DELIVERED)
        assertNull(fallbacks.track("h1", send("out_1")))
        // Delivered before the app had the hash: nothing is left to follow.
        assertFalse(fallbacks.isTracked("h1"))
    }

    @Test
    fun aStoppedRouterForgetsEverything() {
        val fallbacks = PropagationFallbacks()
        fallbacks.track("h1", send("out_1"))
        fallbacks.onState("h2", MessageState.PROP_FALLBACK_REQUESTED)
        fallbacks.clear()
        assertFalse(fallbacks.isTracked("h1"))
        assertNull("nothing held for h2", fallbacks.track("h2", send("out_2")))
    }

    @Test
    fun intermediateStatesAreNotHeld() {
        val fallbacks = PropagationFallbacks()
        fallbacks.onState("h1", MessageState.SENDING)
        assertNull(fallbacks.track("h1", send("out_1")))
    }

    @Test
    fun heldReportsDropTheOldestFirst() {
        val fallbacks = PropagationFallbacks(capacity = 2)
        fallbacks.onState("h1", MessageState.PROP_FALLBACK_REQUESTED)
        fallbacks.onState("h2", MessageState.PROP_FALLBACK_REQUESTED)
        fallbacks.onState("h3", MessageState.PROP_FALLBACK_REQUESTED)
        assertNull(fallbacks.track("h1", send("out_1")))
        val m2 = send("out_2")
        assertSame(m2, fallbacks.track("h2", m2))
        val m3 = send("out_3")
        assertSame(m3, fallbacks.track("h3", m3))
    }

    @Test
    fun startedCopiesAreRememberedBoundedly() {
        val fallbacks = PropagationFallbacks(capacity = 2)
        val m1 = send("out_1")
        fallbacks.track("h1", m1)
        assertSame(m1, fallbacks.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
        for (i in 2..3) {
            fallbacks.track("h$i", send("out_$i"))
            fallbacks.onState("h$i", MessageState.FAILED)
        }
        // out_1 was forgotten, oldest first, so a later report for it starts again.
        assertSame(m1, fallbacks.onState("h1", MessageState.FAILED))
    }

    @Test
    fun theCopysReportsUnderTheSharedHashStartNothing() {
        // The copy is a clone of the DIRECT message: its SENT and FAILED come
        // under the hash followed for the DIRECT send.
        val fallbacks = PropagationFallbacks()
        val m1 = send("out_1")
        fallbacks.track("h1", m1)
        assertSame(m1, fallbacks.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
        assertNull("the copy failed", fallbacks.onState("h1", MessageState.FAILED))
        assertNull("then the DIRECT attempt", fallbacks.onState("h1", MessageState.FAILED))
        assertNull(fallbacks.onPolledState("h1", MessageState.FAILED))

        val m2 = send("out_2")
        fallbacks.track("h2", m2)
        assertSame(m2, fallbacks.onState("h2", MessageState.PROP_FALLBACK_REQUESTED))
        assertNull("the node took the copy", fallbacks.onState("h2", MessageState.SENT))
        assertNull("the DIRECT attempt failed after", fallbacks.onState("h2", MessageState.FAILED))
        assertNull(fallbacks.onPolledState("h2", MessageState.FAILED))
    }

    @Test
    fun aCopyStartedByTheDirectFailureStartsNothingWhenItReports() {
        val fallbacks = PropagationFallbacks()
        val m1 = send("out_1")
        fallbacks.track("h1", m1)
        assertSame(m1, fallbacks.onState("h1", MessageState.FAILED))
        assertNull("the copy failed", fallbacks.onState("h1", MessageState.FAILED))
        assertNull("the node took the copy", fallbacks.onState("h1", MessageState.SENT))
        assertNull(fallbacks.onPolledState("h1", MessageState.FAILED))
        assertFalse(fallbacks.isTracked("h1"))
    }

    @Test
    fun aFailedCopyHandsTheBubbleBackToADirectAttemptInFlight() {
        // One hash for both attempts: the copy's failure is not the message's
        // while the DIRECT attempt can still deliver it.
        for (state in intArrayOf(MessageState.GENERATING, MessageState.OUTBOUND, MessageState.SENDING)) {
            assertTrue("DIRECT in state $state", PropagationFallbacks.directKeepsTheBubble(state))
        }
    }

    @Test
    fun aFailedCopyNeverFailsADirectAttemptThatSucceeded() {
        assertTrue(PropagationFallbacks.directKeepsTheBubble(MessageState.DELIVERED))
        assertTrue(PropagationFallbacks.directKeepsTheBubble(MessageState.SENT))
    }

    @Test
    fun aFailedCopyFailsTheMessageOnceTheDirectAttemptHasEnded() {
        for (state in intArrayOf(MessageState.FAILED, MessageState.REJECTED, MessageState.CANCELLED)) {
            assertFalse("DIRECT in state $state", PropagationFallbacks.directKeepsTheBubble(state))
        }
        // messageGetState's -1: the DIRECT handle can not be followed.
        assertFalse(PropagationFallbacks.directKeepsTheBubble(-1))
    }
}
