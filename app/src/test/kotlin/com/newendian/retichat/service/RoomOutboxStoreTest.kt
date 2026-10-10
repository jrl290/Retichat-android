package com.newendian.retichat.service

import com.newendian.retichat.data.db.dao.DistroOutboxDao
import com.newendian.retichat.data.db.entity.DistroOutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The distro outbox's Room storage: order, replacement, the conditional removal and what a refused write says. */
class RoomOutboxStoreTest {
    /** Room's DAO as an in-memory table, the SQL of [DistroOutboxDao] written out. */
    private class FakeDao : DistroOutboxDao {
        val rows = LinkedHashMap<String, DistroOutboxEntity>()
        var failWrites = false
        override suspend fun all(): List<DistroOutboxEntity> = rows.values.sortedBy { it.seq }
        override suspend fun upsert(entry: DistroOutboxEntity) {
            if (failWrites) throw IllegalStateException("database or disk is full")
            rows[entry.id] = entry
        }
        override suspend fun remove(id: String, msgHash: String): Int =
            if (rows[id]?.msgHash == msgHash) { rows.remove(id); 1 } else 0
        override suspend fun removeId(id: String): Int = if (rows.remove(id) != null) 1 else 0
    }

    private class Lines : OutboxLog {
        val info = mutableListOf<String>()
        val warn = mutableListOf<String>()
        val error = mutableListOf<String>()
        override fun info(message: String) { info += message }
        override fun warn(message: String) { warn += message }
        override fun error(message: String) { error += message }
    }

    private fun channel(id: String, hash: String) = OwedEntry(
        id = id, kind = OwedKind.CHANNEL, distro = "d".repeat(32), distroKey = "1".repeat(128),
        msgHash = hash, packed = byteArrayOf(1, 2, 3, hash.length.toByte()), label = "the join of #x (§17.12)",
        op = "join", name = "public.x", atMs = 1_767_225_600_000,
    )

    private fun sent(hash: String, sealed: ByteArray? = null, syncSig: ByteArray? = null) = OwedEntry(
        id = "sent:$hash", kind = OwedKind.SENT, distro = "d".repeat(32), distroKey = "1".repeat(128),
        msgHash = hash, packed = byteArrayOf(9), label = "the sent-copy (§17.11)", toHex = "c".repeat(32),
        sealed = sealed, syncSig = syncSig,
    )

    @Test
    fun anEntryRoundTripsThroughARowWhole() {
        val c = channel("channel:aa", "h1")
        val back = RoomOutboxStore.toEntry(RoomOutboxStore.toRow(c, 5))!!
        assertEquals(c, back)
        assertArrayEquals(c.packed, back.packed)
        assertEquals(listOf("join", "public.x", 1_767_225_600_000, null), listOf(back.op, back.name, back.atMs, back.toHex))
        val s = sent("h2")
        val sBack = RoomOutboxStore.toEntry(RoomOutboxStore.toRow(s, 6))!!
        assertEquals(OwedKind.SENT, sBack.kind)
        assertEquals("c".repeat(32), sBack.toHex)
        assertNull(sBack.op)
    }

    @Test
    fun theSealedBytesAndSyncSignatureRoundTripAndALegacyRowHasNeither() {
        // DISTRO-SYNC-PROOF-DESIGN.md §7: the proof is stored with the entry, before any upload.
        val c = sent("h1", sealed = ByteArray(200) { it.toByte() }, syncSig = ByteArray(64) { 5 })
        val row = RoomOutboxStore.toRow(c, 3)
        assertArrayEquals(c.sealed, row.sealed)
        assertArrayEquals(c.syncSig, row.syncSig)
        val back = RoomOutboxStore.toEntry(row)!!
        assertTrue(back.isSealed)
        assertArrayEquals(c.sealed, back.sealed)
        assertArrayEquals(c.syncSig, back.syncSig)
        // A row from before database 15 (or one whose seal failed) has neither, and builds the legacy way.
        val legacy = RoomOutboxStore.toEntry(RoomOutboxStore.toRow(sent("h2"), 4))!!
        assertFalse(legacy.isSealed)
        assertNull(legacy.sealed)
        assertNull(legacy.syncSig)
    }

    @Test
    fun aRowWithOnlyOneHalfOfTheProofIsReadAsLegacy() {
        // Sealed bytes without their signature (or the reverse) cannot make a claim RFed accepts:
        // the entry goes the old way rather than not at all.
        val whole = RoomOutboxStore.toRow(sent("h3", sealed = byteArrayOf(1, 2), syncSig = ByteArray(64)), 1)
        for (half in listOf(whole.copy(sealed = null), whole.copy(syncSig = null))) {
            val back = RoomOutboxStore.toEntry(half)!!
            assertFalse(back.isSealed)
            assertNull(back.sealed)
            assertNull(back.syncSig)
            assertArrayEquals(byteArrayOf(9), back.packed)
        }
    }

