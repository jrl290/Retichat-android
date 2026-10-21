package com.newendian.retichat.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * The default backbones (James, 2026-10-21): "make Android and iOS probe all
 * default backbones at once and take the first three that answer, and make
 * Settings show the three actually in use", and "Add a blacklist also".
 *
 * Tested one at a time, each backbone that was down cost its whole 5 s
 * before the stack could start: with two of the seven down, 5.5 s on
 * average. [DefaultEndpointManager.pick] runs here for real with a stand-in
 * for the TCP connect.
 */
class DefaultBackbonesTest {
    private val timeoutMs = 2_000L
    private val now = 1_800_000_000_000L
    private val endpoints = (1..7).map { "bb$it.example" to 4242 }

    /** Answers after [ms] if [up], else never (until the test's timeout). */
    private class Network(private val down: Set<String>, private val ms: Map<String, Long> = emptyMap()) {
        val tested: MutableList<String> = Collections.synchronizedList(mutableListOf())
        suspend fun probe(endpoint: Pair<String, Int>): Boolean {
            tested += endpoint.first
            if (endpoint.first in down) awaitCancellation()
            delay(ms[endpoint.first] ?: 10)
            return true
        }
    }

    private class Run(val picked: List<Pair<String, Int>>, val ms: Long, val recorded: CompletableDeferred<Map<String, Long>>)

    private fun run(network: Network, failedAt: Map<String, Long> = emptyMap(), order: List<Pair<String, Int>> = endpoints): Run =
        runBlocking {
            val recorded = CompletableDeferred<Map<String, Long>>()
            val start = System.currentTimeMillis()
            val picked = DefaultEndpointManager.pick(
                order = order,
                failedAt = failedAt,
                now = now,
                timeoutMs = timeoutMs,
                background = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                probe = network::probe,
                record = { recorded.complete(it) },
            )
            Run(picked, System.currentTimeMillis() - start, recorded)
        }

    private fun recorded(run: Run): Map<String, Long> = runBlocking { withTimeout(5 * timeoutMs) { run.recorded.await() } }

    @Test
    fun backbonesThatAreDownCostNothingWhileOthersAnswer() {
        // The two down come first in the order: one at a time, they cost two timeouts.
        val network = Network(down = setOf("bb1.example", "bb2.example"))
        val run = run(network)
        assertEquals(3, run.picked.size)
        assertTrue("three that answered: ${run.picked}", run.picked.none { it.first in setOf("bb1.example", "bb2.example") })
        assertTrue("picked in ${run.ms} ms, not after a timeout of $timeoutMs", run.ms < timeoutMs / 2)
        assertEquals("all seven tested at once", 7, network.tested.size)
    }

    @Test
    fun theFirstThreeToAnswerInTheOrderTheyAnswered() {
        val network = Network(
            down = emptySet(),
            ms = mapOf("bb1.example" to 400, "bb2.example" to 300, "bb3.example" to 20, "bb4.example" to 120,
                "bb5.example" to 220, "bb6.example" to 500, "bb7.example" to 600),
        )
        assertEquals(listOf("bb3.example", "bb4.example", "bb5.example"), run(network).picked.map { it.first })
    }

    @Test
    fun aBackboneThatFailsWhileOthersAnswerIsSkippedFor24Hours() {
        val run = run(Network(down = setOf("bb1.example", "bb2.example")))
        val skip = recorded(run)
        assertEquals(mapOf("bb1.example:4242" to now, "bb2.example:4242" to now), skip)

        // The next start doesn't test them while the others make up three.
        val next = Network(down = setOf("bb1.example", "bb2.example"))
        val second = run(next, failedAt = skip)
        recorded(second)
        assertFalse("bb1 not tested again", "bb1.example" in next.tested)
        assertFalse("bb2 not tested again", "bb2.example" in next.tested)
        assertEquals(3, second.picked.size)

        // After 24 hours they're tested again.
        val later = Network(down = emptySet())
        runLater(later, skip, DefaultEndpointManager.SKIP_MS)
        assertTrue("bb1 tested again after 24 h", "bb1.example" in later.tested)
    }

    private fun runLater(network: Network, failedAt: Map<String, Long>, afterMs: Long): Map<String, Long> = runBlocking {
        val recorded = CompletableDeferred<Map<String, Long>>()
        DefaultEndpointManager.pick(
            order = endpoints, failedAt = failedAt, now = now + afterMs, timeoutMs = timeoutMs,
            background = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            probe = network::probe, record = { recorded.complete(it) },
        )
        withTimeout(5 * timeoutMs) { recorded.await() }
    }

