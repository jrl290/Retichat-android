package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D7 (2026-09-25): the stack lives for the life of the process. A release
 * never stops it, so balancing the holders cannot tear it down under a
 * visible Activity, and a WakeWorker finishing never stops it. StackRuntime
 * needs the Android runtime and the native library, so this is checked in
 * the source (as NetworkBlocksTest does for its wiring).
 */
class StackLifetimeContractTest {
    private val runtime = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()

    /** The body of `fun [name]`, up to the next member of the object. */
    private fun body(name: String): String {
        val start = runtime.indexOf("fun $name")
        assertTrue("fun $name not found", start >= 0)
        val end = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |@Volatile )")
            .find(runtime, start)?.range?.first ?: runtime.length
        return runtime.substring(start, end)
    }

    @Test
    fun noShutdownIsEverScheduled() {
        assertFalse(runtime.contains("GRACE_SHUTDOWN_MS"))
        assertFalse(runtime.contains("shutdownJob"))
        val release = body("release()")
        assertFalse("the last release stops nothing", release.contains("shutdownNow"))
        assertFalse("and schedules nothing", release.contains("launch"))
        assertFalse(release.contains("delay("))
    }

    @Test
    fun onlyASettingsRestartStopsTheStack() {
        val calls = Regex("shutdownNow\\(app\\)").findAll(runtime).map { it.range.first }.toList()
        assertEquals("one call, from restart()", 1, calls.size)
        val restart = runtime.indexOf("suspend fun restart(")
        assertTrue(restart >= 0 && calls.single() > restart)
        assertFalse(runtime.contains("fun forceShutdown("))
    }

    /** A release nobody took is logged, not silently clamped. */
    @Test
    fun aReleaseWithNoHoldIsReported() {
        val release = body("release()")
        assertTrue(release.contains("if (before == 0) {"))
        assertTrue(release.contains("Log.e(TAG"))
    }

    /**
     * Outside StackRuntime every hold is taken in a shape that gives it back
     * once: [StackRuntime.holding] (any number of callers), WakeWorker.wake
     * or [ForegroundHold]. A bare release is the shape of every unpaired
     * release this app had, and a bare acquire of every leaked hold; the two
     * bare acquires live only where wake and ForegroundHold pair them.
     */
    @Test
    fun everyHoldIsTakenInAShapeThatGivesItBack() {
        val sources = listOf(File("src/main"), File("src/debug"))
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name != "StackRuntime.kt" }
            .associate { it.name to it.readText() }
        fun sites(pattern: String) = sources.mapValues { (_, text) -> text.split(pattern).size - 1 }
            .filterValues { it > 0 }

        assertEquals("no bare release", emptyMap<String, Int>(), sites("StackRuntime.release()"))
        assertEquals("bare acquire only in wake", mapOf("WakeWorker.kt" to 1), sites("StackRuntime.acquire("))
        assertEquals(
            "bare acquire only in ForegroundHold",
            mapOf("RetichatApp.kt" to 1),
            sites("StackRuntime.acquireFromCallback("),
        )
        val pairedAcquires = sites("StackRuntime.acquire(").keys + sites("StackRuntime.acquireFromCallback(").keys
        assertEquals(
            "release handed over only beside the acquire it pairs",
            emptySet<String>(),
            sites("StackRuntime::release").keys - pairedAcquires,
        )
    }
}
