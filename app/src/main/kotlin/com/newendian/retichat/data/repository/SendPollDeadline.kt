package com.newendian.retichat.data.repository

/**
 * When the state poll ([ChatRepository] `pollMessageState`) gives up on a send
 * and shows it FAILED: only once the send has gone its quiet window without
 * its progress rising. Every rise starts the window again, so a send whose
 * progress keeps rising is never failed here, however long its transfer
 * takes; a send that makes no progress still fails when its window runs out.
 *
 * Progress is the core's LXMessage.progress (messageGetProgress). LXMF's start
 * marks go up to [START_MARK]; above it the payload itself is moving: 0.10 +
 * 0.90 × the Resource's fraction as it transfers (LXMF/LXMessage.py
 * `__update_transfer_progress`; LXMF-rust `transfer_progress_reporter` on the
 * AppLinks path since 2026-09-29), or 0.50 for a PROPAGATED packet handed
 * over. The core's transfer reports move it only upward while the send is
 * SENDING.
 *
 * The window is the send's own ([DIRECT_QUIET_MS], [PROPAGATED_QUIET_MS])
 * until the payload moves, then [TRANSFER_QUIET_MS]. A transfer that stalls
 * is decided by the core, whose own clocks count only time without transfer
 * activity (app-links: Timer P, 5 s; the tier-3 outcome backstop, 120 s) and
 * whose FAILED the poll reads; once the payload moves, this window only
 * catches a transfer the core never decides, so it is the longer one.
 *
 * Until 2026-09-29 the send's window ran from the poll's start whatever the
 * progress did, and was replaced by the transfer window only once progress
 * went above 0.05. On the AppLinks path it never did (the core held it at
 * 0.05 for the whole transfer), so a photo over Bluetooth that took ~4
 * minutes would have been marked FAILED here at 60 s while its Resource was
 * still moving, and flipped to DELIVERED at the late proof. The values are
 * unchanged; what they measure changed.
 */
internal class SendPollDeadline(
    private val quietMs: Long,
    startMs: Long,
) {
    companion object {
        /**
         * A DIRECT send's window: 60 s without its progress rising, while the
         * payload has not started moving. The core starts a DIRECT send at
         * [START_MARK] and a send that fits one packet stays there until its
         * proof, so this is how long a DIRECT send that makes no progress is
         * shown SENDING. Until 2026-09-29, 60 s from the poll's start.
         */
        const val DIRECT_QUIET_MS = 60_000L

        /**
         * A PROPAGATED send's window: 600 s without its progress rising, while
         * the payload has not started moving: a PROPAGATED send can
         * legitimately be slow. Until 2026-09-29, 600 s from the poll's start.
         */
        const val PROPAGATED_QUIET_MS = 600_000L

        /**
         * Any send's window once its payload is moving (progress above
         * [START_MARK]): 600 s without a rise. Longer than the core's own
         * quiet backstop (120 s in tier 3), which decides a stalled transfer;
         * and a DIRECT send can have a Resource on each tier, where only the
         * one furthest along raises the progress while the others still move.
         * Already measured from the last rise before 2026-09-29.
         */
        const val TRANSFER_QUIET_MS = 600_000L

        /**
         * The highest of LXMF's start marks: 0.01 queued, 0.03 link requested,
         * 0.05 link ready and the message handed over (LXMF/LXMRouter.py
         * `process_outbound`; LXMF-rust sets 0.05 as it hands a DIRECT send to
         * AppLinks). Progress above it is the payload moving.
         */
        const val START_MARK = 0.05f
    }

    private var highest = 0f
    private var lastRiseMs = startMs
    private var moving = false

    /** The poll read [progress] at [nowMs]: a rise starts the window again. */
    fun onProgress(progress: Float, nowMs: Long) {
        if (progress <= highest) return
        highest = progress
        lastRiseMs = nowMs
        if (progress > START_MARK) moving = true
    }

    /** Whether the send has gone its whole window, at [nowMs], without a rise. */
    fun expired(nowMs: Long): Boolean = nowMs - lastRiseMs >= windowMs()

    /** The quiet window that applies now (see the constants). */
    fun windowMs(): Long = if (moving) TRANSFER_QUIET_MS else quietMs
}
