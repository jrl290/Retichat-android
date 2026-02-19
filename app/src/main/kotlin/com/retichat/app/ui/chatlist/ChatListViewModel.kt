package com.retichat.app.ui.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retichat.app.data.db.dao.ChatPreview
import com.retichat.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ChatListViewModel(
    repository: ChatRepository,
) : ViewModel() {

    val chats: StateFlow<List<ChatPreview>> = repository
        .chatPreviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
