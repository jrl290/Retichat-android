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
    fun anOwedUploadIsMadeByFivePathsAndNoOther() {
        // The coming-up pass, which reads the failure record with its coming-up; the interface's return,
        // which sends only what never left the device (James, 2026-10-06: nothing was sent, so no failure
        // is retried); and uploadOwedAtOnce, which reads none: the user's own action (owe), the third
        // ruling's send (inside uploadOwed's loss handler), and a never-left upload whose UpEdgeMark says an
        // interface came up since it was handed over (keepNeverLeft: that up-edge was its own). Anything else
        // calling either would send an upload again on its own failure.
        assertEquals(listOf("sendDistroOutbox", "onInterfaceOnline", "uploadOwedAtOnce"), callersOf("uploadOwed"))
        assertEquals(listOf("owe", "uploadOwed", "keepNeverLeft"), callersOf("uploadOwedAtOnce"))
        // The upload it makes has one caller too.
        assertEquals(listOf("uploadOwed"), callersOf("uploadFor"))
    }

    @Test
    fun theInterfacesReturnIsCalledByNoOneInTheOutboxAndSendsOnlyWhatNeverLeftOnTheLinkThatIsUp() {
        // An event the runtime tells (DistroOutboxWiringTest pins its one caller): nothing in the outbox calls it.
        assertEquals(emptyList<String>(), callersOf("onInterfaceOnline"))
        val start = code.indexOf("suspend fun onInterfaceOnline(")
        val edge = code.substring(start, code.indexOf("private fun linkUp("))
        // One upload call, with no coming-up: it reads no failure record.
        assertEquals(1, Regex("uploadOwed\\(").findAll(edge).count())
        assertTrue(edge.contains("uploadOwed(held.id, entry)"))
        // Only the stack running, only the propagation link while it is ACTIVE, only what is recorded never left,
        // still owed, with no attempt open, each checked again when its turn comes.
        for (guard in listOf("if (stopped) return", "held.active", "unproved.neverLeft(it)", "!unproved.neverLeft(entry)",
            "!stillOwed(entry)", "distroAttemptOpen(entry, held.id)", "linkUp(held.id)")) {
            assertTrue(guard, edge.contains(guard))
        }
        // It is no coming-up and no failure's release: it names neither the pass, the count nor a failure record.
        for (name in listOf("sendDistroOutbox", "propComingUps", "unprovedSince", "unproved.record(", "unproved.since(", "comingUp")) {
            assertFalse(name, edge.contains(name))
        }
    }

    @Test
    fun aNeverLeftUploadIsDecidedAndRecordedInOnePlaceAndNeverAsAFailure() {
        // The record is made in keepNeverLeft, and nowhere else; its two callers are where the port did not take
        // the packet and where the stack said the Resource's first advertisement was not carried (2026-10-10).
        assertEquals(1, Regex("unproved\\.recordNeverLeft\\(").findAll(code).count())
        assertEquals(listOf("uploadOwed", "uploadOwed"), callersOf("keepNeverLeft"))
        val keep = code.substring(code.indexOf("private fun keepNeverLeft("), code.indexOf("private fun uploadFor("))
        assertTrue(keep.contains("unproved.recordNeverLeft(entry)"))
        val never = code.substring(code.indexOf("is Handoff.NeverLeft -> {"), code.indexOf("flight.upload = upload"))
        assertTrue(never.contains("keepNeverLeft(entry, handoff.mark, "))
        val resource = code.substring(code.indexOf("upload.onOutcomeNeverLeft = {"), code.indexOf("upload.onOutcomeLost = { why ->"))
        assertTrue(resource.contains("keepNeverLeft(entry, upload.mark, "))
        // No failure record, no coming-up, no error the client shows: nothing was sent.
        for ((where, text) in listOf("packet" to never, "resource" to resource, "keep" to keep)) {
            for (name in listOf("recordUnproved", "unproved.record(", "propComingUps", "log.error", "log.warn", "comingUp")) {
                assertFalse("$where $name", text.contains(name))
            }
        }
        // The packet's refusal decides no Resource: it sets no handler of an upload.
        assertFalse(never.contains("upload.on"))
        // An upload beginning ends the record, before anything can yield.
        val uploadOwed = code.substring(code.indexOf("private suspend fun uploadOwed("))
        assertTrue(uploadOwed.indexOf("unproved.clearNeverLeft(entry)") in 0 until uploadOwed.indexOf("port.build(entry)"))
        // forget() ends both records, so every place that forgets an owed message ends both.
        val forget = code.substring(code.indexOf("fun forget(entry: OwedEntry)"), code.indexOf("val size: Int"))
        assertTrue(forget.contains("clearNeverLeft(entry)"))
    }

    @Test
    fun aNeverLeftAsksItsMarkOnlyOnceRecordedAndSendsOnlyWhatIsStillRecordedOnTheLinkThatIsUp() {
        // app-links send_on_held_link_owed / UpEdgeMark: the mark is taken just before the upload is handed over,
        // and asked only after the never-left is recorded, so an up-edge in between is not missed.
        val handOver = code.substring(code.indexOf("private fun uploadFor("), code.indexOf("private fun uploadProved("))
        assertTrue(handOver.indexOf("val mark = port.upEdgeMark()") in 0 until handOver.indexOf("port.upload(bytes, sink)"))
        assertTrue(handOver.contains("return Handoff.NeverLeft(mark)"))
        assertTrue(handOver.contains("override fun neverLeft() { uploadNeverLeft(upload) }"))
        val keep = code.substring(code.indexOf("private fun keepNeverLeft("), code.indexOf("private fun uploadFor("))
        assertTrue(keep.indexOf("unproved.recordNeverLeft(entry)") in 0 until keep.indexOf("mark.cameUpSince()"))
        // One send, with no coming-up: only with the stack running, the propagation link ACTIVE, the record still
        // standing (an up-edge that took it first sent it), the entry still owed and no attempt open.
        assertEquals(1, Regex("uploadOwedAtOnce\\(").findAll(keep).count())
        assertTrue(keep.contains("uploadOwedAtOnce(held.id, entry)"))
        for (guard in listOf("if (!mark.cameUpSince()) return", "stopped", "held.active", "!unproved.neverLeft(entry)",
            "!stillOwed(entry)", "distroAttemptOpen(entry, held.id)")) {
            assertTrue(guard, keep.contains(guard))
        }
        // The Resource's never-left reaches the outbox from the sink alone, and decides an upload once.
        assertEquals(listOf("uploadFor"), callersOf("uploadNeverLeft"))
        val decide = code.substring(code.indexOf("private fun uploadNeverLeft("), code.indexOf("private fun uploadLost("))
        assertTrue(decide.contains("if (upload.settled != null) return false"))
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
        // Or, for a Resource whose first advertisement no interface carried, by the stack's never-left (no failure).
        assertEquals(listOf("uploadFor"), callersOf("uploadNeverLeft"))
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
