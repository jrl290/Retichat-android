package com.newendian.retichat.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a device owes its distro (RFed SPEC §17.12 "On the sending device",
 * §17.11, "The phones' outbox"), driven with fake link and upload events: every
 * ruling of James's of 2026-10-03 and 2026-10-04 as a case, and the tests the
 * spec asks every client for ("Tests every client makes for the sending
 * device"). The mutation each guards is named where it is not obvious.
 */
class DistroOutboxTest {
    private val d1 = HeldDistro("d".repeat(32), "1".repeat(128))
    private val d2 = HeldDistro("e".repeat(32), "2".repeat(128))

    // ---- fakes ----

    /** What a later run loads: only what the store was given and not told to remove. */
    private class FakeStore : OutboxStore {
        val rows = LinkedHashMap<String, OwedEntry>()
        var refuse = false
        var refuseRemoval = false
        override suspend fun loadAll(): List<OwedEntry> = rows.values.toList()
        override suspend fun put(entry: OwedEntry): Boolean {
            if (refuse) return false
            rows.remove(entry.id)
            rows[entry.id] = entry
            return true
        }
        override suspend fun remove(id: String, msgHash: String) {
            if (rows[id]?.msgHash == msgHash) rows.remove(id)
        }
        override suspend fun removeId(id: String) {
            if (refuseRemoval) throw IllegalStateException("storage refuses it")
            rows.remove(id)
        }
    }

    /** One upload that left, with the sink the stack would call. */
    private class Sent(val linkId: String, val entryId: String, val msgHash: String, val key: String, val sink: UploadSink) {
        fun proved() = sink.proved()
        fun lost() = sink.lost()
        /** The stack found its Resource's first advertisement uncarried, cancelled it and kept the link. */
        fun neverLeft() = sink.neverLeft()
    }

    private class FakePort : PropagationPort {
        var held: HeldLink? = null
        /** Uploads go as a Resource (over the link MDU) rather than one packet. */
        var resource = false
        /** The link is up and no interface can carry the packet: the port does not take it (it could not be queued). */
        var cannotQueue = false
        /** How many uploads the port did not take while the link was up. */
        var refused = 0
        /** Told each time the port does not take an upload (an interface may come back then). */
        var onRefused: (() -> Unit)? = null
        /** Transport's count of interface up-edges, which an [UpEdgeMark] reads. */
        var upEdges = 0L
        /** An interface comes up while the next upload is handed over: after its mark, before the port answers. */
        var upEdgeDuringNextUpload = false
        /** The count each mark was taken at, in order. */
        val marks = mutableListOf<Long>()
        val builds = mutableListOf<OwedEntry>()
        val sent = mutableListOf<Sent>()
        private val gates = ArrayDeque<CompletableDeferred<Unit>>()
        private var failNextBuild: Exception? = null
        private var buildSerial = 0

        /** The next build waits for the returned gate: a stamp that takes a while. */
        fun gateNextBuild(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates.addLast(it) }
        fun failNextBuild(e: Exception) { failNextBuild = e }

        override fun held(): HeldLink? = held

        override suspend fun build(entry: OwedEntry): ByteArray {
            builds += entry
            val gate = gates.removeFirstOrNull()
            gate?.await()
            failNextBuild?.let { failNextBuild = null; throw it }
            buildSerial++
            return "${entry.id}|${entry.msgHash}|${entry.distroKey.take(4)}".toByteArray()
        }

        override fun upEdgeMark(): UpEdgeMark {
            val at = upEdges
            marks += at
            return UpEdgeMark { upEdges != at }
        }

        override fun upload(upload: ByteArray, sink: UploadSink): UploadLeft? {
            if (upEdgeDuringNextUpload) { upEdgeDuringNextUpload = false; upEdges++ }
            val link = held ?: return null
            if (!link.active) return null
            if (cannotQueue) { refused++; onRefused?.invoke(); return null }
            val (id, hash, key) = String(upload).split("|")
            sent += Sent(link.id, id, hash, key, sink)
            return UploadLeft(link.id, resource = resource)
        }

        fun uploadsOf(id: String) = sent.filter { it.entryId == id }
    }

    private class Lines : OutboxLog {
        val info = mutableListOf<String>()
        val warn = mutableListOf<String>()
        val error = mutableListOf<String>()
        override fun info(message: String) { info += message }
        override fun warn(message: String) { warn += message }
        override fun error(message: String) { error += message }
        fun all() = info + warn + error
    }

    private inner class Rig(scope: CoroutineScope, val store: FakeStore = FakeStore(), var distro: HeldDistro? = d1) {
        val port = FakePort()
        val lines = Lines()
        var clock = 1_000L
        val outbox = DistroOutbox(store, port, { distro }, lines, scope, now = { clock })
        fun link(id: String, active: Boolean = true) { port.held = HeldLink(id, active) }
    }

    private fun channel(op: String, name: String, hash: String, distro: HeldDistro = d1, at: Long = 1) = OwedEntry(
        id = DistroChannelSync.entryId("hash-of-$name"), kind = OwedKind.CHANNEL,
        distro = distro.delivery, distroKey = distro.publicKey, msgHash = hash, packed = hash.toByteArray(),
        label = "the $op of #$name (§17.12)", op = op, name = name, atMs = at,
    )

    private fun sentCopy(hash: String, to: String = "c".repeat(32), distro: HeldDistro = d1) = OwedEntry(
        id = "sent:$hash", kind = OwedKind.SENT, distro = distro.delivery, distroKey = distro.publicKey,
        msgHash = hash, packed = hash.toByteArray(), label = "the sent-copy for ${to.take(8)} (§17.11)", toHex = to,
    )

    private fun run(block: suspend CoroutineScope.(Rig) -> Unit) = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        block(rig)
    }

    // ---- owing ----

    @Test
    fun anEntryIsKeptBeforeAnythingCanYieldAndWaitsForTheLinkWhenItIsNotUp() = run { rig ->
        val join = channel("join", "public.x", "m1")
        rig.outbox.owe(join)
        // Written, and nothing built or sent: the link is not up, and it is never started.
        assertEquals(listOf(join), rig.store.rows.values.toList())
        assertEquals(emptyList<OwedEntry>(), rig.port.builds)
        assertTrue(rig.lines.info.any { it.contains("owed to the distro") })
        assertEquals(listOf(join), rig.outbox.owed())
    }

    @Test
    fun withTheLinkUpItGoesAtOnceAndOnlyTheNodesProofSettlesIt() = run { rig ->
        rig.link("L1")
        val join = channel("join", "public.x", "m1")
        rig.outbox.owe(join)
        yield()
        assertEquals(1, rig.port.sent.size)
        // Sent is not proved: it is still owed, in storage too.
        assertEquals(listOf(join), rig.outbox.owed())
        assertTrue(rig.store.rows.containsKey(join.id))
        rig.port.sent[0].proved()
        yield()
        assertEquals(emptyList<OwedEntry>(), rig.outbox.owed())
        assertTrue("storage lets it go too", rig.store.rows.isEmpty())
        assertTrue(rig.lines.info.any { it.contains("propagated to the distro as a packet") })
        assertTrue(rig.lines.error.isEmpty())
    }

    @Test
    fun theUsersOwnActionReadsNoFailureRecordAndIsNotHeldBackByAnEarlierFailure() = run { rig ->
        rig.link("L1")
        val a = sentCopy("a")
        rig.outbox.owe(a)
        yield()
        rig.port.sent[0].lost()
        // A second message, owed after a failure of another, is the user's own action: it goes.
        rig.outbox.owe(sentCopy("b"))
        yield()
        assertEquals(2, rig.port.sent.size)
    }

    // ---- what storage refuses ----

    @Test
    fun storageThatRefusesAWriteLargerThanWhatItHoldsNeverKeepsAReplacedJoinForALaterRun() = run { rig ->
        // Owe a join (kept), fill storage, owe a §17.11 sent copy (refused), then owe a
        // leave of the same channel (refused): what storage holds must not hold the join.
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        assertTrue(rig.store.rows.containsKey(join.id))
        rig.store.refuse = true
        rig.outbox.owe(sentCopy("copy"))
        val leave = channel("leave", "public.x", "leave")
        rig.outbox.owe(leave)
        assertFalse("the replaced join is not left for a later run", rig.store.rows.containsKey(join.id))
        // This run still holds what the user did last, and is told: never silent.
        assertEquals(setOf(sentCopy("copy").id, leave.id), rig.outbox.owed().map { it.id }.toSet())
        assertEquals(2, rig.lines.error.count { it.contains("could not be kept in storage") })
        // It still goes while the link is up in this run.
        rig.link("L1")
        rig.outbox.onLinkEstablished("L1")
        assertEquals(setOf(sentCopy("copy").id, leave.id), rig.port.sent.map { it.entryId }.toSet())
        assertTrue("the replaced join never goes", rig.port.sent.none { it.msgHash == "join" })
    }

    @Test
    fun storageThatRefusesEvenTheSmallerWriteIsSaid() = run { rig ->
        rig.outbox.owe(channel("join", "public.x", "join"))
        rig.store.refuse = true
        rig.store.refuseRemoval = true
        rig.outbox.owe(channel("leave", "public.x", "leave"))
        assertTrue(rig.lines.error.any { it.contains("refuses even a smaller write") })
    }

    // ---- a distro given up ----

    @Test
    fun aMembershipMessageOwedToADistroGivenUpIsDroppedAndASentCopyKeepsItsOwnDistro() = run { rig ->
        val join = channel("join", "public.x", "join")
        val copy = sentCopy("copy")
        rig.outbox.owe(join)
        rig.outbox.owe(copy)
        // Give up D (a log line says the join was dropped), import D again, bring the link up.
        rig.link("L0")
        rig.outbox.onLinkEstablished("L0")
        rig.port.sent.single { it.entryId == join.id }.lost()
        assertNotNull(rig.outbox.unproved.at(join))
        rig.port.held = null
        rig.distro = null
        rig.outbox.onDistroChanged()
        yield()
        assertNull("a record goes with the message it belongs to", rig.outbox.unproved.at(join))
        assertTrue(rig.lines.warn.any { it.contains("no longer holds") && it.contains("the join of #public.x") })
        assertEquals(listOf(copy), rig.outbox.owed())
        assertFalse(rig.store.rows.containsKey(join.id))
        rig.distro = d1
        rig.link("L1")
        rig.outbox.onLinkEstablished("L1")
        // The join is not uploaded again (mutation: keeping it while no distro is held uploads it, and
        // the sibling joins a channel this device left); the copy goes once.
        assertEquals(1, rig.port.sent.count { it.entryId == copy.id })
        assertEquals(1, rig.port.sent.count { it.entryId == join.id })
    }

    @Test
    fun aSentCopyMadeUnderD1StillGoesToD1WhenTheDeviceHoldsD2() = run { rig ->
        val old = sentCopy("old", distro = d1)
        rig.outbox.owe(old)
        // Taking D2 drops no sent-copy: it is already packed and signed as D1, with D1's key.
        rig.distro = d2
        rig.outbox.onDistroChanged()
        val fresh = sentCopy("fresh", distro = d2)
        rig.outbox.owe(fresh)
        rig.link("L1")
        rig.outbox.onLinkEstablished("L1")
        // Each is built with its OWN distro's key (mutation: the key of the distro held now would
        // encrypt D1's copy to D2, which D1's devices cannot read).
        assertEquals(listOf("1111", "2222"), rig.port.sent.map { it.key })
        assertEquals(listOf(d1.publicKey, d2.publicKey), rig.port.builds.map { it.distroKey })
    }

    @Test
    fun givingUpTheDistroWhileASentCopyIsBuiltStillUploadsItToItsOwnDistro() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        rig.link("L1")
        val gate = rig.port.gateNextBuild()
        rig.outbox.owe(sentCopy("copy"))
        yield()
        rig.distro = null
        rig.outbox.onDistroChanged()
        gate.complete(Unit)
        yield()
        assertEquals(1, rig.port.sent.size)
        assertEquals("1111", rig.port.sent[0].key)
    }

    @Test
    fun theLogLineSaysAnUploadAlreadyOnTheWireCannotBeTakenBack() = run { rig ->
        rig.link("L1")
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        rig.distro = null
        rig.outbox.onDistroChanged()
        assertTrue(rig.lines.warn.single().contains("already left"))
        // And its proof, if the node gives it, is said as any proof is.
        rig.port.sent[0].proved()
        assertTrue(rig.lines.info.any { it.contains("propagated") })
    }

    // ---- the app stops and starts ----

    @Test
    fun whatIsOwedSurvivesARestartAndGoesOnceOnTheNextLink() = runBlocking {
        val store = FakeStore()
        val first = Rig(this, store)
        first.outbox.start()
        val join = channel("join", "public.x", "join")
        first.outbox.owe(join)
        // The app stops before the link ever came up: a new process loads the store.
        val second = Rig(this, store)
        second.outbox.start()
        assertEquals(listOf(join), second.outbox.owed())
        second.link("L1")
        second.outbox.onLinkEstablished("L1")
        assertEquals(1, second.port.sent.size)
        second.port.sent[0].proved()
        yield()
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun theStacksStopDecidesAnOpenUploadAndSendsNothingAndItGoesOnTheNextStart() = run { rig ->
        rig.link("L1")
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        // A newer link is up when the stop decides it: still nothing is sent.
        rig.link("L2")
        rig.outbox.onStop()
        assertEquals(1, rig.port.sent.size)
        assertEquals(listOf(join), rig.outbox.owed())
        assertTrue(rig.lines.error.any { it.contains("the connection stopped") })
        // Stopped, the next start's first link carries it, once.
        rig.link("L9")
        rig.outbox.start()
        rig.outbox.onLinkEstablished("L9")
        assertEquals(listOf("L1", "L9"), rig.port.sent.map { it.linkId })
    }

    // ---- an upload that is lost ----

    @Test
    fun anUploadReportedLostIsSaidAndWaitsForTheNextComingUpThenGoesOnce() = run { rig ->
        rig.link("L1")
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        rig.port.sent[0].lost()
        yield()
        // Said: a log line and an error the client can show. It stays owed, and is not sent at the
        // failure itself (mutation: uploading it again at the failure sends it with no coming-up).
        assertTrue(rig.lines.error.any { it.contains("was not proved") && it.contains("still owed") })
        assertEquals(listOf(join), rig.outbox.owed())
        assertEquals(1, rig.port.sent.size)
        // The next coming-up is the link's next establishment...
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(listOf("L1", "L2"), rig.port.sent.map { it.linkId })
        // ...and it goes once.
        rig.port.sent[1].proved()
        rig.link("L3")
        rig.outbox.onLinkEstablished("L3")
        assertEquals(2, rig.port.sent.size)
    }

    @Test
    fun theNextComingUpMayBeAStaleLinksRecoveryOnTheSameLink() = run { rig ->
        rig.link("L1")
        rig.outbox.owe(channel("join", "public.x", "join"))
        yield()
        rig.port.sent[0].lost()
        // The link recovers from STALE: it comes up. Mutation: no flush on the recovery leaves it
        // waiting for an establishment that may never come.
        rig.outbox.onLinkRecovered("L1")
        assertEquals(listOf("L1", "L1"), rig.port.sent.map { it.linkId })
    }

    @Test
    fun aStaleLinkThatANewOneReplacedStartsNoFlushWhenItRecovers() = run { rig ->
        rig.link("L1")
        rig.outbox.owe(channel("join", "public.x", "join"))
        yield()
        rig.port.sent[0].lost()
        rig.link("L2")
        // L1 is no longer the propagation link: its recovery sends nothing and counts nothing.
        val before = rig.outbox.propComingUps
        rig.outbox.onLinkRecovered("L1")
        assertEquals(1, rig.port.sent.size)
        assertEquals(before, rig.outbox.propComingUps)
        // L2's own coming-up carries it.
        rig.outbox.onLinkEstablished("L2")
        assertEquals(listOf("L1", "L2"), rig.port.sent.map { it.linkId })
    }

    @Test
    fun nothingButTheLinkComingUpStartsAFlush() = run { rig ->
        rig.link("L1")
        rig.outbox.owe(channel("join", "public.x", "join"))
        yield()
        rig.port.sent[0].lost()
        // Not a distro change, not another action, not a start: a failed upload goes again only on a coming-up.
        rig.outbox.onDistroChanged()
        rig.outbox.start()
        rig.outbox.owe(sentCopy("other"))
        yield()
        assertEquals(listOf("channel:hash-of-public.x", "sent:other"), rig.port.sent.map { it.entryId })
        assertEquals(1, rig.port.uploadsOf("channel:hash-of-public.x").size)
    }

    @Test
    fun aProofAfterTheLossReportSettlesItAndItDoesNotGoAgain() = run { rig ->
        rig.link("L1")
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        rig.port.sent[0].lost()
        // The node had the packet after all.
        rig.port.sent[0].proved()
        yield()
        assertEquals(emptyList<OwedEntry>(), rig.outbox.owed())
        assertTrue(rig.store.rows.isEmpty())
        assertTrue(rig.lines.warn.any { it.contains("proved after all") })
        // Mutation: ignoring the late proof uploads it again on the next link.
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(1, rig.port.sent.size)
    }

    @Test
    fun aProofLaterThanFiveSecondsIsLoggedAsAViolationAndSettlesAllTheSame() = run { rig ->
        rig.link("L1")
        rig.outbox.owe(sentCopy("copy"))
        yield()
        rig.clock += 7_000
        rig.port.sent[0].proved()
        yield()
        assertTrue(rig.lines.error.any { it.contains("§1 VIOLATION") && it.contains("7000 ms") })
        assertEquals(emptyList<OwedEntry>(), rig.outbox.owed())
    }

    @Test
    fun aBuildThatThrowsIsSaidKeptOwedAndGoesOnceOnTheNextComingUp() = run { rig ->
        rig.link("L1")
        rig.port.failNextBuild(IllegalStateException("the stamp cannot be mined"))
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        assertTrue(rig.port.sent.isEmpty())
        assertTrue(rig.lines.error.any { it.contains("the stamp cannot be mined") && it.contains("still owed") })
        assertEquals(listOf(join), rig.outbox.owed())
        // A flush under way that listed it does not build it again, and the link coming up does.
        rig.outbox.onLinkEstablished("L1")
        assertEquals(1, rig.port.sent.size)
    }

    @Test
    fun anUploadWhoseBuildThrowsWhileAFlushThatListedItBuildsAnEarlierEntryIsNotBuiltAgainByThatFlush() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        val early = sentCopy("early")
        val x = channel("join", "public.x", "x")
        // Both owed with the link down.
        rig.outbox.owe(early)
        rig.outbox.owe(x)
        rig.link("L1")
        // The established flush lists early and x, and builds early (a stamp that takes a while).
        val gate = rig.port.gateNextBuild()
        val first = launch { rig.outbox.onLinkEstablished("L1") }
        yield()
        // A second coming-up (a recovery) lists them too: early is in flight, x is built, and its build throws.
        rig.port.failNextBuild(IllegalStateException("the key cannot be read"))
        val second = launch { rig.outbox.onLinkRecovered("L1") }
        yield()
        second.join()
        assertTrue(rig.lines.error.any { it.contains("the key cannot be read") })
        assertEquals(1, rig.port.builds.count { it.id == x.id })
        // The first flush reaches x: its failure was decided at this coming-up or later, so it is not built
        // again (mutation: a build failure left unrecorded is built again by the flush under way, with no
        // coming-up between).
        gate.complete(Unit)
        first.join()
        assertEquals(1, rig.port.builds.count { it.id == x.id })
        assertTrue(rig.port.sent.none { it.entryId == x.id })
        // It goes once on the next coming-up.
        rig.outbox.onLinkEstablished("L1")
        assertEquals(2, rig.port.builds.count { it.id == x.id })
        assertEquals(1, rig.port.sent.count { it.entryId == x.id })
    }

    // ---- a STALE link replaced by a new one (the third ruling) ----

    @Test
    fun aReplacedLinkDecidesNothingTheNewLinkDoesNotUploadWhileTheFirstUploadIsOpenAndTheLateProofSettlesIt() = run { rig ->
        rig.link("L1")
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        assertEquals(1, rig.port.sent.size)
        // L1 goes STALE and L2 replaces it: L2's coming-up must not upload the entry a second time
        // while the first upload is open (mutation: an in-flight check for the same link only).
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(1, rig.port.sent.size)
        // The node proves the upload over the old link: one upload, one stamp, one fan-out.
        rig.port.sent[0].proved()
        yield()
        assertEquals(emptyList<OwedEntry>(), rig.outbox.owed())
        rig.link("L3")
        rig.outbox.onLinkEstablished("L3")
        assertEquals(1, rig.port.sent.size)
    }

    @Test
    fun theOldLinksUploadLostWhileANewerLinkIsUpGoesOnceOnTheNewerLinkAtOnce() = run { rig ->
        rig.link("L1")
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(1, rig.port.sent.size)
        // The old link's upload is reported lost (the phones' receipt timeout, or a Resource's failure):
        // that is the event. Mutation: leaving it for the next coming-up while the newer link is up.
        rig.port.sent[0].lost()
        yield()
        assertEquals(listOf("L1", "L2"), rig.port.sent.map { it.linkId })
        assertTrue(rig.lines.error.any { it.contains("goes once on that one now") })
        // A failure of that upload on the newer link waits for the next coming-up like any other.
        rig.port.sent[1].lost()
        yield()
        assertEquals(2, rig.port.sent.size)
        rig.link("L3")
        rig.outbox.onLinkEstablished("L3")
        assertEquals(listOf("L1", "L2", "L3"), rig.port.sent.map { it.linkId })
    }

    @Test
    fun withNoNewerLinkUpAnOldLinksLossWaitsForTheNextComingUp() = run { rig ->
        rig.link("L1")
        rig.outbox.owe(channel("join", "public.x", "join"))
        yield()
        // L1 was replaced by L2, which has not come up yet (still establishing).
        rig.link("L2", active = false)
        rig.port.sent[0].lost()
        yield()
        assertEquals(1, rig.port.sent.size)
        // Not even built: a link that is not up is no event.
        assertEquals(1, rig.port.builds.size)
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(listOf("L1", "L2"), rig.port.sent.map { it.linkId })
    }

    @Test
    fun aLateProofOverTheOldLinkAfterTheLossSentItOnTheNewLinkSettlesItAndTheNewUploadsLossSendsNothing() = run { rig ->
        rig.link("L1")
        rig.outbox.owe(channel("join", "public.x", "join"))
        yield()
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        rig.port.sent[0].lost()
        yield()
        assertEquals(2, rig.port.sent.size)
        // The node then proves the first upload late: owed no more.
        rig.port.sent[0].proved()
        yield()
        assertEquals(emptyList<OwedEntry>(), rig.outbox.owed())
        // The new link's upload is lost: nothing goes, now or on the next coming-up.
        rig.port.sent[1].lost()
        yield()
        rig.link("L3")
        rig.outbox.onLinkEstablished("L3")
        assertEquals(2, rig.port.sent.size)
        // And if both uploads reached the node, the sibling holds the second as a repeat: the same message.
        assertEquals(rig.port.sent[0].msgHash, rig.port.sent[1].msgHash)
    }

    @Test
    fun aStopDecidesTheOldUploadWithANewerLinkUpAndSendsNothing() = run { rig ->
        rig.link("L1")
        rig.outbox.owe(channel("join", "public.x", "join"))
        yield()
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        rig.outbox.onStop()
        yield()
        assertEquals(1, rig.port.sent.size)
    }

    // ---- flushes and the failure record ----

    @Test
    fun twoFlushesOnOneLinkWithALossBetweenThemTheEarlierDoesNotUploadAgainWhatTheNewerSawLost() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        val y = sentCopy("y")
        val ch = channel("join", "public.x", "join")
        rig.outbox.owe(y)
        rig.outbox.owe(ch)
        rig.link("L1")
        // The established flush builds y (a stamp that takes a while); a recovered flush then runs
        // and uploads ch, whose packet is reported lost; then the first flush reaches ch.
        val gate = rig.port.gateNextBuild()
        val established = launch { rig.outbox.onLinkEstablished("L1") }
        yield()
        val recovered = launch { rig.outbox.onLinkRecovered("L1") }
        yield()
        recovered.join()
        // The recovered flush (coming-up 2) skipped y (in flight for the first flush) and uploaded ch.
        assertEquals(listOf(ch.id), rig.port.sent.map { it.entryId })
        rig.port.sent.single().lost()
        gate.complete(Unit)
        established.join()
        // The earlier flush uploaded y and did not upload ch again, though ch is owed and not in
        // flight: mutation, a flush that goes on after a newer one began uploads it with no coming-up.
        assertEquals(1, rig.port.sent.count { it.entryId == ch.id })
        assertEquals(1, rig.port.sent.count { it.entryId == y.id })
    }

    /** An entry y owed after a loss, then the user's own entry ch uploaded at once: the case of the first two tests below. */
    private suspend fun CoroutineScope.yOwedAfterALossThenChUploaded(rig: Rig): Pair<OwedEntry, OwedEntry> {
        rig.link("L1")
        val y = sentCopy("y")
        rig.outbox.owe(y)
        yield()
        rig.port.sent.single { it.entryId == y.id }.lost()
        yield()
        val ch = channel("join", "public.x", "join")
        rig.outbox.owe(ch)
        yield()
        assertEquals(1, rig.port.sent.count { it.entryId == ch.id })
        return y to ch
    }

    @Test
    fun theUsersOwnUploadReportedLostWhileARecoveredFlushBuildsAnEarlierEntryIsNotUploadedByThatFlush() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        val (y, ch) = yOwedAfterALossThenChUploaded(rig)
        // The link goes STALE and recovers: the recovered flush lists y and ch and builds y.
        val gate = rig.port.gateNextBuild()
        val flush = launch { rig.outbox.onLinkRecovered("L1") }
        yield()
        assertEquals("the flush builds y first", y.id, rig.port.builds.last().id)
        // ch's packet is reported lost while the flush builds y.
        rig.port.sent.single { it.entryId == ch.id }.lost()
        yield()
        gate.complete(Unit)
        flush.join()
        // Mutation: a flush that checks only that the entry is owed and not in flight uploads it at
        // once, with no coming-up after the failure (Retichat-js 145ca2f).
        assertEquals(1, rig.port.sent.count { it.entryId == ch.id })
        assertEquals(2, rig.port.sent.count { it.entryId == y.id })
        // Nor did the flush mine a stamp for it.
        assertEquals(1, rig.port.builds.count { it.id == ch.id })
        // It goes once on the link's next coming-up, which may be on the same link.
        rig.outbox.onLinkRecovered("L1")
        assertEquals(2, rig.port.sent.count { it.entryId == ch.id })
    }

    @Test
    fun anUploadOnAReplacedLinkDecidedWhileTheNewLinksFlushBuildsAnEarlierEntryGoesOnceOnTheNewLinkAndItsFailureWaits() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        val (y, ch) = yOwedAfterALossThenChUploaded(rig)
        // L1 goes STALE and L2 replaces it; L2's established flush lists y and ch, and builds y. ch is
        // open on L1 and so left alone.
        rig.link("L2")
        val gate = rig.port.gateNextBuild()
        val flush = launch { rig.outbox.onLinkEstablished("L2") }
        yield()
        assertEquals(y.id, rig.port.builds.last().id)
        // L1's upload of ch is reported lost: the newer link is up, so it goes once on L2 at once (the third ruling).
        rig.port.sent.single { it.entryId == ch.id && it.linkId == "L1" }.lost()
        yield()
        val onL2 = rig.port.sent.single { it.entryId == ch.id && it.linkId == "L2" }
        // That upload is reported lost too, still while the flush builds y: a failure waits for the next coming-up.
        onL2.lost()
        yield()
        gate.complete(Unit)
        flush.join()
        // Mutation: a flush that does not read when an upload failed uploads it a third time at once.
        assertEquals(2, rig.port.sent.count { it.entryId == ch.id })
        assertEquals("built for L1, and again for the send on L2, never a third time", 2, rig.port.builds.count { it.id == ch.id })
        rig.link("L3")
        rig.outbox.onLinkEstablished("L3")
        assertEquals(3, rig.port.sent.count { it.entryId == ch.id })
    }

    @Test
    fun anUploadAnEarlierFlushMadeReportedLostWhileALaterFlushOnTheSameLinkBuildsIsNotUploadedByIt() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        val y = sentCopy("y")
        val ch = channel("join", "public.x", "join")
        // y's upload is open on L1 (the user's own action) and ch is owed with no link up: [y, ch].
        rig.link("L1")
        rig.outbox.owe(y)
        yield()
        val yUpload = rig.port.sent.single()
        rig.port.held = null
        rig.outbox.owe(ch)
        rig.link("L1")
        // The first flush (coming-up 1) leaves y to its open upload and uploads ch.
        rig.outbox.onLinkEstablished("L1")
        val chUpload = rig.port.sent.single { it.entryId == ch.id }
        // y's upload is lost before the second flush starts; the second (coming-up 2) builds y.
        yUpload.lost()
        yield()
        val gate = rig.port.gateNextBuild()
        val second = launch { rig.outbox.onLinkRecovered("L1") }
        yield()
        assertEquals(y.id, rig.port.builds.last().id)
        // ch, made by the FIRST flush, is reported lost while the second builds y. The failure is
        // recorded at the coming-up current now (2), not the first flush's (1).
        chUpload.lost()
        yield()
        gate.complete(Unit)
        second.join()
        // Mutation: recording the failure at the coming-up of the flush that made the upload (1) lets the
        // second flush (2) upload it again at once.
        assertEquals(1, rig.port.sent.count { it.entryId == ch.id })
        assertEquals(1, rig.port.builds.count { it.id == ch.id })
    }

    // ---- two actions on one channel whose decisions overlap ----

    /**
     * The user joins CH and the join's upload stays open on L1; L2 replaces L1; the user leaves CH and the
     * leave goes at once on L2. An older entry `early` (owed after a loss) is listed before the leave. A
     * recovered flush on L2 builds `early`. Returns what the cases below decide in different orders.
     */
    private inner class Overlap(val scope: CoroutineScope, val rig: Rig) {
        val early = sentCopy("early")
        val join = channel("join", "public.x", "join")
        val leave = channel("leave", "public.x", "leave")
        lateinit var joinUpload: Sent
        lateinit var leaveUpload: Sent
        lateinit var flush: kotlinx.coroutines.Job
        lateinit var gate: CompletableDeferred<Unit>

        suspend fun setUp() {
            rig.link("L1")
            rig.outbox.owe(early)
            yield()
            rig.port.sent.single { it.entryId == early.id }.lost()
            yield()
            rig.outbox.owe(join)
            yield()
            joinUpload = rig.port.sent.single { it.entryId == join.id }
            rig.link("L2")
            rig.outbox.owe(leave)
            yield()
            leaveUpload = rig.port.sent.single { it.msgHash == "leave" }
            assertEquals("the leave goes at once on the newer link", "L2", leaveUpload.linkId)
            // A coming-up's flush on L2 lists early, then the leave, and builds early.
            gate = rig.port.gateNextBuild()
            flush = scope.launch { rig.outbox.onLinkRecovered("L2") }
            yield()
            assertEquals(early.id, rig.port.builds.last().id)
        }

        suspend fun finish() {
            gate.complete(Unit)
            flush.join()
            // The flush neither uploaded the leave nor mined a stamp for it.
            assertEquals(1, rig.port.sent.count { it.msgHash == "leave" })
            assertEquals(1, rig.port.builds.count { it.msgHash == "leave" })
            // It goes once on the next coming-up; the join, owed no more, never goes again.
            rig.outbox.onLinkEstablished("L2")
            assertEquals(2, rig.port.sent.count { it.msgHash == "leave" })
            assertEquals(1, rig.port.sent.count { it.msgHash == "join" })
        }
    }

    @Test
    fun aFailureBelongsToTheExactMessageTheJoinDecidedByItsLossAfterTheLeavesLossTakesNoRecordAndCostsTheLeaveNothing() = runBlocking {
        val rig = Rig(this); rig.outbox.start()
        val o = Overlap(this, rig); o.setUp()
        // The leave's upload is reported lost while the flush builds `early`: its record is written.
        o.leaveUpload.lost()
        // Then the old link's loss decides the join, owed no more: it keeps no record, and takes no other's.
        o.joinUpload.lost()
        yield()
        assertNotNull(rig.outbox.unproved.at(o.leave))
        assertNull("a message owed no more keeps no record", rig.outbox.unproved.at(o.join))
        o.finish()
    }

    @Test
    fun theJoinDecidedBeforeTheLeavesLossOrProvedLateAfterItChangesNothingForTheLeave() = runBlocking {
        // Before: the join's loss, then the leave's.
        run {
            val rig = Rig(this); rig.outbox.start()
            val o = Overlap(this, rig); o.setUp()
            o.joinUpload.lost(); yield()
            o.leaveUpload.lost(); yield()
            assertNull(rig.outbox.unproved.at(o.join))
            assertNotNull(rig.outbox.unproved.at(o.leave))
            o.finish()
        }
        // Proved after: the leave's loss, then the join proved (late, over the old link).
        run {
            val rig = Rig(this); rig.outbox.start()
            val o = Overlap(this, rig); o.setUp()
            o.leaveUpload.lost(); yield()
            o.joinUpload.proved(); yield()
            assertNotNull("the older action's proof does not clear the newer's record", rig.outbox.unproved.at(o.leave))
            o.finish()
        }
        // Reported lost before the leave's loss and proved late after it.
        run {
            val rig = Rig(this); rig.outbox.start()
            val o = Overlap(this, rig); o.setUp()
            o.joinUpload.lost(); yield()
            o.leaveUpload.lost(); yield()
            o.joinUpload.proved(); yield()
            assertNotNull(rig.outbox.unproved.at(o.leave))
            o.finish()
        }
    }

    @Test
    fun aJoinReplacedByALeaveIsNeverBuiltByAFlushThatListedItAndTheLeaveKeepsItsRecord() = runBlocking {
        val rig = Rig(this); rig.outbox.start()
        val early = sentCopy("early")
        val join = channel("join", "public.x", "join")
        val leave = channel("leave", "public.x", "leave")
        rig.link("L1")
        rig.outbox.owe(early)
        yield()
        rig.port.sent.single().lost()
        yield()
        rig.port.held = null
        rig.outbox.owe(join)           // owed, link down
        rig.link("L1")
        val gate = rig.port.gateNextBuild()
        val flush = launch { rig.outbox.onLinkEstablished("L1") }  // lists [early, join], builds early (gated)
        yield()
        // The user leaves while that flush builds: the leave replaces the join, goes at once, and is lost.
        rig.outbox.owe(leave)
        yield()
        rig.port.sent.single { it.msgHash == "leave" }.lost()
        yield()
        gate.complete(Unit)
        flush.join()
        // The flush listed the join; it was replaced, so it was never built or sent.
        assertTrue(rig.port.builds.none { it.msgHash == "join" })
        assertEquals(1, rig.port.sent.count { it.msgHash == "leave" })
        assertNull(rig.outbox.unproved.at(join))
        assertNotNull(rig.outbox.unproved.at(leave))
    }

    @Test
    fun aReplacedJoinWhoseBuildThrowsAfterTheLeavesLossTakesNoRecordAndNothingFromTheLeave() = runBlocking {
        val rig = Rig(this); rig.outbox.start()
        val early = sentCopy("early")
        val join = channel("join", "public.x", "join")
        val leave = channel("leave", "public.x", "leave")
        // early failed before; the join is owed with the link down.
        rig.link("L1")
        rig.outbox.owe(early)
        yield()
        rig.port.sent.single().lost()
        yield()
        rig.port.held = null
        rig.outbox.owe(join)
        rig.link("L1")
        // A flush builds early, then the join; the join's build waits (a stamp) and will throw.
        val earlyGate = rig.port.gateNextBuild()
        val joinGate = rig.port.gateNextBuild()
        val flush = launch { rig.outbox.onLinkEstablished("L1") }
        yield()
        earlyGate.complete(Unit)
        yield()
        assertEquals("the join is being built", join.msgHash, rig.port.builds.last().msgHash)
        // The user leaves meanwhile: the leave replaces the join and is lost on L1.
        rig.outbox.owe(leave)
        yield()
        rig.port.sent.single { it.msgHash == "leave" }.lost()
        yield()
        assertNotNull(rig.outbox.unproved.at(leave))
        // Now the join's build throws: it is a failure, but of a message owed no more.
        rig.port.failNextBuild(IllegalStateException("the key cannot be read"))
        joinGate.complete(Unit)
        flush.join()
        assertTrue(rig.lines.info.any { it.contains("owed no more") })
        assertNull("a message owed no more keeps no record", rig.outbox.unproved.at(join))
        assertNotNull("and takes none from the leave", rig.outbox.unproved.at(leave))
        assertEquals(1, rig.port.sent.count { it.msgHash == "leave" })
        rig.outbox.onLinkEstablished("L1")
        assertEquals(2, rig.port.sent.count { it.msgHash == "leave" })
        assertEquals(0, rig.port.sent.count { it.msgHash == "join" })
    }

    @Test
    fun twoSentCopiesInPlaceOfAJoinAndALeaveTheOlderIsStillOwedAndGoesOnceOnTheNewerLinkAtOnce() = run { rig ->
        val older = sentCopy("older")
        val newer = sentCopy("newer")
        rig.link("L1")
        rig.outbox.owe(older)
        yield()
        rig.link("L2")
        rig.outbox.owe(newer)
        yield()
        // The older copy's upload (open on the replaced L1) is lost while L2 is up: once on L2 at once.
        rig.port.sent.single { it.entryId == older.id }.lost()
        yield()
        assertEquals(listOf("L1", "L2"), rig.port.sent.filter { it.entryId == older.id }.map { it.linkId })
        assertEquals(1, rig.port.sent.count { it.entryId == newer.id })
    }

    @Test
    fun theOlderActionsProofDoesNotClearTheNewerActionsFailureRecord() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        rig.link("L1")
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        val joinUpload = rig.port.sent.single()
        rig.link("L2")
        val leave = channel("leave", "public.x", "leave")
        rig.outbox.owe(leave)
        yield()
        val leaveUpload = rig.port.sent.single { it.msgHash == "leave" }
        leaveUpload.lost()
        // The join, proved late over the old link, is owed no more and clears only its own record.
        joinUpload.proved()
        yield()
        assertNotNull(rig.outbox.unproved.at(leave))
        assertNull(rig.outbox.unproved.at(join))
        assertEquals(listOf(leave), rig.outbox.owed())
    }

    @Test
    fun aReplacedActionsFailureRecordGoesWithIt() = run { rig ->
        rig.link("L1")
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        rig.port.sent.single().lost()
        assertNotNull(rig.outbox.unproved.at(join))
        assertEquals(1, rig.outbox.unproved.size)
        // The user leaves: the join is owed no more, and so is its record.
        rig.outbox.owe(channel("leave", "public.x", "leave"))
        assertNull(rig.outbox.unproved.at(join))
        assertEquals(0, rig.outbox.unproved.size)
    }

    @Test
    fun aReplacedActionIsNeverSentEvenWhenItWasReplacedWhileItsUploadWasBeingBuilt() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        rig.link("L1")
        val gate = rig.port.gateNextBuild()
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        // The user leaves while the join's stamp is being mined.
        val leave = channel("leave", "public.x", "leave")
        rig.outbox.owe(leave)
        yield()
        gate.complete(Unit)
        yield()
        // Only the newest action goes: the siblings would otherwise make the change the user undid.
        assertEquals(listOf("leave"), rig.port.sent.map { it.msgHash })
        assertTrue(rig.lines.info.any { it.contains("was replaced by a later action") })
    }

    @Test
    fun atMostOneUploadOfAnEntryIsInFlightAtATime() = run { rig ->
        rig.link("L1")
        rig.outbox.owe(channel("join", "public.x", "join"))
        yield()
        // Established again (a new coming-up) while the first upload is open: not uploaded again.
        rig.outbox.onLinkEstablished("L1")
        rig.outbox.onLinkRecovered("L1")
        assertEquals(1, rig.port.sent.size)
    }

    @Test
    fun anOpenUploadOfAnOlderActionDoesNotHoldBackTheNewerActionOnTheSameChannel() = run { rig ->
        rig.link("L1")
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        assertEquals(1, rig.port.sent.size)
        // The user leaves while no link is up: the leave is owed, the join's upload is still open on L1.
        rig.port.held = null
        val leave = channel("leave", "public.x", "leave")
        rig.outbox.owe(leave)
        // L2 comes up: the leave goes. At most one upload is in flight per MESSAGE, not per channel.
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(listOf("join", "leave"), rig.port.sent.map { it.msgHash })
        assertEquals("L2", rig.port.sent.last().linkId)
    }

    @Test
    fun aFlushWhoseLinkGoesDownMidwayLeavesTheRestForTheNextOne() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        rig.outbox.owe(sentCopy("a"))
        rig.outbox.owe(sentCopy("b"))
        rig.link("L1")
        val gate = rig.port.gateNextBuild()
        val flush = launch { rig.outbox.onLinkEstablished("L1") }
        yield()
        rig.port.held = null
        gate.complete(Unit)
        flush.join()
        // The first was built for a link that is gone: nothing left, nothing decided. Nor the second.
        assertTrue(rig.port.sent.isEmpty())
        assertEquals(2, rig.outbox.owed().size)
        assertTrue(rig.lines.error.isEmpty())
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(2, rig.port.sent.size)
    }

    // ---- the phones' event ----

    @Test
    fun aResourcesFailureDecidesItLikeAPacketsTimeoutAndNeedsNoPacketProofClock() = run { rig ->
        rig.port.resource = true
        rig.link("L1")
        rig.outbox.owe(sentCopy("big"))
        yield()
        assertEquals(1, rig.port.sent.size)
        rig.port.sent[0].lost()
        yield()
        assertTrue(rig.lines.error.any { it.contains("was not proved") && it.contains("its Resource failed") })
        assertEquals(1, rig.outbox.owed().size)
        // Still owed, and not sent again at the failure; the next coming-up carries it.
        assertEquals(1, rig.port.sent.size)
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(listOf("L1", "L2"), rig.port.sent.map { it.linkId })
        // A Resource's own progress has its own §1 watch in the stack, so a long transfer is no violation here.
        rig.clock += 60_000
        rig.port.sent[1].proved()
        yield()
        assertTrue(rig.lines.error.none { it.contains("§1 VIOLATION") })
        assertTrue(rig.lines.info.any { it.contains("propagated to the distro as a resource") })
        assertEquals(emptyList<OwedEntry>(), rig.outbox.owed())
    }

    @Test
    fun aResourcesFailureOnAReplacedLinkWithANewerLinkUpGoesOnceOnTheNewerLinkAtOnce() = run { rig ->
        rig.port.resource = true
        rig.link("L1")
        rig.outbox.owe(channel("join", "public.x", "join"))
        yield()
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(1, rig.port.sent.size)
        // James, 2026-10-04: a Resource upload's own "failed" is the same event as the loss report.
        rig.port.sent[0].lost()
        yield()
        assertEquals(listOf("L1", "L2"), rig.port.sent.map { it.linkId })
    }

    @Test
    fun anEntryOwedWhileTheLinkIsNotActiveBuildsNothingAndSaysNothingWentWrong() = run { rig ->
        rig.link("L1", active = false)
        // Not ACTIVE when the user acts: it waits (and is never started).
        rig.outbox.owe(sentCopy("copy"))
        yield()
        assertTrue(rig.port.builds.isEmpty())
        assertTrue(rig.lines.error.isEmpty())
    }

    // ---- an upload that never left the device (James, 2026-10-06) ----
    //
    // Mirrors Retichat-js 80c74ad / 136d13d (distro_channels, distro_outbox and exchange_truth tests). The port
    // not taking the packet while the link is up is "it could not be queued": nothing was sent, which is no
    // failure. The event is an interface coming back online, never a timer and never a failure.

    /** The owed [entry] was handed over while no interface could carry it: refused, recorded never left, nothing said as a failure. */
    private suspend fun neverLeft(rig: Rig, entry: OwedEntry) {
        rig.link("L1")
        rig.port.cannotQueue = true
        rig.outbox.owe(entry)
        yield()
    }

    @Test
    fun anUploadWhosePacketCouldNotBeQueuedIsNoFailureAndGoesOnceWhenAnInterfaceComesBackOnline() = run { rig ->
        val join = channel("join", "public.x", "join")
        neverLeft(rig, join)
        // Nothing left, and it is no failure: no error, no warning, no failure record, no §1 watch to speak of.
        assertEquals(1, rig.port.refused)
        assertTrue(rig.port.sent.isEmpty())
        assertTrue(rig.lines.error.isEmpty())
        assertTrue(rig.lines.warn.isEmpty())
        assertEquals(0, rig.outbox.unproved.size)
        assertTrue(rig.outbox.unproved.neverLeft(join))
        assertTrue(rig.lines.info.any { it.contains("never left the device") && it.contains("no failure") })
        assertEquals(listOf(join), rig.outbox.owed())
        // Owed, and nothing carries it by itself: no timer, no poll.
        repeat(5) { yield() }
        assertTrue(rig.port.sent.isEmpty())
        // The interface comes back online: it goes, once, the same message, on the link that is up.
        rig.port.cannotQueue = false
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(listOf("L1"), rig.port.sent.map { it.linkId })
        assertEquals(listOf("join"), rig.port.sent.map { it.msgHash })
        // An upload of it began, so it is no longer recorded never left, and it is no failure either.
        assertEquals(0, rig.outbox.unproved.neverLeftSize)
        rig.port.sent[0].proved()
        yield()
        assertEquals(emptyList<OwedEntry>(), rig.outbox.owed())
        assertTrue(rig.store.rows.isEmpty())
        // Proved and owed no more: the next edge sends nothing.
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(1, rig.port.sent.size)
        assertTrue(rig.lines.error.isEmpty())
    }

    @Test
    fun aLossAfterThePacketLeftDoesNotGoOnTheInterfacesReturnAndGoesAtTheNextComingUp() = run { rig ->
        rig.link("L1")
        rig.outbox.owe(channel("join", "public.x", "join"))
        yield()
        rig.port.sent[0].lost()
        yield()
        assertTrue(rig.lines.error.any { it.contains("was not proved") && it.contains("still owed") })
        assertEquals(1, rig.outbox.unproved.size)
        assertEquals(0, rig.outbox.unproved.neverLeftSize)
        // The packet left and was lost: an interface coming online sends nothing of it (mutation: the edge uploads every owed entry).
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(1, rig.port.sent.size)
        // The next coming-up carries it.
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(listOf("L1", "L2"), rig.port.sent.map { it.linkId })
    }

    @Test
    fun theInterfacesReturnSendsOnlyWhatNeverLeftAndReleasesNoFailure() = run { rig ->
        rig.link("L1")
        val failed = sentCopy("failed")
        rig.outbox.owe(failed)
        yield()
        rig.port.sent[0].lost()
        yield()
        val never = sentCopy("never")
        rig.port.cannotQueue = true
        rig.outbox.owe(never)
        yield()
        rig.port.cannotQueue = false
        val ups = rig.outbox.propComingUps
        rig.outbox.onInterfaceOnline("tcp0")
        // Only the one that never left; the failed one waits for a coming-up. It is no coming-up, and counts none.
        assertEquals(listOf("failed", "never"), rig.port.sent.map { it.msgHash })
        assertEquals(ups, rig.outbox.propComingUps)
        assertEquals(1, rig.outbox.unproved.size)
        rig.port.sent[1].proved()
        yield()
        assertEquals(listOf(failed), rig.outbox.owed())
    }

    @Test
    fun aNeverLeftUploadGoesAtAComingUpTooAndTheInterfacesReturnAfterThatSendsNothing() = run { rig ->
        val join = channel("join", "public.x", "join")
        neverLeft(rig, join)
        rig.port.cannotQueue = false
        // No failure record holds it back: a flush at any coming-up sends it, like a message never uploaded.
        rig.outbox.onLinkRecovered("L1")
        assertEquals(listOf("join"), rig.port.sent.map { it.msgHash })
        assertEquals(0, rig.outbox.unproved.neverLeftSize)
        // And the edge that follows finds nothing recorded never left, and no second upload.
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(1, rig.port.sent.size)
    }

    @Test
    fun aLossAfterANeverLeftUploadLeftIsAFailureAgainThatWaitsForTheNextComingUp() = run { rig ->
        neverLeft(rig, sentCopy("copy"))
        rig.port.cannotQueue = false
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(1, rig.port.sent.size)
        // This time the packet left and was lost: the record of its never leaving ended when the upload began (mutation: kept).
        rig.port.sent[0].lost()
        yield()
        assertTrue(rig.lines.error.any { it.contains("was not proved") })
        assertEquals(1, rig.outbox.unproved.size)
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(1, rig.port.sent.size)
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(listOf("L1", "L2"), rig.port.sent.map { it.linkId })
    }

    @Test
    fun twoInterfaceReturnsInARowAndAFlushAlreadyUnderWayCarryOneUpload() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        neverLeft(rig, channel("join", "public.x", "join"))
        rig.port.cannotQueue = false
        val gate = rig.port.gateNextBuild()
        val first = launch { rig.outbox.onInterfaceOnline("tcp0") }
        yield()
        // The first return's upload is being built; a second return and a coming-up flush find it taken.
        val second = launch { rig.outbox.onInterfaceOnline("backbone") }
        val flush = launch { rig.outbox.onLinkEstablished("L1") }
        yield()
        gate.complete(Unit)
        listOf(first, second, flush).forEach { it.join() }
        assertEquals(1, rig.port.sent.size)
        assertEquals(listOf("join"), rig.port.sent.map { it.msgHash })
    }

    @Test
    fun aMessageReplacedOrDroppedWithItsDistroLeavesNoNeverLeftRecordAndSendsNothing() = run { rig ->
        val join = channel("join", "public.x", "join")
        neverLeft(rig, join)
        assertEquals(1, rig.outbox.unproved.neverLeftSize)
        // Replaced by a later action under the same id: the join's record goes with it, the leave's stands.
        val leave = channel("leave", "public.x", "leave")
        rig.outbox.owe(leave)
        yield()
        assertFalse(rig.outbox.unproved.neverLeft(join))
        assertTrue(rig.outbox.unproved.neverLeft(leave))
        assertEquals(1, rig.outbox.unproved.neverLeftSize)
        // The distro is given up: the membership message is dropped, and with it the record.
        rig.distro = null
        rig.outbox.onDistroChanged()
        assertEquals(0, rig.outbox.unproved.neverLeftSize)
        rig.port.cannotQueue = false
        rig.outbox.onInterfaceOnline("tcp0")
        assertTrue(rig.port.sent.isEmpty())
    }

    @Test
    fun noLinkAStaleLinkAndAStoppedStackSendNothingOnTheInterfacesReturnAndTheRecordStands() = run { rig ->
        val copy = sentCopy("copy")
        neverLeft(rig, copy)
        rig.port.cannotQueue = false
        // No link held: its coming-up sends it with everything owed.
        rig.port.held = null
        rig.outbox.onInterfaceOnline("tcp0")
        // A STALE (or coming-up) link: the same.
        rig.link("L1", active = false)
        rig.outbox.onInterfaceOnline("tcp0")
        assertTrue(rig.port.sent.isEmpty())
        assertTrue(rig.outbox.unproved.neverLeft(copy))
        // The stack stopped: nothing is sent.
        rig.link("L1")
        rig.outbox.onStop()
        rig.outbox.onInterfaceOnline("tcp0")
        assertTrue(rig.port.sent.isEmpty())
        // Running again, with the link up: it goes.
        rig.outbox.start()
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(listOf("copy"), rig.port.sent.map { it.msgHash })
    }

    @Test
    fun aSentCopyGoesTheSameWayAsAMembershipMessage() = run { rig ->
        val copy = sentCopy("copy")
        neverLeft(rig, copy)
        assertTrue(rig.lines.error.isEmpty())
        assertEquals(0, rig.outbox.unproved.size)
        rig.port.cannotQueue = false
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(listOf(copy.id), rig.port.sent.map { it.entryId })
        rig.port.sent[0].proved()
        yield()
        assertEquals(emptyList<OwedEntry>(), rig.outbox.owed())
    }

    @Test
    fun anInterfaceThatStillCannotCarryItRefusesAgainWithNoLoopAndNoFailure() = run { rig ->
        val join = channel("join", "public.x", "join")
        neverLeft(rig, join)
        // The edge comes while the packet still cannot be queued: refused again, recorded again, no loop.
        rig.outbox.onInterfaceOnline("tcp0")
        repeat(5) { yield() }
        assertEquals(2, rig.port.refused)
        assertTrue(rig.outbox.unproved.neverLeft(join))
        assertTrue(rig.lines.error.isEmpty())
        assertTrue(rig.lines.warn.isEmpty())
        assertEquals(0, rig.outbox.unproved.size)
        // The next edge, with the interface carrying it, sends it.
        rig.port.cannotQueue = false
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(1, rig.port.sent.size)
    }

    @Test
    fun aBuildAbandonedBecauseTheLinkWentDownIsNotRecordedNeverLeft() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        rig.link("L1")
        val gate = rig.port.gateNextBuild()
        val owing = launch { rig.outbox.owe(sentCopy("copy")) }
        yield()
        rig.port.held = null
        gate.complete(Unit)
        owing.join()
        repeat(3) { yield() }
        // Nothing was handed to a link, so nothing never left: the link's coming-up carries it (§17.12).
        assertEquals(0, rig.outbox.unproved.neverLeftSize)
        assertTrue(rig.port.sent.isEmpty())
        rig.link("L2")
        rig.outbox.onInterfaceOnline("tcp0")
        assertTrue(rig.port.sent.isEmpty())
        rig.outbox.onLinkEstablished("L2")
        assertEquals(1, rig.port.sent.size)
    }


    // ---- Stage 0: a Resource upload that never left (DISTRO-SYNC-PROOF-DESIGN.md §5.3, §13.1) ----
    //
    // DESIGN_PRINCIPLES §3, James 2026-10-06, and for a Resource 2026-10-10: a Resource upload whose first
    // advertisement no interface could carry never left the device. The stack cancels it there and keeps the
    // link (rust/retichat-jni nativePropagationUpload; PropagationJniContractTest pins that no teardown comes
    // with it) and tells onNeverLeft. Every proven upload is a Resource (its claim puts it over the link MDU),
    // so the 2026-10-06 short drop now happens to Resources.

    /** The owed [entry] went as a Resource on L1, which the stack then found never left. */
    private suspend fun resourceNeverLeft(rig: Rig, entry: OwedEntry): Sent {
        rig.link("L1")
        rig.port.resource = true
        rig.outbox.owe(entry)
        yield()
        val first = rig.port.uploadsOf(entry.id).single()
        first.neverLeft()
        yield()
        return first
    }

    @Test
    fun theShortDropForAResourceUploadIsNeverLeftWithNoTeardownAndGoesOnceAtTheInterfacesUpEdge() = run { rig ->
        val copy = sentCopy("copy")
        val first = resourceNeverLeft(rig, copy)
        // Nothing was sent, so it is no failure: no error the client shows, no warning, no failure record.
        assertTrue(rig.lines.error.isEmpty())
        assertTrue(rig.lines.warn.isEmpty())
        assertEquals(0, rig.outbox.unproved.size)
        assertTrue(rig.outbox.unproved.neverLeft(copy))
        assertTrue(rig.lines.info.any { it.contains("never left the device") && it.contains("Resource") && it.contains("no failure") })
        // The link is kept: the outbox asks for no teardown and the link it went on is still the one held.
        assertEquals(HeldLink("L1", true), rig.port.held)
        assertEquals(listOf(copy), rig.outbox.owed())
        // Nothing carries it by itself: no timer, no poll.
        repeat(5) { yield() }
        assertEquals(1, rig.port.sent.size)
        // The interface comes back online: it goes once, on the link that is up, with no coming-up.
        rig.port.upEdges++
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(listOf("L1", "L1"), rig.port.sent.map { it.linkId })
        assertEquals(0, rig.outbox.unproved.neverLeftSize)
        assertEquals(0L, rig.outbox.propComingUps)
        // The cancelled Resource's own conclusion, should one still come, decides nothing.
        first.lost()
        first.proved()
        yield()
        assertTrue(rig.lines.error.isEmpty())
        assertEquals(listOf(copy), rig.outbox.owed())
        rig.port.sent[1].proved()
        yield()
        assertEquals(emptyList<OwedEntry>(), rig.outbox.owed())
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(2, rig.port.sent.size)
    }

    @Test
    fun aResourceThatLeftAndThenFailsIsStillLostAndWaitsForTheNextComingUp() = run { rig ->
        rig.link("L1")
        rig.port.resource = true
        val copy = sentCopy("copy")
        rig.outbox.owe(copy)
        yield()
        // Its advertisement left; then the Resource failed (ruling of 2026-10-04): a loss.
        rig.port.sent.single().lost()
        yield()
        assertTrue(rig.lines.error.any { it.contains("its Resource failed") })
        assertEquals(1, rig.outbox.unproved.size)
        assertFalse(rig.outbox.unproved.neverLeft(copy))
        // An interface's up-edge is no coming-up: a loss is not sent on it.
        rig.port.upEdges++
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(1, rig.port.sent.size)
        // The next coming-up sends it.
        rig.link("L2")
        rig.outbox.onLinkEstablished("L2")
        assertEquals(listOf("L1", "L2"), rig.port.sent.map { it.linkId })
    }

    @Test
    fun anUpEdgeThatCameBeforeTheResourcesNeverLeftWasRecordedIsNotMissed() = run { rig ->
        rig.link("L1")
        rig.port.resource = true
        val copy = sentCopy("copy")
        rig.outbox.owe(copy)
        yield()
        assertEquals(listOf(0L), rig.port.marks)
        // The interface comes back while the never-left is on its way to the outbox: its up-edge finds nothing
        // recorded, and the link is still up, so no coming-up follows.
        rig.port.upEdges++
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(1, rig.port.sent.size)
        // Then never-left is told: recorded first, then the mark asked. An interface came up since: that up-edge
        // was this upload's, and it goes now, once, on the link that is up.
        rig.port.sent[0].neverLeft()
        yield()
        assertEquals(listOf("L1", "L1"), rig.port.sent.map { it.linkId })
        assertEquals(listOf(0L, 1L), rig.port.marks)
        assertEquals(0, rig.outbox.unproved.neverLeftSize)
        assertTrue(rig.lines.info.any { it.contains("that up-edge was its own") })
        assertTrue(rig.lines.error.isEmpty())
        // While that upload is open no later edge sends it again.
        rig.port.upEdges++
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(2, rig.port.sent.size)
    }

    @Test
    fun withNoUpEdgeSinceItsMarkANeverLeftUploadWaitsAndAnUpEdgeAlreadyHeardDoesNotSendItTwice() = run { rig ->
        rig.link("L1")
        rig.port.resource = true
        val copy = sentCopy("copy")
        rig.outbox.owe(copy)
        yield()
        rig.port.sent[0].neverLeft()
        yield()
        // No interface came up since the mark: it waits for the next up-edge.
        assertEquals(1, rig.port.sent.size)
        assertTrue(rig.outbox.unproved.neverLeft(copy))
        // The up-edge and its listener: one send. The mark of that send is the new count.
        rig.port.upEdges++
        rig.outbox.onInterfaceOnline("tcp0")
        assertEquals(2, rig.port.sent.size)
        assertEquals(listOf(0L, 1L), rig.port.marks)
        // That one never leaves either, with no edge since its mark: it waits again, no loop.
        rig.port.sent[1].neverLeft()
        repeat(5) { yield() }
        assertEquals(2, rig.port.sent.size)
        assertTrue(rig.outbox.unproved.neverLeft(copy))
    }

    @Test
    fun anUpEdgeWhileAPacketIsRefusedIsNotMissedAndEachSendAnswersOneUpEdge() = run { rig ->
        rig.link("L1")
        rig.port.cannotQueue = true
        // The interface comes back after the mark and before the port's answer: refused, and its up-edge was
        // this upload's. It goes again at once, and is carried.
        rig.port.upEdgeDuringNextUpload = true
        rig.port.onRefused = { rig.port.cannotQueue = false }
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        yield()
        assertEquals(1, rig.port.refused)
        assertEquals(listOf("join"), rig.port.sent.map { it.msgHash })
        assertEquals(0, rig.outbox.unproved.neverLeftSize)
        assertTrue(rig.lines.error.isEmpty())
        rig.port.sent[0].proved()
        yield()
        assertTrue(rig.outbox.owed().isEmpty())
    }

    @Test
    fun anUpEdgeThatStillCannotCarryAPacketSendsItOnceMoreAndNoLoopFollows() = run { rig ->
        rig.link("L1")
        rig.port.cannotQueue = true
        rig.port.upEdgeDuringNextUpload = true
        val join = channel("join", "public.x", "join")
        rig.outbox.owe(join)
        repeat(5) { yield() }
        // The up-edge since the first mark sent it once more; that send's mark saw no edge, so it waits.
        assertEquals(2, rig.port.refused)
        assertEquals(listOf(0L, 1L), rig.port.marks)
        assertTrue(rig.outbox.unproved.neverLeft(join))
        assertTrue(rig.port.sent.isEmpty())
        assertTrue(rig.lines.error.isEmpty())
        assertTrue(rig.lines.warn.isEmpty())
    }

    @Test
    fun aNeverLeftAfterTheStackStoppedDecidesNothing() = run { rig ->
        rig.link("L1")
        rig.port.resource = true
        val copy = sentCopy("copy")
        rig.outbox.owe(copy)
        yield()
        rig.outbox.onStop()
        yield()
        rig.port.upEdges++
        rig.port.sent[0].neverLeft()
        yield()
        // The stop decided it (cut): no never-left record, nothing sent, the failure's record stands.
        assertEquals(0, rig.outbox.unproved.neverLeftSize)
        assertEquals(1, rig.outbox.unproved.size)
        assertEquals(1, rig.port.sent.size)
    }

    // ---- Stage 1: the distro sync proof (RFed SPEC §17.13; DISTRO-SYNC-PROOF-DESIGN.md §5, §13.1) ----

    private val rfedNode = "a1".repeat(16)
    private val overrideNode = "b2".repeat(16)

    private fun sealed(entry: OwedEntry, tag: Int = 1) = OwedEntry(
        id = entry.id, kind = entry.kind, distro = entry.distro, distroKey = entry.distroKey, msgHash = entry.msgHash,
        packed = entry.packed, label = entry.label, op = entry.op, name = entry.name, atMs = entry.atMs, toHex = entry.toHex,
        sealed = ByteArray(200) { (it + tag).toByte() }, syncSig = ByteArray(64) { (tag * 3).toByte() },
    )

    @Test
    fun bothKindsAreKeptWithTheirSealedBytesAndSignatureBeforeAnythingCanYield() = runBlocking {
        val rig = Rig(this)
        rig.outbox.start()
        rig.link("L1")
        for (entry in listOf(sealed(sentCopy("copy")), sealed(channel("join", "public.x", "join"), tag = 2))) {
            val gate = rig.port.gateNextBuild()
            val owing = launch { rig.outbox.owe(entry) }
            yield()
            // Written, with the proof, while its build waits: nothing has been uploaded yet.
            val row = rig.store.rows[entry.id]!!
            assertTrue(row.isSealed)
            assertTrue(row.sealed!!.contentEquals(entry.sealed))
            assertTrue(row.syncSig!!.contentEquals(entry.syncSig))
            assertTrue(rig.port.uploadsOf(entry.id).isEmpty())
            gate.complete(Unit)
            owing.join()
            repeat(3) { yield() }
            // The build is handed the same entry, proof and all.
            assertTrue(rig.port.builds.last().sealed!!.contentEquals(entry.sealed))
            assertEquals(1, rig.port.uploadsOf(entry.id).size)
        }
    }

    @Test
    fun afterTheDistroIsForgottenASentCopyStillBuildsWithItsClaim() = run { rig ->
        val copy = sealed(sentCopy("copy"))
        val join = sealed(channel("join", "public.x", "join"), tag = 2)
        rig.outbox.owe(copy)
        rig.outbox.owe(join)
        // The device forgets its distro: the membership message goes, the sent-copy is still owed (James, 2026-10-03).
        rig.distro = null
        rig.outbox.onDistroChanged()
        assertEquals(listOf(copy), rig.outbox.owed())
        rig.link("L1")
        rig.outbox.onLinkEstablished("L1")
        val built = rig.port.builds.single()
        assertEquals(copy, built)
        assertTrue(built.sealed!!.contentEquals(copy.sealed))
        assertTrue(built.syncSig!!.contentEquals(copy.syncSig))
        // Its proof does not depend on the distro held now: for the RFed node it carries the claim.
        assertEquals(SyncProofGate.Build.Sealed(withClaim = true), SyncProofGate.buildFor(built, rfedNode, rfedNode))
    }

    @Test
    fun theClaimGoesOnlyToTheRfedNodesOwnPropagationDestination() {
        val copy = sealed(sentCopy("copy"))
        assertEquals(SyncProofGate.Build.Sealed(withClaim = true), SyncProofGate.buildFor(copy, rfedNode, rfedNode))
        assertEquals(SyncProofGate.Build.Sealed(withClaim = true), SyncProofGate.buildFor(copy, rfedNode.uppercase(), " $rfedNode "))
        // An override (an RFed override or a manual hash): LXMF's two-element envelope, the same sealed bytes.
        assertEquals(SyncProofGate.Build.Sealed(withClaim = false), SyncProofGate.buildFor(copy, overrideNode, rfedNode))
        // No RFed node configured, or one that is not a hash: no claim.
        assertEquals(SyncProofGate.Build.Sealed(withClaim = false), SyncProofGate.buildFor(copy, rfedNode, null))
        assertEquals(SyncProofGate.Build.Sealed(withClaim = false), SyncProofGate.buildFor(copy, "", ""))
        assertEquals(SyncProofGate.Build.Sealed(withClaim = false), SyncProofGate.buildFor(copy, "a1", "a1"))
        // A membership message the same way (James, 2026-10-10: both kinds carry the proof).
        assertEquals(SyncProofGate.Build.Sealed(withClaim = true), SyncProofGate.buildFor(sealed(channel("leave", "public.x", "l")), rfedNode, rfedNode))
    }

    @Test
    fun aLegacyEntryUsesTheOldBuilderForEveryNode() {
        val legacy = sentCopy("copy")
        assertFalse(legacy.isSealed)
        for (node in listOf(rfedNode, overrideNode)) {
            assertEquals(SyncProofGate.Build.Legacy, SyncProofGate.buildFor(legacy, node, rfedNode))
        }
        // One of the two alone is no proof.
        val half = OwedEntry(
            id = legacy.id, kind = legacy.kind, distro = legacy.distro, distroKey = legacy.distroKey,
            msgHash = legacy.msgHash, packed = legacy.packed, label = legacy.label, toHex = legacy.toHex,
            sealed = ByteArray(40),
        )
        assertFalse(half.isSealed)
        assertEquals(SyncProofGate.Build.Legacy, SyncProofGate.buildFor(half, rfedNode, rfedNode))
    }

    @Test
    fun aLegacyEntryAndASealedOneGoTheSameWayThroughTheOutbox() = run { rig ->
        val legacy = sentCopy("old")
        val proven = sealed(sentCopy("new"))
        rig.outbox.owe(legacy)
        rig.outbox.owe(proven)
        rig.link("L1")
        rig.outbox.onLinkEstablished("L1")
        assertEquals(listOf(legacy, proven), rig.port.builds)
        assertEquals(listOf(false, true), rig.port.builds.map { it.isSealed })
        rig.port.sent.forEach { it.proved() }
        yield()
        assertTrue(rig.outbox.owed().isEmpty())
    }

    // ---- UnprovedUploads' never-left records ----

    @Test
    fun aNeverLeftRecordIsOneMessagesHasNoComingUpAndHoldsBackNoFlush() {
        val unproved = UnprovedUploads()
        val join = channel("join", "public.x", "AAAA")
        val leave = channel("leave", "public.x", "CCCC")
        val other = sentCopy("AAAA")
        assertFalse(unproved.neverLeft(leave))
        unproved.recordNeverLeft(leave)
        assertEquals(listOf(true, false, false), listOf(unproved.neverLeft(leave), unproved.neverLeft(join), unproved.neverLeft(other)))
        // No coming-up is recorded, so no flush leaves it for the next one.
        assertEquals(listOf(null, false, 0), listOf(unproved.at(leave), unproved.since(leave, 0), unproved.size))
        unproved.recordNeverLeft(leave)
        unproved.recordNeverLeft(join)
        unproved.recordNeverLeft(other)
        assertEquals(3, unproved.neverLeftSize)
        unproved.clearNeverLeft(join)
        assertEquals(listOf(false, true, true, 2), listOf(unproved.neverLeft(join), unproved.neverLeft(leave), unproved.neverLeft(other), unproved.neverLeftSize))
        unproved.clearNeverLeft(join)
        assertEquals(2, unproved.neverLeftSize)
        unproved.forget(other)
        assertEquals(listOf(true, false, 1), listOf(unproved.neverLeft(leave), unproved.neverLeft(other), unproved.neverLeftSize))
        unproved.forget(leave)
        assertEquals(0, unproved.neverLeftSize)
    }

    @Test
    fun forgetEndsAMessagesFailureRecordAndItsNeverLeftRecordTogetherAndClearNeverLeftEndsTheSecondAlone() {
        val unproved = UnprovedUploads()
        val message = channel("join", "public.x", "AAAA")
        unproved.record(message, 3)
        unproved.recordNeverLeft(message)
        unproved.clearNeverLeft(message)
        assertEquals(listOf(false, 3L, 1), listOf(unproved.neverLeft(message), unproved.at(message), unproved.size))
        unproved.recordNeverLeft(message)
        unproved.forget(message)
        assertEquals(listOf(false, null, 0, 0), listOf(unproved.neverLeft(message), unproved.at(message), unproved.size, unproved.neverLeftSize))
        // A message with only a never-left record is forgotten too.
        unproved.recordNeverLeft(message)
        unproved.forget(message)
        assertEquals(0, unproved.neverLeftSize)
    }
}
