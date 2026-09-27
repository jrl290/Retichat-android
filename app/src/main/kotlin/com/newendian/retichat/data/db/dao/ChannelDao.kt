package com.newendian.retichat.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.newendian.retichat.data.db.entity.ChannelEntity
import com.newendian.retichat.data.db.entity.ChannelMessageEntity
import com.newendian.retichat.data.db.entity.ChannelNameStateEntity
import com.newendian.retichat.data.db.entity.ChannelSenderEntity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels WHERE isArchived = 0 ORDER BY lastMessageTime DESC")
    fun activeChannelsFlow(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE isArchived = 0")
    suspend fun activeChannels(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    fun channelByIdFlow(id: String): Flow<ChannelEntity?>

    @Upsert
    suspend fun upsert(channel: ChannelEntity)

    @Query("UPDATE channels SET stampCost = :cost WHERE id = :id")
    suspend fun updateStampCost(id: String, cost: Int?)

    @Query("UPDATE channels SET lastMessageTime = :ts WHERE id = :id AND lastMessageTime < :ts")
    suspend fun bumpLastMessageTime(id: String, ts: Long)

    @Delete
    suspend fun delete(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteById(id: String)

    // ---- Channel messages ----

    @Query("SELECT * FROM channel_messages WHERE channelId = :channelId ORDER BY timestamp ASC")
    fun messagesFlow(channelId: String): Flow<List<ChannelMessageEntity>>

    @Query("SELECT COUNT(*) FROM channel_messages WHERE channelId = :channelId AND id = :id")
    suspend fun messageExists(channelId: String, id: String): Int

    @Upsert
    suspend fun upsertMessage(message: ChannelMessageEntity)

    /** Update the [ChannelMessageEntity.sendState] for a single outbound row. */
    @Query("UPDATE channel_messages SET sendState = :state WHERE id = :id")
    suspend fun updateMessageSendState(id: String, state: Int)

    /** Any outbound row still marked SENDING after a process restart cannot
     *  still be in flight; recover it as FAILED on startup. */
    @Query("UPDATE channel_messages SET sendState = 2 WHERE isOutbound = 1 AND sendState = 0")
    suspend fun failStaleOutboundSendingMessages(): Int

    /** Drop a single channel message row by id (used to clean up the optimistic
     *  placeholder row once the canonical id is known). */
    @Query("DELETE FROM channel_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM channel_messages WHERE channelId = :channelId")
    suspend fun deleteMessagesForChannel(channelId: String)

    // ---- Channel names (DISPLAY_NAMES.md §4.2, §5.1) ----

    @Query("SELECT * FROM channel_senders WHERE channelId = :channelId")
    fun sendersFlow(channelId: String): Flow<List<ChannelSenderEntity>>

    @Query("SELECT * FROM channel_senders WHERE channelId = :channelId AND senderHex = :senderHex LIMIT 1")
    suspend fun findSender(channelId: String, senderHex: String): ChannelSenderEntity?

    /** Record a poster the first time it is seen here; an existing row is kept. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSenderIfAbsent(sender: ChannelSenderEntity): Long

    @Query("UPDATE channel_senders SET channelName = :name WHERE channelId = :channelId AND senderHex = :senderHex")
    suspend fun setSenderChannelName(channelId: String, senderHex: String, name: String?)

    /** Posters first seen here after [sinceMs] (the send rule's "new sender"). */
    @Query("SELECT COUNT(*) FROM channel_senders WHERE channelId = :channelId AND firstSeenAt > :sinceMs")
    suspend fun countSendersSeenAfter(channelId: String, sinceMs: Long): Int

    @Query("DELETE FROM channel_senders WHERE channelId = :channelId")
    suspend fun deleteSendersForChannel(channelId: String)

    @Query("SELECT * FROM channel_name_state WHERE channelId = :channelId LIMIT 1")
    suspend fun nameState(channelId: String): ChannelNameStateEntity?

    @Upsert
    suspend fun upsertNameState(state: ChannelNameStateEntity)

    @Query("DELETE FROM channel_name_state WHERE channelId = :channelId")
    suspend fun deleteNameState(channelId: String)
}
