package com.newendian.retichat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The state poll fails a send only after it has gone its quiet window without
 * its progress rising (SendPollDeadline), as the core's Timer P and tier-3
 * backstop count only time without transfer activity since 2026-09-29.
 */
class SendPollDeadlineTest {
    private val direct = SendPollDeadline.DIRECT_QUIET_MS
    private val propagated = SendPollDeadline.PROPAGATED_QUIET_MS
    private val transfer = SendPollDeadline.TRANSFER_QUIET_MS

    /**
     * pollMessageState's loop on a simulated clock: check the window, wait
     * (200 ms, ×1.5, capped at 5 s), read [progressAt], report it. Returns
     * when the poll gives up, or null if it is still polling at [endMs] (the
     * send's delivery ends the poll there).
     */
    private fun givesUpAt(quietMs: Long, endMs: Long, progressAt: (Long) -> Float): Long? {
        var now = 0L
        val deadline = SendPollDeadline(quietMs, startMs = now)
        var interval = 200L
        while (!deadline.expired(now)) {
            now += interval
            if (now >= endMs) return null
            deadline.onProgress(progressAt(now), now)
            interval = (interval * 3 / 2).coerceAtMost(5_000L)
        }
        return now
    }

    /** The core's progress for a DIRECT Resource: LXMF/LXMessage.py `__update_transfer_progress`. */
    private fun transferProgress(fraction: Double): Float = (0.10 + 0.90 * fraction.coerceIn(0.0, 1.0)).toFloat()

    @Test
    fun theValuesAreUnchangedOnlyWhatTheyMeasure() {
        // DESIGN_PRINCIPLES.md §4: no timeout was changed to make a send pass.
        assertEquals(60_000L, direct)
        assertEquals(600_000L, propagated)
        assertEquals(600_000L, transfer)
        assertEquals(0.05f, SendPollDeadline.START_MARK)
    }

    @Test
    fun aDirectSendThatMakesNoProgressStillFailsAfterSixtySeconds() {
        val deadline = SendPollDeadline(direct, startMs = 0L)
        deadline.onProgress(0.05f, 200L)
        assertFalse(deadline.expired(200L + direct - 1))
        assertTrue(deadline.expired(200L + direct))
        // Reading the same value again is not progress.
        deadline.onProgress(0.05f, 30_000L)
        assertTrue(deadline.expired(200L + direct))
    }

    /**
     * The device round of 2026-09-29: an iPad photo, 1708 Resource parts,
     * ~4 minutes over a Nearby RTNode Bluetooth link. Its progress now rises
     * with each part request, and the poll follows it to the end.
     */
    @Test
    fun aFourMinutePhotoWhoseProgressRisesIsNeverFailed() {
        val parts = 1708
        val handOverMs = 3_000L        // link up, Resource advertised, first request
        val transferMs = 237_000L
        val deliveredMs = handOverMs + transferMs + 2_000L
        val givenUp = givesUpAt(direct, deliveredMs) { now ->
            if (now < handOverMs) {
                SendPollDeadline.START_MARK
            } else {
                val sent = ((now - handOverMs) * parts / transferMs).coerceAtMost(parts.toLong())
                transferProgress(sent.toDouble() / parts)
            }
        }
        assertNull("a transfer that keeps moving is not stuck", givenUp)
    }

    /** The same photo with the core as it was: progress held at 0.05 for the whole transfer. */
    @Test
    fun theSamePhotoAtAFrozenFivePercentFailsAtSixtySeconds() {
        val givenUp = givesUpAt(direct, 242_000L) { SendPollDeadline.START_MARK }
        assertNotNull(givenUp)
        assertTrue("gave up at $givenUp", givenUp!! in direct..direct + 5_000L)
    }

    @Test
    fun everyRiseStartsTheWindowAgainTheStartMarksIncluded() {
        // Queued, link requested, link ready: 50 s apart, each one progress.
        // Counted from the poll's start (the rule until 2026-09-29) this send
        // failed at 60 s while it was still moving through its phases.
        val givenUp = givesUpAt(direct, 200_000L) { now ->
            when {
                now < 50_000L -> 0.01f
                now < 100_000L -> 0.03f
                now < 150_000L -> SendPollDeadline.START_MARK
                else -> transferProgress((now - 150_000L) / 50_000.0)
            }
        }
        assertNull(givenUp)
    }

    @Test
    fun theStartMarkIsNotTheTransferMoving() {
        val deadline = SendPollDeadline(direct, startMs = 0L)
        deadline.onProgress(SendPollDeadline.START_MARK, 1_000L)
        assertEquals("0.05 is the core handing the send over", direct, deadline.windowMs())
        deadline.onProgress(transferProgress(0.0), 2_000L)
        assertEquals("0.10 is the Resource handed over", transfer, deadline.windowMs())
    }

