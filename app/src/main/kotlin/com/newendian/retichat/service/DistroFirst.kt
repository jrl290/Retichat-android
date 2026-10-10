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
 * link inside 5 s), a malformed answer, a stack restart under it, or a throw.
 * Each is said, and the re-subscription goes on: the gate never waits on a
 * timer, never forever, and never drops the re-subscription silently. A device
 * with no distro re-subscribes at once, as before.
 */
internal object DistroFirst {
    /** The log tag of the re-subscription it gates (RfedChannelClient). */
    const val TAG = "RfedChannel"

    /** A line for the log; [warning] when the pull did not get RFed's whole answer. */
    class Said(val line: String, val warning: Boolean)

    suspend fun thenResubscribe(
        hasDistro: Boolean,
        pull: suspend () -> RfedDistroClient.PullOutcome,
        say: (line: String, warning: Boolean) -> Unit,
        resubscribe: suspend () -> Unit,
    ) {
        if (hasDistro) {
            val said = pullFirst(pull)
            say(said.line, said.warning)
        }
        resubscribe()
    }

    /** The pull, run to its outcome; a throw is an outcome too (cancellation is not). */
    internal suspend fun pullFirst(pull: suspend () -> RfedDistroClient.PullOutcome): Said =
        try {
            said(pull())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Said(
                "Distro pull before re-subscribing threw (${e.message ?: e.javaClass.simpleName}): " +
                    "re-subscribing persisted channels now; a sibling's leave still queued at RFed, if any, " +
                    "is applied when a later pull brings it",
                warning = true,
            )
        }

    fun said(o: RfedDistroClient.PullOutcome): Said = when {
        o.unfinished != null -> Said(
            "Distro pull before re-subscribing stopped after ${o.blobs} blob(s): ${o.unfinished}; " +
                "re-subscribing persisted channels now; a sibling's leave still queued at RFed, if any, " +
                "is applied when a later pull brings it",
            warning = true,
        )
        o.morePending -> Said(
            "Distro pull before re-subscribing applied ${o.blobs} blob(s) and stopped at its round cap " +
                "with more pending: re-subscribing persisted channels now; the rest is applied when a later pull brings it",
            warning = true,
        )
        else -> Said("Distro queue pulled and applied first (${o.blobs} blob(s)): re-subscribing persisted channels", warning = false)
    }
}
