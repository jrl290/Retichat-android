package com.newendian.retichat.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Stack start with a distro: the distro's queue is pulled and applied before the
 * persisted channels are subscribed again (DistroFirst). Found in private staging,
 * 2026-10-10: on a push wake RFed saw /rfed/subscribe, then 0.3 s later the
 * /rfed/unsubscribe of a sibling's leave the wake's pull applied, and a post
 * fanned out between them reached a device whose distro had left the channel.
 * Also the truth rule for the pull's and the announce's no-answer lines.
 */
class DistroFirstResubscribeTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    private class Device(vararg held: String) {
        val channels = held.toMutableList()
        val wire = mutableListOf<String>()
        val said = mutableListOf<Pair<String, Boolean>>()

        /** RFed's queue holds a sibling's leave of #b: the pull applies it here. */
        suspend fun pullApplyingLeaveOfB(): RfedDistroClient.PullOutcome {
            wire += "/rfed/pull"
            if (channels.remove("b")) wire += "/rfed/unsubscribe #b"
            return RfedDistroClient.PullOutcome(1, null)
        }

        suspend fun resubscribe() {
            for (c in channels) wire += "/rfed/subscribe #$c"
        }

        suspend fun start(
            hasDistro: Boolean,
            stopped: () -> Boolean = { false },
            pull: suspend () -> RfedDistroClient.PullOutcome,
        ) = DistroFirst.thenResubscribe(hasDistro, pull, stopped, { line, warning -> said += line to warning }) { resubscribe() }
    }

    @Test
    fun aSiblingsLeaveThePullAppliesIsNeverSubscribedAgain() = runBlocking {
        val d = Device("a", "b")
        d.start(hasDistro = true) { d.pullApplyingLeaveOfB() }
        assertEquals(
            "the leave is applied before any channel is subscribed again, and the left channel is not",
            listOf("/rfed/pull", "/rfed/unsubscribe #b", "/rfed/subscribe #a"),
            d.wire,
        )
        assertEquals(listOf("Distro queue pulled and applied first (1 blob(s)): persisted channels may be re-subscribed now" to false), d.said)
    }

    @Test
    fun aDeviceWithNoDistroResubscribesAtOnceWithNoPull() = runBlocking {
        val d = Device("a", "b")
        d.start(hasDistro = false) { fail("no distro: no pull"); RfedDistroClient.PullOutcome(0, null) }
        assertEquals(listOf("/rfed/subscribe #a", "/rfed/subscribe #b"), d.wire)
        assertTrue(d.said.isEmpty())
    }

    @Test
    fun aPullWithNoAnswerStillLetsTheReSubscriptionGoAndSaysSo() = runBlocking {
        // No interface: the round gets no link inside its 5 s. Its outcome is known, so the channels go now.
        val d = Device("a")
        d.start(hasDistro = true) { RfedDistroClient.PullOutcome(0, DistroPullRounds.noAnswer(stoppedSince = false)) }
        assertEquals(listOf("/rfed/subscribe #a"), d.wire)
        assertEquals(
            listOf(
                "Distro pull before re-subscribing stopped after 0 blob(s): no answer from RFed (no link inside 5 s, " +
                    "or the request went unanswered); persisted channels may be re-subscribed now; a sibling's leave still " +
                    "queued at RFed, if any, is applied when a later pull brings it" to true,
            ),
            d.said,
        )
    }

    @Test
    fun aPullThatThrowsStillLetsTheReSubscriptionGoAndSaysSo() = runBlocking {
        val d = Device("a")
        d.start(hasDistro = true) { throw IllegalStateException("boom") }
        assertEquals(listOf("/rfed/subscribe #a"), d.wire)
        assertEquals(1, d.said.size)
        assertTrue(d.said[0].first.startsWith("Distro pull before re-subscribing threw (boom): persisted channels may be re-subscribed now"))
        assertTrue(d.said[0].second)
    }

    @Test
    fun aPullStoppedAtItsRoundCapSaysTheRestWaits() = runBlocking {
        val d = Device("a")
        d.start(hasDistro = true) { RfedDistroClient.PullOutcome(24, null, morePending = true) }
        assertEquals(listOf("/rfed/subscribe #a"), d.wire)
        assertEquals(
            "Distro pull before re-subscribing applied 24 blob(s) and stopped at its round cap with more pending: " +
                "persisted channels may be re-subscribed now; the rest is applied when a later pull brings it",
            d.said.single().first,
        )
    }

    @Test
    fun aRestartUnderTheStartsPullEndsThatStartAndTheRestartedStacksStartReSubscribes() = runBlocking {
        // Review of 0670af8: a Settings restart (StackRuntime.restart) cancelled the start's
        // round, and the old start then re-subscribed every channel on the NEW stack, racing
        // that stack's own pull: #b, left on a sibling, could be subscribed before the leave.
        var stops = 0L
        val d = Device("a", "b")
        val first = stops
        d.start(hasDistro = true, stopped = { stops != first }) {
            stops++ // shutdownNow counts the stop first; the round then comes back cancelled
            RfedDistroClient.PullOutcome(0, DistroPullRounds.noAnswer(stoppedSince = true))
        }
        assertTrue("the superseded start subscribes nothing", d.wire.isEmpty())
        assertEquals(
            listOf(
                "Distro pull before re-subscribing ended with the stack stopped under it (StackRuntime.restart) after 0 blob(s): " +
                    "this start re-subscribes nothing; the stack's next start runs its own distro pull before its re-subscription" to false,
            ),
            d.said,
        )
        // The restarted stack's start: its own pull applies the leave, then the rest is subscribed.
        val second = stops
        d.start(hasDistro = true, stopped = { stops != second }) { d.pullApplyingLeaveOfB() }
        assertEquals(listOf("/rfed/pull", "/rfed/unsubscribe #b", "/rfed/subscribe #a"), d.wire)
    }

    @Test
    fun aPullThatThrowsUnderARestartEndsTheStartToo() = runBlocking {
        var stops = 0L
        val d = Device("a")
        d.start(hasDistro = true, stopped = { stops != 0L }) { stops++; throw IllegalStateException("link closed") }
        assertTrue(d.wire.isEmpty())
        assertEquals(
            "Distro pull before re-subscribing ended with the stack stopped under it (StackRuntime.restart): " +
                "this start re-subscribes nothing; the stack's next start runs its own distro pull before its re-subscription",
            d.said.single().first,
        )
    }

    @Test
    fun anErrorFromThePullIsAnOutcomeTooNotASilentEndOfTheStart() = runBlocking {
        // Review of 0670af8: only Exception was caught, so an Error (an OutOfMemoryError on a
        // large page, a LinkageError from the native unwrap) skipped both the push
        // re-registration and the re-subscription; the runCatching calls it replaced caught it.
        val d = Device("a")
        d.start(hasDistro = true) { throw LinkageError("unwrap") }
        assertEquals(listOf("/rfed/subscribe #a"), d.wire)
        assertTrue(d.said.single().first.startsWith("Distro pull before re-subscribing threw (unwrap): persisted channels may be re-subscribed now"))
        assertTrue(d.said.single().second)
    }

    @Test
    fun aRoundThatCannotGoIsNeverSentAndNeverSaidAsRfedsSilence() {
        // Review of 0670af8: a pull that waited on pullLock while a restart tore the stack
        // down read the stop count only after the lock, then sent through a router of 0
        // (appLinkSend returns null at once) and said "no answer from RFed".
        assertEquals(null, DistroPullRounds.unsent(stoppedSince = false, stackUp = true))
        assertEquals("the stack is not running: no request was sent", DistroPullRounds.unsent(stoppedSince = false, stackUp = false))
        val cancelled = "cancelled: the stack was stopped (StackRuntime.restart) before the round was sent"
        assertEquals(cancelled, DistroPullRounds.unsent(stoppedSince = true, stackUp = true))
        assertEquals("a stop is said as the stop", cancelled, DistroPullRounds.unsent(stoppedSince = true, stackUp = false))
        val client = src("service/RfedDistroClient.kt")
        val body = client.substring(client.indexOf("suspend fun pullOutcome("), client.indexOf("suspend fun handleBlob("))
        assertTrue("the stop count is read before the pull waits its turn", body.indexOf("val stops = StackRuntime.stops") in 0 until body.indexOf("pullLock.withLock {"))
        val check = body.indexOf("DistroPullRounds.unsent(")
        assertTrue("checked before each round is sent", check > body.indexOf("DistroPullRounds.drain(") && check < body.indexOf("RfedLink.request(app, node, \"/distro/pull\""))
        assertTrue(body.contains("stackUp = ConnectionStateManager.isRegistered"))
        assertTrue(src("service/ConnectionStateManager.kt").contains("get() = routerHandle != 0L"))
    }

    @Test
    fun aPullItsRoundCapStoppedIsNeverSaidDrained() {
        // Review of 0670af8: "pull drained N blob(s)" followed the round cap's warning.
        val client = src("service/RfedDistroClient.kt")
        val body = client.substring(client.indexOf("suspend fun pullOutcome("), client.indexOf("suspend fun handleBlob("))
        val cap = body.indexOf("lastMore -> Log.w(TAG, \"pull stopped at its round cap after \$total blob(s); RFed has more\")")
        val drained = body.indexOf("else -> Log.i(TAG, \"pull drained \$total blob(s)\")")
        assertTrue(cap >= 0 && drained > cap)
        assertEquals(1, Regex("pull drained").findAll(body).count())
    }

    @Test
    fun cancellationIsNotAnOutcomeAndSubscribesNothing() = runBlocking {
        val d = Device("a")
        try {
            d.start(hasDistro = true) { throw CancellationException("scope gone") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue(d.wire.isEmpty())
    }

    @Test
    fun aRoundWithNoAnswerIsSaidAsNoAnswerOrAsTheRestartsCancellationNeverAsAFailure() {
        assertEquals("no answer from RFed (no link inside 5 s, or the request went unanswered)", DistroPullRounds.noAnswer(false))
        assertEquals(
            "cancelled: the stack was stopped (StackRuntime.restart) while the round waited for RFed",
            DistroPullRounds.noAnswer(true),
        )
        val client = src("service/RfedDistroClient.kt")
        assertFalse("until 2026-10-10 a round with no answer read \"request failed\"", client.contains("Log.w(TAG, \"pull: request failed\")"))
        assertTrue(client.contains("DistroPullRounds.noAnswer(stoppedSince = StackRuntime.stops != stops)"))
        assertTrue(client.contains("Log.w(TAG, \"pull stopped after \$total blob(s): \$unfinished\")"))
        val runtime = src("service/StackRuntime.kt")
        val shutdown = runtime.substring(runtime.indexOf("private fun shutdownNow("))
        assertTrue("the stop is counted before anything is torn down", shutdown.indexOf("stops++") < shutdown.indexOf("ConnectionStateManager.unregister()"))
    }

    @Test
    fun theStacksStartWiresTheGateAroundBothThePushReRegistrationAndTheReSubscription() {
        val runtime = src("service/StackRuntime.kt")
        val gate = runtime.indexOf("DistroFirst.thenResubscribe(")
        assertTrue(gate >= 0)
        val block = runtime.substring(gate, runtime.indexOf("val hashHex = selfDestHash", gate))
        assertTrue(block.contains("hasDistro = DistroManager.hasDistro"))
        assertTrue(block.contains("pull = { RfedDistroClient.pullOutcome(app) }"))
        assertTrue("a stop under the start ends it (review of 0670af8)", block.contains("stopped = stopped,"))
        val pull = block.indexOf("pull = {")
        assertTrue("push registration waits for the pull", block.indexOf("reregisterChannelPushOnStart()") > pull)
        assertTrue("the re-subscription waits for the pull", block.indexOf("scheduleResubscribeOnRfedChannelActive(superseded = stopped)") > pull)
        assertTrue(block.contains("// NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1"))
        // The start's stop count is read before the gate is launched, under initLock (bootstrap).
        val before = runtime.substring(runtime.lastIndexOf("app.applicationScope.launch(Dispatchers.IO)", gate) - 200, gate)
        assertTrue(before.contains("val stopsAtStart = stops"))
        assertTrue(before.contains("val stopped = { stops != stopsAtStart }"))
        // Nothing else re-subscribes at start, outside the gate.
        assertEquals(1, Regex("scheduleResubscribeOnRfedChannelActive\\(").findAll(runtime).count())
        assertEquals(1, Regex("reregisterChannelPushOnStart\\(\\)").findAll(runtime).count())
    }

    @Test
    fun pullsRunOneAtATimeSoTheStartsPullCannotComeBackEmptyBesideAWakesPull() {
        val client = src("service/RfedDistroClient.kt")
        val body = client.substring(client.indexOf("suspend fun pullOutcome("), client.indexOf("suspend fun handleBlob("))
        val lock = body.indexOf("pullLock.withLock {")
        assertTrue(lock >= 0)
        assertTrue("every round, and the handling of its blobs, is inside the lock", body.indexOf("DistroPullRounds.drain(") > lock)
        assertTrue(client.contains("suspend fun pull(context: Context): Int = pullOutcome(context).blobs"))
    }

    @Test
    fun everyReturnOfTheReSubscriptionIsSaidNotSilent() {
        // The gate's line only lets the re-subscription run (review of 0670af8: it read
        // "re-subscribing persisted channels" and the function could then return without a word).
        val channels = src("service/RfedChannelClient.kt")
        val body = channels.substring(
            channels.indexOf("suspend fun scheduleResubscribeOnRfedChannelActive("),
            channels.indexOf("suspend fun resubscribePersistedChannels()"),
        )
        assertTrue(body.contains("Log.w(TAG, \"RFed node not reachable within 5 s: \${channels.size} persisted channel(s) not re-subscribed at this start\")"))
        assertTrue(body.contains("Log.w(TAG, \"Persisted channels not re-subscribed at this start: the stack is not running\")"))
        assertTrue(body.contains("Log.i(TAG, \"No persisted channels to re-subscribe at this start\")"))
        assertEquals("both checks of the once-per-process flag say it", 2, Regex("Log\\.i\\(TAG, ALREADY_RESUBSCRIBED\\)").findAll(body).count())
        assertTrue(body.contains("The stack was stopped while this start waited for RFed: \${channels.size} persisted channel(s) not re-subscribed by it"))
        assertFalse(body.contains("waitForRfedReachable(timeoutMs = 5_000L)) return@withContext"))
        assertFalse("no bare return: each is said", Regex("\\) return@withContext\\s*\\n").containsMatchIn(body))
        // A stop while it waited for RFed: the restarted stack's start re-subscribes, not this one.
        assertTrue(body.indexOf("if (superseded())") > body.indexOf("waitForRfedReachable("))
        assertTrue(body.indexOf("if (superseded())") < body.indexOf("didResubscribeOnActive = true"))
        assertEquals(
            "The persisted channels' re-subscription ran already in this process: it does not run again at this start",
            RfedChannelClient.ALREADY_RESUBSCRIBED,
        )
        // One line per channel under "Re-subscribing to N": a hash that cannot be used is said.
        val loop = channels.substring(channels.indexOf("suspend fun resubscribePersistedChannels()"), channels.indexOf("private suspend fun reconfigureChannelStreams("))
        assertFalse(loop.contains("hexToBytes(channel.id) ?: continue"))
        assertFalse(loop.contains("legacyChannelDest(channel.rfedNodeIdentityHashHex) ?: continue"))
        assertTrue(loop.contains("Re-subscribe failed for '\${channel.channelName}': its stored channel hash is not hex"))
        assertTrue(loop.contains("Re-subscribe failed for '\${channel.channelName}': its stored RFed node identity hash is not 32 hex characters"))
    }

    @Test
    fun anUnusableNodeHashIsSaidAsWhatItIsNeverAsNoNodeConfigured() {
        // An unset hash is the default node (UserPreferences.getEffectiveRfedNodeIdentityHash):
        // destHash is null only for a set one that is not 32 hex characters.
        val client = src("service/RfedDistroClient.kt")
        assertTrue(client.contains("private const val NO_NODE = \"the RFed node identity hash set here is not 32 hex characters\""))
        assertTrue(client.contains("Log.w(TAG, \"pull: \$NO_NODE\")"))
        assertTrue(client.contains("return@withContext PullOutcome(0, NO_NODE)"))
        assertTrue(client.contains("return@withContext fail(NO_NODE)"))
        assertFalse(client.contains("no RFed node configured"))
    }

    @Test
    fun noAnswerToTheAnnounceIsNeverCalledARefusal() {
        assertEquals("RFed did not answer the pre-signed announce", DistroCodec.notAYes("pre-signed announce", null))
        assertEquals(
            "RFed refused the pre-signed announce (it answered false)",
            DistroCodec.notAYes("pre-signed announce", byteArrayOf(0xc2.toByte())),
        )
        assertEquals(
            "RFed refused the pre-signed announce (it answered nil)",
            DistroCodec.notAYes("pre-signed announce", byteArrayOf(0xc0.toByte())),
        )
        assertEquals(
            "RFed refused the pre-signed announce (it answered 2 byte(s): 91c2)",
            DistroCodec.notAYes("pre-signed announce", byteArrayOf(0x91.toByte(), 0xc2.toByte())),
        )
        assertEquals(
            "RFed refused the pre-signed announce (it answered 0 byte(s): )",
            DistroCodec.notAYes("pre-signed announce", ByteArray(0)),
        )
        assertEquals("17 byte(s): 000102030405060708090a0b0c0d0e0f…", DistroCodec.describeAnswer(ByteArray(17) { it.toByte() }))
        assertEquals("RFed did not answer the registration", DistroCodec.notAYes("registration", null))
        val client = src("service/RfedDistroClient.kt")
        assertFalse("until 2026-10-10 no answer read \"refused (resp=null bytes)\"", client.contains("resp=\${resp?.size} bytes"))
        assertFalse(client.contains("fail(if (resp == null) \"RFed link not reachable\""))
        val announce = client.substring(client.indexOf("private suspend fun publishAnnounce("), client.indexOf("suspend fun unregister("))
        assertTrue(announce.contains("val said = DistroCodec.notAYes(\"pre-signed announce\", resp)"))
        assertTrue(announce.contains("Log.w(TAG, said)"))
        assertTrue(client.contains("return@withContext fail(DistroCodec.notAYes(\"registration\", resp))"))
    }
}
