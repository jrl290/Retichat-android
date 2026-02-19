package com.retichat.app.data.db.dao

import androidx.room.*
import com.retichat.app.data.db.entity.AttachmentEntity
import com.retichat.app.data.db.entity.DeliveryTrackingEntity
import com.retichat.app.data.db.entity.GroupMemberEntity
import com.retichat.app.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun messagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: String): MessageEntity?

    @Upsert
    suspend fun upsert(msg: MessageEntity)

    @Query("UPDATE messages SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: Int)

    @Query("UPDATE messages SET nativeHandle = :handle WHERE id = :id")
    suspend fun updateHandle(id: String, handle: Long)

    // ---- Attachments ----

    @Insert
    suspend fun insertAttachment(att: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE messageId = :msgId")
    suspend fun attachmentsFor(msgId: String): List<AttachmentEntity>

    // ---- Delivery tracking (group) ----

    @Upsert
    suspend fun upsertTracking(entry: DeliveryTrackingEntity)

    @Query("SELECT * FROM delivery_tracking WHERE messageId = :msgId")
    suspend fun trackingFor(msgId: String): List<DeliveryTrackingEntity>

    @Query("""
        SELECT * FROM delivery_tracking
        WHERE messageId = :msgId AND delivered = 0
    """)
    suspend fun undeliveredFor(msgId: String): List<DeliveryTrackingEntity>

    // ---- Group members ----

    @Upsert
    suspend fun upsertGroupMember(member: GroupMemberEntity)

    @Query("SELECT * FROM group_members WHERE chatId = :chatId ORDER BY destHashHex ASC")
    fun groupMembers(chatId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE chatId = :chatId ORDER BY destHashHex ASC")
    suspend fun groupMembersList(chatId: String): List<GroupMemberEntity>
}
