package com.newendian.retichat.data.db.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.newendian.retichat.data.db.entity.AttachmentEntity
import com.newendian.retichat.data.db.entity.DeliveryTrackingEntity
import com.newendian.retichat.data.db.entity.GroupMemberEntity
import com.newendian.retichat.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun messagesForChat(chatId: String): Flow<List<MessageEntity>>

    /** Paged query – newest first (used with reverseLayout). */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC")
    fun messagesForChatPaged(chatId: String): PagingSource<Int, MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: String): MessageEntity?

    @Upsert
    suspend fun upsert(msg: MessageEntity)

    @Query("UPDATE messages SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: Int)

    @Query("UPDATE messages SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float)

    @Query("UPDATE messages SET state = :state, progress = :progress WHERE id = :id")
    suspend fun updateStateAndProgress(id: String, state: Int, progress: Float)

    @Query("UPDATE messages SET nativeHandle = :handle WHERE id = :id")
    suspend fun updateHandle(id: String, handle: Long)

    /** Mark any outbound messages left in transient states as FAILED (app was killed mid-send). */
    @Query("UPDATE messages SET state = 0xFF WHERE isOutbound = 1 AND state IN (0x00, 0x02)")
    suspend fun failStaleOutbound()

    /** Messages queued while offline (OUTBOUND state, not yet sent natively). */
    @Query("SELECT * FROM messages WHERE isOutbound = 1 AND state = 0x01 AND nativeHandle = 0 ORDER BY timestamp ASC")
    suspend fun pendingOutbound(): List<MessageEntity>

    // ---- Attachments ----

    @Insert
    suspend fun insertAttachment(att: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE messageId = :msgId")
    suspend fun attachmentsFor(msgId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE messageId = :msgId")
    fun attachmentsForFlow(msgId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId IN (SELECT id FROM messages WHERE chatId = :chatId)")
    suspend fun attachmentsForChat(chatId: String): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE messageId IN (SELECT id FROM messages WHERE chatId = :chatId)")
    suspend fun deleteAttachmentsForChat(chatId: String)

    // ---- Delivery tracking (group) ----

    @Upsert
    suspend fun upsertTracking(entry: DeliveryTrackingEntity)

    @Query("SELECT * FROM delivery_tracking WHERE messageId = :msgId")
    suspend fun trackingFor(msgId: String): List<DeliveryTrackingEntity>

    @Query("DELETE FROM delivery_tracking WHERE chatId = :chatId")
    suspend fun deleteDeliveryTrackingForChat(chatId: String)

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

    @Query("DELETE FROM group_members WHERE chatId = :chatId")
    suspend fun deleteGroupMembersForChat(chatId: String)

    @Query("DELETE FROM group_members WHERE chatId = :chatId AND destHashHex = :hashHex")
    suspend fun deleteGroupMember(chatId: String, hashHex: String)

    @Query("UPDATE group_members SET inviteStatus = :status WHERE chatId = :chatId AND destHashHex = :hashHex")
    suspend fun setGroupMemberStatus(chatId: String, hashHex: String, status: String)

    @Query("SELECT * FROM group_members WHERE chatId = :chatId AND inviteStatus = :status ORDER BY destHashHex ASC")
    suspend fun groupMembersWithStatus(chatId: String, status: String): List<GroupMemberEntity>

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: String)
}
