package com.newendian.retichat.service

import kotlinx.coroutines.CancellationException

/**
 * Stack start on a device that holds a distro: its queue first, then the
 * persisted channels (DESIGN_PRINCIPLES §5, strict ordering).
 *
 * RFed holds the siblings' channel joins and leaves for this device (RFed SPEC
 * §17.12; with the distro sync proof, §17.13, a membership message no longer
 * wakes it). Found in private staging, 2026-10-10: on a push wake the start's
 * re-subscription (StackRuntime) sent /rfed/subscribe for a channel the distro
 * had left on another device, and the wake's pull applied that leave 0.3 s
 * later, sending /rfed/unsubscribe; a post fanned out between the two reached
 * the device. Now the pull is applied first, and the channels that are left
 * are subscribed again.
 *
 * The pull's outcome is the event the re-subscription waits for, whatever the
 * outcome is: drained, stopped at its round cap, no answer (no interface, no
 * link inside 5 s), a malformed answer, or a throw. Each is said, and the
 * re-subscription may run: the gate never waits on a timer, never forever, and
 * never drops the re-subscription silently. A device with no distro
 * re-subscribes at once, as before.
 *
 * One outcome ends the start instead: the stack stopped under its pull
 * (StackRuntime.restart, [stopped]). That start's stack is gone, and the
 * restarted stack's own start runs its own pull before its own
 * re-subscription; this one re-subscribing would race that pull on the new
 * stack and could subscribe a channel the distro left before the leave is
 * applied (review of 0670af8, 2026-10-10). It is said, and nothing is
 * re-subscribed by it.
 */
internal object DistroFirst {
    /** The log tag of the re-subscription it gates (RfedChannelClient). */
    const val TAG = "RfedChannel"

    private const val LATER = "a sibling's leave still queued at RFed, if any, is applied when a later pull brings it"

    /**
     * A line for the log; [warning] when the pull did not get RFed's whole
     * answer; [ends] when the start ends here and re-subscribes nothing.
     */
    class Said(val line: String, val warning: Boolean, val ends: Boolean = false)

    suspend fun thenResubscribe(
        hasDistro: Boolean,
        pull: suspend () -> RfedDistroClient.PullOutcome,
        stopped: () -> Boolean,
        say: (line: String, warning: Boolean) -> Unit,
        resubscribe: suspend () -> Unit,
    ) {
        if (hasDistro) {
            val said = pullFirst(pull, stopped)
            say(said.line, said.warning)
            if (said.ends) return
        }
        resubscribe()
    }

    /**
     * The pull, run to its outcome. A throw is an outcome too, an Error as much
     * as an Exception (the runCatching calls this replaced caught every
     * Throwable); a cancellation is not, and propagates.
     */
    internal suspend fun pullFirst(
        pull: suspend () -> RfedDistroClient.PullOutcome,
        stopped: () -> Boolean,
    ): Said {
        val o = try {
            pull()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return if (stopped()) superseded(null) else threw(t)
        }
        return if (stopped()) superseded(o.blobs) else said(o)
    }

    /** The stack stopped under the start's pull: this start ends, and re-subscribes nothing. */
    fun superseded(blobs: Int?): Said = Said(
        "Distro pull before re-subscribing ended with the stack stopped under it (StackRuntime.restart)" +
            (blobs?.let { " after $it blob(s)" } ?: "") +
            ": this start re-subscribes nothing; the stack's next start runs its own distro pull before its re-subscription",
        warning = false,
        ends = true,
    )

    fun threw(t: Throwable): Said = Said(
        "Distro pull before re-subscribing threw (${t.message ?: t.javaClass.simpleName}): " +
            "persisted channels may be re-subscribed now; $LATER",
        warning = true,
    )

    fun said(o: RfedDistroClient.PullOutcome): Said = when {
        o.unfinished != null -> Said(
            "Distro pull before re-subscribing stopped after ${o.blobs} blob(s): ${o.unfinished}; " +
                "persisted channels may be re-subscribed now; $LATER",
            warning = true,
        )
        o.morePending -> Said(
            "Distro pull before re-subscribing applied ${o.blobs} blob(s) and stopped at its round cap " +
                "with more pending: persisted channels may be re-subscribed now; the rest is applied when a later pull brings it",
            warning = true,
        )
        else -> Said("Distro queue pulled and applied first (${o.blobs} blob(s)): persisted channels may be re-subscribed now", warning = false)
    }
}
