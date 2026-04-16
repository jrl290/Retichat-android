package com.retichat.app.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.retichat.app.data.db.dao.ChatPreview
import com.retichat.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatListViewModel(
    private val repository: ChatRepository,
) : ViewModel() {

    val chats: StateFlow<List<ChatPreview>> = repository
        .chatPreviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun archiveChat(chatId: String) {
        viewModelScope.launch {
            repository.archiveChat(chatId)
        }
    }

    class Factory(private val repository: ChatRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatListViewModel(repository) as T
    }
}