    @Test
    fun aTransferThatStallsIsGivenTheTransferWindowFromItsLastRise() {
        val deadline = SendPollDeadline(direct, startMs = 0L)
        deadline.onProgress(SendPollDeadline.START_MARK, 200L)
        deadline.onProgress(transferProgress(0.40), 90_000L)
        // Past the 60 s a DIRECT send gets before it moves: the core decides a
        // stalled transfer (its tier-3 backstop is 120 s without activity).
        assertFalse(deadline.expired(90_000L + direct))
        assertFalse(deadline.expired(90_000L + 120_000L))
        assertFalse(deadline.expired(90_000L + transfer - 1))
        assertTrue(deadline.expired(90_000L + transfer))
    }

    @Test
    fun aSlowTransferIsKeptAliveByEachRise() {
        // One rise every 500 s for 50 minutes: never 600 s without one.
        val givenUp = givesUpAt(direct, 3_000_000L) { now ->
            if (now < 1_000L) SendPollDeadline.START_MARK
            else transferProgress((now / 500_000L + 1) / 10.0)
        }
        assertNull(givenUp)
    }

    @Test
    fun progressThatFallsIsNotARise() {
        val deadline = SendPollDeadline(direct, startMs = 0L)
        deadline.onProgress(transferProgress(0.5), 10_000L)
        deadline.onProgress(transferProgress(0.2), 20_000L)
        deadline.onProgress(transferProgress(0.5), 30_000L)
        assertTrue("counted from 0.5's first reading", deadline.expired(10_000L + transfer))
        deadline.onProgress(transferProgress(0.51), 40_000L)
        assertFalse(deadline.expired(10_000L + transfer))
    }

    @Test
    fun aPropagatedSendThatMakesNoProgressFailsAfterItsOwnWindow() {
        val deadline = SendPollDeadline(propagated, startMs = 0L)
        deadline.onProgress(0.01f, 200L)  // waiting for the node's link
        assertFalse(deadline.expired(200L + direct))
        assertFalse(deadline.expired(200L + propagated - 1))
        assertTrue(deadline.expired(200L + propagated))
    }

    // ── Wiring (needs the native library to run, so checked in the source) ──

    private val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
    private val screen = File("src/main/kotlin/com/newendian/retichat/ui/conversation/ConversationScreen.kt").readText()
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var )")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun thePollGivesUpOnlyByTheQuietWindow() {
        val poll = body(repo, "pollMessageState")
        assertTrue(poll.contains("quietMs: Long = SendPollDeadline.DIRECT_QUIET_MS,"))
        assertTrue(poll.contains("val deadline = SendPollDeadline(quietMs, startMs = System.currentTimeMillis())"))
        assertTrue(poll.contains("while (!deadline.expired(System.currentTimeMillis())) {"))
        // Each reading is reported before anything else can end this pass.
        val read = poll.indexOf("val progress = RetichatBridge.messageGetProgress(msgHandle)")
        val report = poll.indexOf("deadline.onProgress(progress, System.currentTimeMillis())")
        assertTrue(read >= 0 && report > read)
        assertTrue(poll.indexOf("return", read) > report)
        // The start-anchored window is gone.
        assertFalse(poll.contains("initialDeadlineMs"))
        assertFalse(poll.contains("longDeadline"))
        assertFalse(poll.contains("progress > 0.05f"))
        assertFalse("nothing moves the window but a rise", Regex("\\n\\s+deadline = ").containsMatchIn(poll))
    }

    @Test
    fun theBubblesBarFollowsTheCoresProgress() {
        // Each poll writes the handle's progress to the row, and the paged
        // Room query redraws the bubble; the bar shows it while SENDING.
        val poll = body(repo, "pollMessageState")
        assertTrue(
            "a row still sending takes the progress read on every pass",
            Regex("""\} else \{\s*messageDao\.updateStateAndProgress\(localId, newState, progress\)\s*\}\s*if \(isTerminalState\(newState\)\)""")
                .containsMatchIn(poll),
        )
        val bar = screen.indexOf("LinearProgressIndicator(\n                            progress = { msg.progress },")
        assertTrue(bar >= 0)
        val condition = screen.lastIndexOf("if (isOut && attachments.isNotEmpty()", bar)
        assertTrue(condition >= 0)
        assertTrue(
            screen.substring(condition, bar).contains(
                "msg.state == RetichatBridge.MessageState.SENDING && msg.progress > 0f && msg.progress < 1f"
            )
        )
    }
}
