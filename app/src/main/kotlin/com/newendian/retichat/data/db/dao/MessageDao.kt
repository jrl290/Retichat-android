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

    // ---- Reactions (REACTIONS-DESIGN.md §3, §6) ----

    /**
     * `find_target` in a direct chat: the message of chat [chatId] whose LXMF
     * hash is [hash] (64 lowercase hex), by its [MessageEntity.lxmfHash] or by
     * its id where the id is the hash (a received direct message). Never
     * outside [chatId].
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND (lxmfHash = :hash OR id = :hash) LIMIT 1")
    suspend fun findByLxmfHash(chatId: String, hash: String): MessageEntity?

    /** `find_target` in a Group: the post of chat [chatId] whose id is [postId] (32 lowercase hex). Never outside [chatId]. */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND postId = :postId LIMIT 1")
    suspend fun findByPostId(chatId: String, postId: String): MessageEntity?

    /** An outgoing message's LXMF hash, once its send has one. */
    @Query("UPDATE messages SET lxmfHash = :hash WHERE id = :id")
    suspend fun setLxmfHash(id: String, hash: String)

    /** The reactions to [id], as ChatRepository's apply wrote them. Its rowid stays, so the chat list's order does not change. */
    @Query("UPDATE messages SET reactionsJson = :json WHERE id = :id")
    suspend fun setReactionsJson(id: String, json: String)

    @Query("UPDATE messages SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float)

    @Query("UPDATE messages SET state = :state, progress = :progress WHERE id = :id")
    suspend fun updateStateAndProgress(id: String, state: Int, progress: Float)

    @Query("UPDATE messages SET nativeHandle = :handle WHERE id = :id")
    suspend fun updateHandle(id: String, handle: Long)

    /**
     * Set [state] unless the row already succeeded (SENT 0x04, DELIVERED
     * 0x08): success is sticky. One conditional UPDATE, so a DELIVERED
     * written between a read and this write is not overwritten. Returns the
     * number of rows changed.
     */
    @Query("UPDATE messages SET state = :state WHERE id = :id AND state NOT IN (0x04, 0x08)")
    suspend fun updateStateUnlessSucceeded(id: String, state: Int): Int

    /**
     * Set [state] while the row still follows [handle] and has not succeeded:
     * one attempt's outcome, not written over another attempt that took the
     * row over meanwhile. One conditional UPDATE. Returns 1 when written.
     */
    @Query("UPDATE messages SET state = :state WHERE id = :id AND nativeHandle = :handle AND state NOT IN (0x04, 0x08)")
    suspend fun updateStateOnHandleUnlessSucceeded(id: String, handle: Long, state: Int): Int

    /**
     * Point the row at [handle] and [state], unless the row already
     * succeeded: a propagated copy taking the row over, or the DIRECT attempt
     * taking it back when its copy failed. Returns 1 when [handle] took the row.
     */
    @Query("UPDATE messages SET nativeHandle = :handle, state = :state WHERE id = :id AND state NOT IN (0x04, 0x08)")
    suspend fun takeOverUnlessSucceeded(id: String, handle: Long, state: Int): Int

    /** Mark any outbound messages left in transient states as FAILED (app was killed mid-send). */
    @Query("UPDATE messages SET state = 0xFF WHERE isOutbound = 1 AND state IN (0x00, 0x02)")
    suspend fun failStaleOutbound()

    /** Messages queued while offline (OUTBOUND state, not yet sent natively). */
    @Query("SELECT * FROM messages WHERE isOutbound = 1 AND state = 0x01 AND nativeHandle = 0 ORDER BY timestamp ASC")
    suspend fun pendingOutbound(): List<MessageEntity>

    /**
     * Take a queued row for sending: record its native handle only while the
     * row still matches [pendingOutbound]. Returns 1 when this caller took it
     * and 0 when it had already left the queue. One conditional UPDATE, so
     * two callers can not both take the same row and send it twice.
     */
    @Query("UPDATE messages SET nativeHandle = :handle WHERE id = :id AND isOutbound = 1 AND state = 0x01 AND nativeHandle = 0")
    suspend fun claimPendingOutbound(id: String, handle: Long): Int

    /**
     * Take a group post queued at start-up (GROUPS-DISTRO-DESIGN.md §8.7) from
     * the queue: set its [state] (SENDING, or FAILED when it cannot be signed
     * now) and its [sender], the group's own address resolved now, only while
     * the row still matches [pendingOutbound]. Returns 1 when this caller took
     * it and 0 when it had already left the queue, so it is sent once.
     */
    @Query("UPDATE messages SET state = :state, senderHashHex = :sender WHERE id = :id AND isOutbound = 1 AND state = 0x01 AND nativeHandle = 0")
    suspend fun claimQueuedGroupPost(id: String, state: Int, sender: String): Int

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
