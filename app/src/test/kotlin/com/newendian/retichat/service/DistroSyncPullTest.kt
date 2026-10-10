package com.newendian.retichat.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The receiving side of the distro sync proof (DISTRO-SYNC-PROOF-DESIGN.md §12.15, §13.1): a wake
 * pulls the distro's queue before the channels, the foreground pulls it too, and a pull that stops
 * with more still pending says so.
 */
class DistroSyncPullTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    @Test
    fun aWakePullsTheDistroQueueBeforeAnyChannelAndThePropagationPullStaysLast() {
        val worker = src("service/WakeWorker.kt")
        val distro = worker.indexOf("RfedDistroClient.pull(applicationContext)")
        val channels = worker.indexOf("pullDeferred(channel)")
        val propagation = worker.indexOf("PropagationSync.runOnce(applicationContext, requireFreshTransport = true)")
        assertTrue("the wake must pull the distro queue", distro >= 0)
        assertTrue("the distro pull goes before the per-channel pulls", distro < channels)
        assertTrue("the propagation pull stays after both", channels < propagation)
    }

    @Test
    fun theForegroundPullsTheDistroQueueBesideThePropagationNodeAndNeitherWaitsOnTheOther() {
        val app = src("RetichatApp.kt")
        val start = app.indexOf("private fun triggerForegroundPropagationPull()")
        assertTrue(start >= 0)
        val body = app.substring(start, app.indexOf("internal fun onStackReadyWhileForeground", start))
        assertTrue(body.contains("if (DistroManager.hasDistro)"))
        val distro = body.indexOf("RfedDistroClient.pull(this@RetichatApp)")
        val propagation = body.indexOf("PropagationSync.runOnce(this@RetichatApp)")
        assertTrue(distro >= 0 && propagation >= 0)
        // Each in its own coroutine: a slow distro pull never holds the propagation pull back.
        assertEquals(2, Regex("applicationScope\\.launch \\{").findAll(body).count())
        assertTrue(body.lastIndexOf("applicationScope.launch {", propagation) > distro)
    }

    @Test
    fun aPullThatStopsWithMorePendingSaysSoOnceAndTheRestWaitsForTheNextPull() = runBlocking {
        val warnings = mutableListOf<String>()
        var rounds = 0
        val total = DistroPullRounds.drain(8, warn = { warnings += it }) { rounds++; DistroPullRounds.Page(3, more = true) }
        assertEquals(8, rounds)
        assertEquals(24, total)
        assertEquals(listOf("distro pull: more pending after 8 rounds (24 blobs); the rest waits for the next pull"), warnings)
    }

    @Test
    fun aPullThatDrainsTheQueueOrIsCutShortWarnsOfNothing() = runBlocking {
        val warnings = mutableListOf<String>()
        // The last of the eight rounds says nothing more is pending: drained, no warning.
        var rounds = 0
        val drained = DistroPullRounds.drain(8, warn = { warnings += it }) { rounds++; DistroPullRounds.Page(2, more = rounds < 8) }
        assertEquals(listOf(8, 16), listOf(rounds, drained))
        // Fewer rounds than the bound.
        rounds = 0
        assertEquals(3, DistroPullRounds.drain(8, warn = { warnings += it }) { rounds++; DistroPullRounds.Page(1, more = rounds < 3) })
        assertEquals(3, rounds)
        // A round that could not run (no link, a refused request) ends the pull; it already said why.
        rounds = 0
        assertEquals(5, DistroPullRounds.drain(8, warn = { warnings += it }) { rounds++; if (rounds == 2) null else DistroPullRounds.Page(5, more = true) })
        assertEquals(2, rounds)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun thePullDrainsThroughTheBoundedRoundsWithItsWarningInTheLog() {
        val client = src("service/RfedDistroClient.kt")
        assertTrue(client.contains("private const val PULL_ROUNDS_MAX = 8"))
        assertTrue(client.contains("DistroPullRounds.drain(PULL_ROUNDS_MAX, warn = { Log.w(TAG, it) })"))
    }
}
