package com.newendian.retichat.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.newendian.retichat.data.repository.ChatRepository
import com.newendian.retichat.names.SystemText
import com.newendian.retichat.service.RfedChannelClient
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
        repository.nameBook(),
        channelClient.channelsFlow(),
    ) { chats, names, channels ->
        val mapped: List<ChatListItem> = chats.map {
            ChatListItem(
                id = it.id,
                isGroup = it.isGroup,
                isChannel = false,
                // A DM is titled by its peer, resolved live (DISPLAY_NAMES.md
                // §5.3); a group by its group name.
                name = if (it.isGroup) it.name else names.contact(it.memberHashes),
                memberHashes = it.memberHashes,
                groupIdHex = it.groupIdHex,
                lastContent = it.lastContent?.let { content ->
                    SystemText.render(it.lastSystemKind, content, it.lastSender.orEmpty(), names)
                },
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

