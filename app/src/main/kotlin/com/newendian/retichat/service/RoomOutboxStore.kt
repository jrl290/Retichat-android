package com.newendian.retichat.service

import com.newendian.retichat.data.db.dao.DistroOutboxDao
import com.newendian.retichat.data.db.entity.DistroOutboxEntity
import kotlinx.coroutines.CancellationException

/**
 * The distro outbox's storage ([OutboxStore]) in Room's `distro_outbox`
 * (database 13; the sealed bytes and sync signature of the distro sync proof
 * since 15, null for a row owed before). The outbox keeps what is owed in memory and this is its
 * durable copy: it is read once at start, and written by [put] when something
 * is owed, [remove] when the node proves it or it is owed no more, and
 * [removeId] when a refused write must still take a replaced message out.
 *
 * Rows are ordered by [DistroOutboxEntity.seq], which only grows: a message
 * owed under an id already there replaces it and goes to the end, as the web's
 * outbox does. A write that fails (a full disk) returns false and says nothing
 * here: the outbox says it, and holds the entry for this run.
 *
 * A row holding only one half of the sync proof is read as legacy and said as
 * a warning on [log] ([halfProof]): its proof is dropped, so its upload wakes
 * the distro's devices, and that must not happen in silence.
 */
class RoomOutboxStore(private val dao: DistroOutboxDao, private val log: OutboxLog) : OutboxStore {
    private var seq = 0L

    override suspend fun loadAll(): List<OwedEntry> {
        val rows = dao.all()
        seq = rows.maxOfOrNull { it.seq } ?: 0L
        return rows.mapNotNull { row -> toEntry(row)?.also { halfProof(row)?.let(log::warn) } }
    }

    override suspend fun put(entry: OwedEntry): Boolean = try {
        dao.upsert(toRow(entry, ++seq))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    override suspend fun remove(id: String, msgHash: String) {
        dao.remove(id, msgHash)
    }

    override suspend fun removeId(id: String) {
        dao.removeId(id)
    }

    companion object {
        const val KIND_CHANNEL = "channel"
        const val KIND_SENT = "sent"

        fun toRow(entry: OwedEntry, seq: Long): DistroOutboxEntity = DistroOutboxEntity(
            id = entry.id,
            kind = if (entry.kind == OwedKind.CHANNEL) KIND_CHANNEL else KIND_SENT,
            distro = entry.distro,
            distroKey = entry.distroKey,
            msgHash = entry.msgHash,
            packed = entry.packed,
            label = entry.label,
            op = entry.op,
            name = entry.name,
            atMs = entry.atMs,
            toHex = entry.toHex,
            seq = seq,
            sealed = entry.sealed,
            syncSig = entry.syncSig,
        )

        /** Null for a row of a kind this build does not know: it is left alone and never uploaded. */
        fun toEntry(row: DistroOutboxEntity): OwedEntry? {
            val kind = when (row.kind) {
                KIND_CHANNEL -> OwedKind.CHANNEL
                KIND_SENT -> OwedKind.SENT
                else -> return null
            }
            return OwedEntry(
                id = row.id, kind = kind, distro = row.distro, distroKey = row.distroKey,
                msgHash = row.msgHash, packed = row.packed, label = row.label,
                op = row.op, name = row.name, atMs = row.atMs, toHex = row.toHex,
                // Both or neither: a row with one alone is built the legacy way, as one owed before the proof
                // ([halfProof] says so when it is loaded).
                sealed = row.sealed?.takeIf { row.syncSig != null },
                syncSig = row.syncSig?.takeIf { row.sealed != null },
            )
        }

        /**
         * The warning for a row that holds one half of the sync proof alone, or null for one with
         * both or neither. This build always writes both or neither, from one seal in one write,
         * so only a damaged row or another writer comes here.
         */
        fun halfProof(row: DistroOutboxEntity): String? {
            if ((row.sealed == null) == (row.syncSig == null)) return null
            val held = if (row.sealed != null) "its sealed bytes without their sync signature"
            else "a sync signature without its sealed bytes"
            return "distro outbox: ${row.label} (${row.id}) is stored with $held; it is uploaded without the " +
                "sync proof, and its upload wakes the distro's devices"
        }
    }
}
