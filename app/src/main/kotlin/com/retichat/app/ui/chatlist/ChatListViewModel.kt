package com.retichat.app.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.retichat.app.data.repository.ChatRepository
import com.retichat.app.service.RfedChannelClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatListViewModel(
    private val repository: ChatRepository,
    private val channelClient: RfedChannelClient,
) : ViewModel() {

    /**
     * Unified list of chats and channels, sorted by most recent
     * message (or join time when there are none yet).
     */
    val items: StateFlow<List<ChatListItem>> = combine(
        repository.chatPreviews(),
        channelClient.channelsFlow(),
    ) { chats, channels ->
        val mapped: List<ChatListItem> = chats.map {
            ChatListItem(
                id = it.id,
                isGroup = it.isGroup,
                isChannel = false,
                name = it.name,
                memberHashes = it.memberHashes,
                groupIdHex = it.groupIdHex,
                lastContent = it.lastContent,
                lastTimestamp = it.lastTimestamp,
                unreadCount = it.unreadCount,
            )
        } + channels.map { ch ->
            ChatListItem(
                id = ch.id,
                isGroup = false,
                isChannel = true,
                name = "#${ch.channelName}",
                memberHashes = "",
                groupIdHex = null,
                lastContent = null,
                lastTimestamp = ch.lastMessageTime.takeIf { it > 0L } ?: ch.joinedAt,
                unreadCount = 0,
            )
        }
        mapped.sortedByDescending { it.lastTimestamp ?: 0L }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun archiveChat(chatId: String) {
        viewModelScope.launch {
            repository.archiveChat(chatId)
        }
    }

    fun leaveChannel(channelId: String) {
        viewModelScope.launch {
            channelClient.leaveChannel(channelId)
        }
    }

    class Factory(
        private val repository: ChatRepository,
        private val channelClient: RfedChannelClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatListViewModel(repository, channelClient) as T
    }
}