    @Test
    fun aRowWithOnlyOneHalfOfTheProofIsSaidAsAWarningWhenLoadedAndAWholeOrLegacyRowSaysNothing() = runBlocking {
        // Every drop speaks: the proof is dropped and the upload wakes the distro's devices, so the load says why.
        val dao = FakeDao()
        val whole = RoomOutboxStore.toRow(sent("whole", sealed = byteArrayOf(1, 2), syncSig = ByteArray(64)), 1)
        dao.rows[whole.id] = whole
        val legacy = RoomOutboxStore.toRow(sent("legacy"), 2)
        dao.rows[legacy.id] = legacy
        val noSig = RoomOutboxStore.toRow(sent("nosig", sealed = byteArrayOf(3), syncSig = ByteArray(64)), 3).copy(syncSig = null)
        dao.rows[noSig.id] = noSig
        val noSealed = RoomOutboxStore.toRow(sent("nosealed", sealed = byteArrayOf(4), syncSig = ByteArray(64)), 4).copy(sealed = null)
        dao.rows[noSealed.id] = noSealed
        val lines = Lines()
        val loaded = RoomOutboxStore(dao, lines).loadAll()
        // All four are still owed: the half-proof rows go the legacy way rather than not at all.
        assertEquals(listOf("sent:whole", "sent:legacy", "sent:nosig", "sent:nosealed"), loaded.map { it.id })
        assertEquals(listOf(true, false, false, false), loaded.map { it.isSealed })
        assertEquals(2, lines.warn.size)
        assertTrue(lines.info.isEmpty() && lines.error.isEmpty())
        val (first, second) = lines.warn
        assertTrue(first, first.contains("(sent:nosig)") && first.contains("sealed bytes without their sync signature"))
        assertTrue(second, second.contains("(sent:nosealed)") && second.contains("sync signature without its sealed bytes"))
        for (line in lines.warn) {
            assertTrue(line, line.contains("the sent-copy (§17.11)"))
            assertTrue(line, line.contains("without the sync proof") && line.contains("wakes the distro's devices"))
        }
        assertNull(RoomOutboxStore.halfProof(whole))
        assertNull(RoomOutboxStore.halfProof(legacy))
        // A row of a kind this build does not know is left alone, and its proof is not spoken of.
        dao.rows.clear()
        dao.rows[noSig.id] = noSig.copy(kind = "from-the-future")
        val quiet = Lines()
        assertTrue(RoomOutboxStore(dao, quiet).loadAll().isEmpty())
        assertTrue(quiet.warn.isEmpty())
    }

    @Test
    fun aRowOfAKindThisBuildDoesNotKnowIsLeftAloneAndNeverUploaded() {
        val row = RoomOutboxStore.toRow(channel("channel:aa", "h1"), 1).copy(kind = "from-the-future")
        assertNull(RoomOutboxStore.toEntry(row))
    }

    @Test
    fun entriesComeBackOldestFirstAndAReplacementGoesToTheEnd() = runBlocking {
        val dao = FakeDao()
        val store = RoomOutboxStore(dao, Lines())
        assertTrue(store.put(channel("channel:aa", "join")))
        assertTrue(store.put(sent("copy")))
        assertTrue(store.put(channel("channel:aa", "leave")))
        // A later run loads them in the order they were owed: the leave replaced the join and went last.
        val later = RoomOutboxStore(dao, Lines())
        assertEquals(listOf("sent:copy", "channel:aa"), later.loadAll().map { it.id })
        assertEquals("leave", later.loadAll().last().msgHash)
        // It goes on from the highest number, never reusing one.
        later.put(sent("newer"))
        assertEquals(listOf("sent:copy", "channel:aa", "sent:newer"), later.loadAll().map { it.id })
    }

    @Test
    fun aMessageProvedIsRemovedOnlyIfItIsStillTheOneStored() = runBlocking {
        val dao = FakeDao()
        val store = RoomOutboxStore(dao, Lines())
        store.put(channel("channel:aa", "join"))
        store.put(channel("channel:aa", "leave"))
        // The join's proof arrives late: the leave under the same id stays owed.
        store.remove("channel:aa", "join")
        assertEquals(listOf("leave"), store.loadAll().map { it.msgHash })
        store.remove("channel:aa", "leave")
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun aRefusedWriteIsFalseAndTheReplacedRowCanBeTakenOutAnyway() = runBlocking {
        val dao = FakeDao()
        val store = RoomOutboxStore(dao, Lines())
        assertTrue(store.put(channel("channel:aa", "join")))
        dao.failWrites = true
        assertFalse(store.put(channel("channel:aa", "leave")))
        // Storage still holds the join, which is owed no more: it goes, by a write no larger than what is held.
        assertEquals(listOf("join"), store.loadAll().map { it.msgHash })
        store.removeId("channel:aa")
        assertTrue(store.loadAll().isEmpty())
    }
}
