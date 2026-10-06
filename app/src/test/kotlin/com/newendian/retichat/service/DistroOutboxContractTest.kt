package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DESIGN_PRINCIPLES §3, the distro exception: an upload goes again only when
 * the propagation link comes up, never on its own failure and never on a
 * timer. The outbox's own tests drive every ruling; this pins who may call
 * what, so a new path that sent an owed upload again (an announce, a resume, a
 * network change, a failed send) is caught however it is written, as
 * Retichat-js `distro_sent_sync.test.mjs` pins `_uploadOwed`'s three callers.
 */
class DistroOutboxContractTest {
    private val source = File("src/main/kotlin/com/newendian/retichat/service/DistroOutbox.kt").readText()

    /** The file's code with comments and string contents taken out, so a mention is not a call. */
    private val code: String = run {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(source, "")
        noBlock.lines().joinToString("\n") { line ->
            val quote = Regex("\"(\\\\.|[^\"\\\\])*\"")
            val blanked = quote.replace(line, "\"\"")
            val cut = blanked.indexOf("//")
            if (cut >= 0) blanked.substring(0, cut) else blanked
        }
    }

    /**
     * The member of DistroOutbox (a `fun` declared directly in its body) each
     * call of [callee] sits in; a local function, a lambda or an object
     * expression belongs to the member around it.
     */
    private fun callersOf(callee: String): List<String> {
        val callers = mutableListOf<String>()
        var depth = 0
        var member = "<outside>"
        var inClass = false
        val declaration = Regex("\\bfun (?:[\\w.<>]+\\.)?(\\w+)\\(")
        for (line in code.lines()) {
            if (Regex("^class DistroOutbox\\(").containsMatchIn(line)) inClass = true
            val m = declaration.find(line)
            val declares = m?.groupValues?.get(1)
            if (inClass && depth == 1 && declares != null) member = declares
            if (inClass && member != "<outside>" && Regex("\\b$callee\\(").containsMatchIn(line) && declares != callee) callers += member
            depth += line.count { it == '{' } - line.count { it == '}' }
        }
        return callers
    }

    @Test
    fun onlyTheLinkComingUpStartsAFlush() {
        // Established and recovered: the two comings-up James ruled on. Nothing else calls the flush.
        assertEquals(listOf("onLinkEstablished", "onLinkRecovered"), callersOf("sendDistroOutbox"))
    }

    @Test
    fun anOwedUploadIsMadeByThreePathsAndNoOther() {
        // The coming-up pass, which reads the failure record with its coming-up, and uploadOwedAtOnce,
        // which reads none: the user's own action (owe) and the third ruling's send (inside uploadOwed's
        // loss handler). Anything else calling either would send an upload again on its own failure.
        assertEquals(listOf("sendDistroOutbox", "uploadOwedAtOnce"), callersOf("uploadOwed"))
        assertEquals(listOf("owe", "uploadOwed"), callersOf("uploadOwedAtOnce"))
        // The upload it makes has one caller too.
        assertEquals(listOf("uploadOwed"), callersOf("uploadFor"))
    }

    @Test
    fun theThirdRulingsSendIsTheLossOfAnUploadOnAnotherLinkAndNothingElse() {
        val lost = code.substring(code.indexOf("upload.onOutcomeLost = { why ->"), code.indexOf("upload.onLateProof ="))
        assertEquals(1, Regex("uploadOwedAtOnce\\(").findAll(lost).count())
        // Only with a newer link held and up, the entry still owed, no attempt open on it, and the stack running.
        for (guard in listOf("held.id == newer", "held.active", "stillOwed(entry)", "!distroAttemptOpen(entry, newer)", "!stopped")) {
            assertTrue(guard, lost.contains(guard))
        }
        // The upload's own failure is recorded before anything is considered (in the task of the event).
        assertTrue(code.contains("upload.onLost = {\n            recordUnproved()"))
    }

    @Test
    fun anUploadIsDecidedOnlyByItsProofItsLossOrTheStacksStop() {
        // The proof and the loss reach it from the sink the stack is handed, and the stop through cut.
        assertEquals(listOf("uploadFor"), callersOf("uploadProved"))
        assertEquals(listOf("uploadFor", "cut"), callersOf("uploadLost"))
        assertEquals(listOf("onStop"), callersOf("cut"))
    }

    @Test
    fun theOutboxStartsNoLinkAndWaitsOnNoClock() {
        // It never starts the propagation link: its port has held() and upload(), no open or reopen.
        assertFalse(code.contains("appLinkOpen"))
        assertFalse(code.contains("reopen", ignoreCase = true))
        for (clock in listOf("delay(", "Thread.sleep", "withTimeout", "Timer(", "schedule", "postDelayed", "AlarmManager", "Handler(")) {
            assertFalse(clock, code.contains(clock))
        }
        // The one clock it reads is `now`, for the §1 report of a proof that came late: never to send.
        assertEquals(2, Regex("\\bnow\\(\\)").findAll(code).count())
    }
}
