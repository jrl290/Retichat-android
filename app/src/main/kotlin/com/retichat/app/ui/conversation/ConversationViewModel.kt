package com.retichat.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retichat.app.data.db.entity.MessageEntity
import com.retichat.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConversationViewModel(
    private val chatId: String,
    private val repository: ChatRepository,
) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = repository
        .messagesForChat(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun send(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.sendMessage(chatId, content)
        }
    }

    fun sendWithAttachment(content: String, filename: String, data: ByteArray) {
        viewModelScope.launch {
            repository.sendMessage(chatId, content, listOf(filename to data))
        }
    }
}
