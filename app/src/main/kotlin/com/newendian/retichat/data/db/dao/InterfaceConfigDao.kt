package com.newendian.retichat.data.db.dao

import androidx.room.*
import com.newendian.retichat.data.db.entity.InterfaceConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InterfaceConfigDao {
    @Query("SELECT * FROM interfaces ORDER BY createdAt ASC")
    fun allInterfaces(): Flow<List<InterfaceConfigEntity>>

    @Query("SELECT * FROM interfaces WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun enabledInterfaces(): List<InterfaceConfigEntity>

    @Query("SELECT * FROM interfaces WHERE id = :id")
    suspend fun findById(id: Long): InterfaceConfigEntity?

    @Upsert
    suspend fun upsert(config: InterfaceConfigEntity): Long

    @Query("UPDATE interfaces SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Delete
    suspend fun delete(config: InterfaceConfigEntity)

    @Query("DELETE FROM interfaces WHERE id = :id")
    suspend fun deleteById(id: Long)
}
