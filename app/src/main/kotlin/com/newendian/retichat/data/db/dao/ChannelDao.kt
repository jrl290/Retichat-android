package com.newendian.retichat.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.newendian.retichat.data.db.NameSql
import com.newendian.retichat.data.db.NameStateSql
import com.newendian.retichat.data.db.entity.ChannelEntity
import com.newendian.retichat.data.db.entity.ChannelMembershipEntity
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

    /**
     * Posts from [sourceHashHex] stored as another poster's become the user's
     * own, SENT: the distro this device now holds sent them before it held it
     * (RfedChannelClient's distroClaim). Returns how many changed.
     */
    @Query("UPDATE channel_messages SET isOutbound = 1, sendState = 1 WHERE lower(sourceHashHex) = lower(:sourceHashHex) AND isOutbound = 0")
    suspend fun claimOwnPosts(sourceHashHex: String): Int

    /** Update the [ChannelMessageEntity.sendState] for a single outbound row. */
    @Query("UPDATE channel_messages SET sendState = :state WHERE id = :id")
    suspend fun updateMessageSendState(id: String, state: Int)

    /** Any outbound row still marked SENDING after a process restart cannot
     *  still be in flight; recover it as FAILED on startup. */
    @Query("UPDATE channel_messages SET sendState = 2 WHERE isOutbound = 1 AND sendState = 0")
    suspend fun failStaleOutboundSendingMessages(): Int

    /**
     * Posts typed while the app was starting (sendState QUEUED, James's UI
     * rulings 2026-10-11), oldest first: sent when the start finishes. Never
     * failed by [failStaleOutboundSendingMessages]: they wait for the start as
     * a queued DM does.
     */
    @Query("SELECT * FROM channel_messages WHERE isOutbound = 1 AND sendState = 3 ORDER BY timestamp ASC")
    suspend fun queuedOutboundMessages(): List<ChannelMessageEntity>

    /**
     * Take the queued post [id] for sending: SENDING, filed under [postingHex],
     * the posting identity held now, only while it is still queued. Returns 1
     * when this caller took it and 0 when it had already left the queue, so it
     * is sent once.
     */
    @Query("UPDATE channel_messages SET sendState = 0, sourceHashHex = :postingHex WHERE id = :id AND isOutbound = 1 AND sendState = 3")
    suspend fun claimQueuedMessage(id: String, postingHex: String): Int

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

    /** §5.2 order per (channel, sender): only a post newer than `nameAt` lands ([NameSql.SET_SENDER_CHANNEL_NAME]). */
    @Query(NameSql.SET_SENDER_CHANNEL_NAME)
    suspend fun setSenderChannelName(channelId: String, senderHex: String, name: String?, postTimestampMs: Long): Int

    /** Posters first seen here after [sinceMs] (the send rule's "new sender"). */
    @Query("SELECT COUNT(*) FROM channel_senders WHERE channelId = :channelId AND firstSeenAt > :sinceMs")
    suspend fun countSendersSeenAfter(channelId: String, sinceMs: Long): Int

    @Query("DELETE FROM channel_senders WHERE channelId = :channelId")
    suspend fun deleteSendersForChannel(channelId: String)

    /** The send rule's state for posts to [channelId] signed as [postingHex] (§4.2, per posting identity). */
    @Query("SELECT * FROM channel_name_state WHERE channelId = :channelId AND postingHex = :postingHex LIMIT 1")
    suspend fun nameState(channelId: String, postingHex: String): ChannelNameStateEntity?

    @Upsert
    suspend fun upsertNameState(state: ChannelNameStateEntity)

    /** A channel's state for every posting identity goes with the channel. */
    @Query("DELETE FROM channel_name_state WHERE channelId = :channelId")
    suspend fun deleteNameState(channelId: String)

    /**
     * Rows from before database 13 (empty `postingHex`) were the device's, the
     * only posting identity then: they become [deviceHex]'s where it has no
     * row of its own yet. [dropLegacyNameState] then removes what is left.
     */
    @Query(NameStateSql.ADOPT_LEGACY)
    suspend fun adoptLegacyNameState(deviceHex: String): Int

    @Query(NameStateSql.DROP_LEGACY)
    suspend fun dropLegacyNameState(): Int

    // ---- Membership record (RFed SPEC §17.12 rule 5) ----

    @Query("SELECT * FROM channel_membership WHERE channelId = :channelId LIMIT 1")
    suspend fun membership(channelId: String): ChannelMembershipEntity?

    @Upsert
    suspend fun upsertMembership(record: ChannelMembershipEntity)
}
