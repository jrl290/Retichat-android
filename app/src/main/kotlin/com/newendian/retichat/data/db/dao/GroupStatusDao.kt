package com.newendian.retichat.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.newendian.retichat.data.db.entity.GroupArrivalEntity
import com.newendian.retichat.data.db.entity.GroupArrivalHead
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

    // ---- Held arrivals through the distro (database 16, RFed SPEC §17.14) ----

    /** Every held arrival without its blob, oldest first: what the bounds decide on. */
    @Query("SELECT rowId, groupId, srcHex, msgHash, blobDigest, validated, distro, isPostSync, LENGTH(blob) AS size FROM group_arrivals ORDER BY rowId ASC")
    suspend fun arrivalHeads(): List<GroupArrivalHead>

    @Insert
    suspend fun holdArrival(arrival: GroupArrivalEntity): Long

    @Query("DELETE FROM group_arrivals WHERE rowId IN (:rowIds)")
    suspend fun dropArrivals(rowIds: List<Long>): Int

    /** A group's held arrivals, in arrival order: what its replay feeds back. */
    @Query("SELECT * FROM group_arrivals WHERE groupId = :groupId ORDER BY rowId ASC")
    suspend fun arrivalsFor(groupId: String): List<GroupArrivalEntity>

    @Query("DELETE FROM group_arrivals WHERE groupId = :groupId")
    suspend fun dropArrivalsFor(groupId: String): Int

    /** Forget what came through any distro other than [distro] (the device gave it up). */
    @Query("DELETE FROM group_arrivals WHERE distro != :distro")
    suspend fun dropArrivalsNotFor(distro: String): Int

    @Query("DELETE FROM group_arrivals")
    suspend fun dropAllArrivals(): Int
}
