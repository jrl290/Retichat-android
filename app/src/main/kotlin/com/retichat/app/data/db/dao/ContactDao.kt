package com.retichat.app.data.db.dao

import androidx.room.*
import com.retichat.app.data.db.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun allContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE destHashHex = :hex")
    suspend fun findByHash(hex: String): ContactEntity?

    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)
}
