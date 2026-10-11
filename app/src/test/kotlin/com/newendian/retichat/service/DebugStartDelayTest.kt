package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The staging probe's start delay (debug builds only): a number of
 * milliseconds in files/debug_start_delay_ms holds the start that long, in a
 * debuggable build and never otherwise, and it is the first thing the
 * bootstrap does, so the whole start is unfinished for that time.
 */
class DebugStartDelayTest {
    @Test
    fun aDebuggableBuildReadsTheDelayAndNoOtherBuildDoes() {
        assertEquals(15_000L, DebugStartDelay.millis(true, "15000\n"))
        assertEquals(1L, DebugStartDelay.millis(true, " 1 "))
        assertEquals(30_000L, DebugStartDelay.millis(true, "30000"))
        assertNull("not debuggable: never read", DebugStartDelay.millis(false, "15000"))
        assertNull("no file", DebugStartDelay.millis(true, null))
        assertNull(DebugStartDelay.millis(true, ""))
        assertNull(DebugStartDelay.millis(true, "soon"))
        assertNull(DebugStartDelay.millis(true, "0"))
        assertNull(DebugStartDelay.millis(true, "-5"))
        assertNull("past the bound", DebugStartDelay.millis(true, "30001"))
    }

    @Test
    fun theBootstrapHoldsFirstAndTheHoldIsGatedOnADebuggableBuild() {
        val runtime = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()
        val start = runtime.indexOf("private suspend fun bootstrap(app: RetichatApp): Boolean {")
        assertTrue(start >= 0)
        val firstLine = runtime.substring(start).lines()[1].trim()
        assertEquals("DebugStartDelay.hold(app)", firstLine)
        val hold = runtime.substring(runtime.indexOf("suspend fun hold(context: Context)"))
        assertTrue(hold.indexOf("FLAG_DEBUGGABLE") in 0 until hold.indexOf("File(context.filesDir, FILE)"))
        assertTrue("nothing in main writes the file", !Regex("writeText|appendText").containsMatchIn(hold.substringBefore("\n}\n")))
    }
}
