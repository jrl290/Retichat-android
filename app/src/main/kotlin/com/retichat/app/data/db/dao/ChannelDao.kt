package com.retichat.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.retichat.app.data.db.entity.ChannelEntity
import com.retichat.app.data.db.entity.ChannelMessageEntity
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

    /** Drop a single channel message row by id (used to clean up the optimistic
     *  placeholder row once the canonical id is known). */
    @Query("DELETE FROM channel_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM channel_messages WHERE channelId = :channelId")
    suspend fun deleteMessagesForChannel(channelId: String)
}
