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

    private fun channel(id: String, hash: String) = OwedEntry(
        id = id, kind = OwedKind.CHANNEL, distro = "d".repeat(32), distroKey = "1".repeat(128),
        msgHash = hash, packed = byteArrayOf(1, 2, 3, hash.length.toByte()), label = "the join of #x (§17.12)",
        op = "join", name = "public.x", atMs = 1_767_225_600_000,
    )

    private fun sent(hash: String) = OwedEntry(
        id = "sent:$hash", kind = OwedKind.SENT, distro = "d".repeat(32), distroKey = "1".repeat(128),
        msgHash = hash, packed = byteArrayOf(9), label = "the sent-copy (§17.11)", toHex = "c".repeat(32),
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
    fun aRowOfAKindThisBuildDoesNotKnowIsLeftAloneAndNeverUploaded() {
        val row = RoomOutboxStore.toRow(channel("channel:aa", "h1"), 1).copy(kind = "from-the-future")
        assertNull(RoomOutboxStore.toEntry(row))
    }

    @Test
    fun entriesComeBackOldestFirstAndAReplacementGoesToTheEnd() = runBlocking {
        val dao = FakeDao()
        val store = RoomOutboxStore(dao)
        assertTrue(store.put(channel("channel:aa", "join")))
        assertTrue(store.put(sent("copy")))
        assertTrue(store.put(channel("channel:aa", "leave")))
        // A later run loads them in the order they were owed: the leave replaced the join and went last.
        val later = RoomOutboxStore(dao)
        assertEquals(listOf("sent:copy", "channel:aa"), later.loadAll().map { it.id })
        assertEquals("leave", later.loadAll().last().msgHash)
        // It goes on from the highest number, never reusing one.
        later.put(sent("newer"))
        assertEquals(listOf("sent:copy", "channel:aa", "sent:newer"), later.loadAll().map { it.id })
    }

    @Test
    fun aMessageProvedIsRemovedOnlyIfItIsStillTheOneStored() = runBlocking {
        val dao = FakeDao()
        val store = RoomOutboxStore(dao)
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
        val store = RoomOutboxStore(dao)
        assertTrue(store.put(channel("channel:aa", "join")))
        dao.failWrites = true
        assertFalse(store.put(channel("channel:aa", "leave")))
        // Storage still holds the join, which is owed no more: it goes, by a write no larger than what is held.
        assertEquals(listOf("join"), store.loadAll().map { it.msgHash })
        store.removeId("channel:aa")
        assertTrue(store.loadAll().isEmpty())
    }
}
