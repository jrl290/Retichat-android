package com.retichat.app.ui.chatlist

/**
 * UI model for a single row in the chat list. Unifies DM/group chats
 * (backed by [com.retichat.app.data.db.dao.ChatPreview]) and RFed
 * channels (backed by
 * [com.retichat.app.data.db.entity.ChannelEntity]) so the screen
 * can render them in a single sorted list.
 */
data class ChatListItem(
    /**
     * Stable list key: the chat row id for chats; the channel hash hex
     * (16 bytes = 32 chars) for channels.
     */
    val id: String,
    val isGroup: Boolean,
    val isChannel: Boolean,
    val name: String,
    val memberHashes: String,
    val groupIdHex: String?,
    val lastContent: String?,
    val lastTimestamp: Long?,
    val unreadCount: Int,
)
