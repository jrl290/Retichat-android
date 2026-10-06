package com.newendian.retichat.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.newendian.retichat.data.db.entity.DistroOutboxEntity

/** What this device owes its distro (RFed SPEC §17.12, §17.11), oldest first. */
@Dao
interface DistroOutboxDao {
    @Query("SELECT * FROM distro_outbox ORDER BY seq ASC")
    suspend fun all(): List<DistroOutboxEntity>

    /** Owe [entry]: a row under the same id (an earlier action on the channel) is replaced. */
    @Upsert
    suspend fun upsert(entry: DistroOutboxEntity)

    /**
     * The propagation node proved the message [msgHash], or it is owed no
     * more: only that message goes, never one put under the same id since (a
     * later action on the channel).
     */
    @Query("DELETE FROM distro_outbox WHERE id = :id AND msgHash = :msgHash")
    suspend fun remove(id: String, msgHash: String): Int

    /** The row under [id], whatever message it holds: a replaced message's removal when a write was refused. */
    @Query("DELETE FROM distro_outbox WHERE id = :id")
    suspend fun removeId(id: String): Int
}
