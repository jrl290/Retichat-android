package com.newendian.retichat.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.newendian.retichat.data.db.entity.GroupHeldEntity
import com.newendian.retichat.data.db.entity.GroupStatusEntity

/**
 * Member statuses and keys (RFed-spec Group.md, 2026-10-06), database 14: the
 * creator's copies of its members' accepts and leaves, and the accepts and
 * leaves held until their source's key arrives.
 */
@Dao
interface GroupStatusDao {
    // ---- The creator's copies ----

    @Query("SELECT * FROM group_statuses WHERE chatId = :chatId AND memberHex = :memberHex")
    suspend fun copy(chatId: String, memberHex: String): GroupStatusEntity?

    /** Keep [copy] as the member's: the row under the same group and member is replaced. */
    @Upsert
    suspend fun putCopy(copy: GroupStatusEntity)

    /** A group's copies, by member hash ascending: the order a status lists them in. */
    @Query("SELECT * FROM group_statuses WHERE chatId = :chatId ORDER BY memberHex ASC")
    suspend fun copies(chatId: String): List<GroupStatusEntity>

    @Query("DELETE FROM group_statuses WHERE chatId = :chatId")
    suspend fun deleteCopies(chatId: String): Int

    // ---- Held until a key arrives ----

    /** Every held entry, oldest first: the order they came in. */
    @Query("SELECT * FROM group_held ORDER BY rowId ASC")
    suspend fun held(): List<GroupHeldEntity>

    @Query("SELECT COUNT(*) FROM group_held")
    suspend fun heldCount(): Int

    @Query("SELECT COUNT(*) FROM group_held WHERE chatId = :chatId AND srcHex = :srcHex")
    suspend fun heldCountFor(chatId: String, srcHex: String): Int

    @Query("SELECT COUNT(*) FROM group_held WHERE msgHash = :msgHash")
    suspend fun heldWithHash(msgHash: String): Int

    /** How many entries wait for [srcHex]'s key, in any group: a cheap look before any is decided. */
    @Query("SELECT COUNT(*) FROM group_held WHERE srcHex = :srcHex")
    suspend fun heldFrom(srcHex: String): Int

    @Insert
    suspend fun hold(entry: GroupHeldEntity): Long

    @Query("DELETE FROM group_held WHERE rowId = :rowId")
    suspend fun release(rowId: Long): Int

    @Query("DELETE FROM group_held WHERE chatId = :chatId")
    suspend fun deleteHeld(chatId: String): Int
}
