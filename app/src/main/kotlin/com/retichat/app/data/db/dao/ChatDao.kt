package com.retichat.app.data.db.dao

import androidx.room.*
import com.retichat.app.data.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Suppress("unused") // Room uses createdAt for ORDER BY

data class ChatPreview(
    val id: String,
    val isGroup: Boolean,
    val name: String,
    val memberHashes: String,
    val groupSecretHex: String?,
    val lastContent: String?,
    val lastTimestamp: Long?,
    val unreadCount: Int,
)

@Dao
interface ChatDao {
    /**
     * All chats ordered by most-recent message, with a preview of
     * the latest message and unread count.
     */
    @RewriteQueriesToDropUnusedColumns
    @Query("""
        SELECT c.*,
               m.content  AS lastContent,
               m.timestamp AS lastTimestamp,
               COALESCE(u.cnt, 0) AS unreadCount
        FROM chats c
        LEFT JOIN (
            SELECT chatId, content, timestamp
            FROM messages
            WHERE rowid IN (SELECT MAX(rowid) FROM messages GROUP BY chatId)
        ) m ON m.chatId = c.id
        LEFT JOIN (
            SELECT chatId, COUNT(*) AS cnt
            FROM messages
            WHERE isOutbound = 0 AND state < 4
            GROUP BY chatId
        ) u ON u.chatId = c.id
        ORDER BY COALESCE(m.timestamp, c.createdAt) DESC
    """)
    fun chatPreviews(): Flow<List<ChatPreview>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun findById(id: String): ChatEntity?

    @Upsert
    suspend fun upsert(chat: ChatEntity)

    @Delete
    suspend fun delete(chat: ChatEntity)
}
