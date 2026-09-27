package com.newendian.retichat.data.db.dao

import androidx.room.*
import com.newendian.retichat.data.db.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    /** Every contact; order by the resolved name where it is shown (NameBook). */
    @Query("SELECT * FROM contacts")
    fun allContacts(): Flow<List<ContactEntity>>

    /** One-shot (non-Flow) snapshot of all contacts. */
    @Query("SELECT * FROM contacts")
    suspend fun allContactsSnapshot(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE destHashHex = :hex")
    suspend fun findByHash(hex: String): ContactEntity?

    @Upsert
    suspend fun upsert(contact: ContactEntity)

    /** Create [contact] unless a row for its hash exists (which keeps its names and key). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(contact: ContactEntity): Long

    @Query("UPDATE contacts SET localName = :name WHERE destHashHex = :hex")
    suspend fun setLocalName(hex: String, name: String?)

    @Query("UPDATE contacts SET messageName = :name WHERE destHashHex = :hex")
    suspend fun setMessageName(hex: String, name: String?)

    @Query("UPDATE contacts SET announceName = :name WHERE destHashHex = :hex")
    suspend fun setAnnounceName(hex: String, name: String?)

    @Query("UPDATE contacts SET isAllowlisted = 1 WHERE destHashHex = :hex")
    suspend fun setAllowlisted(hex: String)

    @Query("UPDATE contacts SET publicKeyHex = :publicKeyHex WHERE destHashHex = :hex")
    suspend fun setPublicKey(hex: String, publicKeyHex: String)

    @Delete
    suspend fun delete(contact: ContactEntity)
}