    @Test
    fun skippedBackbonesAreTestedWhenTheOthersCantMakeUpThree() {
        // Five failed earlier; of the two left one is down, so the two can't
        // make up three and the five are tested at once with them. bb1 and
        // bb2 are still down; bb3-bb5 are back.
        val skip = (1..5).associate { "bb$it.example:4242" to now - 1_000 }
        val network = Network(
            down = setOf("bb6.example", "bb1.example", "bb2.example"),
            ms = mapOf("bb7.example" to 10, "bb3.example" to 150, "bb4.example" to 150, "bb5.example" to 150),
        )
        val run = run(network, failedAt = skip)
        assertEquals("bb7.example", run.picked.first().first)
        assertEquals(3, run.picked.size)
        assertTrue(run.picked.drop(1).all { it.first in setOf("bb3.example", "bb4.example", "bb5.example") })
        assertTrue("no wait for the two to fail first (${run.ms} ms)", run.ms < timeoutMs / 2)
        assertEquals(
            "the ones back are cleared, the ones down marked again",
            mapOf("bb1.example:4242" to now, "bb2.example:4242" to now, "bb6.example:4242" to now),
            recorded(run),
        )
    }

    @Test
    fun offlineMarksNothingAndStillGivesThreeToReconnectTo() {
        val skip = mapOf("bb7.example:4242" to now - 1_000)
        val run = run(Network(down = endpoints.map { it.first }.toSet()), failedAt = skip)
        assertEquals("padded in order, the skipped one last",
            listOf("bb1.example", "bb2.example", "bb3.example"), run.picked.map { it.first })
        // The six, then (they can't make up three) the skipped one: two
        // timeouts at most, where one at a time took seven.
        assertTrue("waited ${run.ms} ms", run.ms < 2 * timeoutMs + timeoutMs / 2)
        assertEquals("none marked while offline", skip, recorded(run))
    }

    @Test
    fun aNameLookupThatHangsCountsAsDownAtTheTimeout() {
        // A blocking lookup the coroutine can't cancel.
        val hang = object {
            suspend fun probe(endpoint: Pair<String, Int>): Boolean {
                if (endpoint.first == "bb2.example") Thread.sleep(3 * timeoutMs)
                return endpoint.first == "bb1.example"
            }
        }
        val recorded = CompletableDeferred<Map<String, Long>>()
        val start = System.currentTimeMillis()
        val picked = runBlocking {
            DefaultEndpointManager.pick(
                order = endpoints.take(2), failedAt = emptyMap(), now = now, timeoutMs = timeoutMs,
                background = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                probe = hang::probe, record = { recorded.complete(it) },
            )
        }
        val ms = System.currentTimeMillis() - start
        assertEquals(listOf("bb1.example", "bb2.example"), picked.map { it.first })
        assertTrue("gave up at the timeout ($ms ms)", ms < 2 * timeoutMs)
        assertEquals(mapOf("bb2.example:4242" to now), runBlocking { withTimeout(2 * timeoutMs) { recorded.await() } })
    }

    @Test
    fun theSkipListSurvivesTheRoundTripThroughThePreferences() {
        val skip = mapOf("rns.noderage.org:4242" to now, "45.77.109.86:4965" to now - 5)
        assertEquals(skip, DefaultEndpointManager.decodeSkipList(DefaultEndpointManager.encodeSkipList(skip)))
        assertEquals(emptyMap<String, Long>(), DefaultEndpointManager.decodeSkipList(""))
        assertEquals("a damaged entry is dropped", mapOf("a:1" to 5L), DefaultEndpointManager.decodeSkipList("a:1=5,b:2=x,junk"))
    }

    @Test
    fun anExpiredOrFutureEntryIsDropped() {
        val skip = mapOf("old:1" to now - DefaultEndpointManager.SKIP_MS, "future:1" to now + 1, "fresh:1" to now - 1)
        assertEquals(mapOf("fresh:1" to now - 1), DefaultEndpointManager.updatedSkipList(skip, emptyList(), now))
    }

    /** The startup and Settings wiring, checked in the source (Android can't run on the JVM). */
    @Test
    fun theStackUsesThePickAndSettingsShowsIt() {
        val stack = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()
        assertTrue(stack.contains("DefaultEndpointManager.selectFallbackEndpoints(app)"))
        assertTrue(stack.contains("DefaultEndpointManager.fallbackInterfaceConfigs(defaultBackbones)"))
        assertTrue(stack.contains("DefaultEndpointManager.setInUse(defaultBackbones)"))
        val settings = File("src/main/kotlin/com/newendian/retichat/ui/settings/SettingsScreen.kt").readText()
        val card = settings.substringAfter("private fun DefaultTcpCard(").substringBefore("\n}\n")
        assertTrue("Settings shows the three in use", card.contains("DefaultEndpointManager.inUse.collectAsState()"))
    }
}
