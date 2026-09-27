package com.newendian.retichat.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.newendian.retichat.data.db.entity.AttachmentEntity
import com.newendian.retichat.data.db.entity.ChatEntity
import com.newendian.retichat.data.db.entity.GroupMemberEntity
import com.newendian.retichat.data.db.entity.MessageEntity
import com.newendian.retichat.data.model.Contact
import com.newendian.retichat.data.repository.ChatRepository
import com.newendian.retichat.names.NameBook
import com.newendian.retichat.service.ConnectionStateManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConversationViewModel(
    private val chatId: String,
    private val repository: ChatRepository,
) : ViewModel() {

    /** Paged message flow – newest first (caller uses reverseLayout). */
    val pagedMessages: Flow<PagingData<MessageEntity>> = Pager(
        config = PagingConfig(
            pageSize = 50,
            prefetchDistance = 20,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { repository.messagesForChatPaged(chatId) },
    ).flow.cachedIn(viewModelScope)

    /** The resolver for every name on this screen (DISPLAY_NAMES.md §5.3), live from Room. */
    val names: StateFlow<NameBook> =
        repository.nameBook()
            .stateIn(viewModelScope, SharingStarted.Eagerly, NameBook.EMPTY)

    /** Every contact (resolved names), for the DM peer's own name slot. */
    val contacts: StateFlow<List<Contact>> =
        repository.contacts()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Chat entity for this conversation (a group's name; a DM's title is resolved from the contact). */
    val chat: StateFlow<ChatEntity?> =
        repository.chatById(chatId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Group members for this chat (empty for 1:1). */
    val groupMembers: StateFlow<List<GroupMemberEntity>> =
        repository.groupMembers(chatId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True when this chat is a group with an unaccepted invite. */
    val isPendingInvite: StateFlow<Boolean> =
        repository.isPendingGroupInvite(chatId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun acceptInvite() {
        viewModelScope.launch { repository.acceptGroupInvite(chatId) }
    }

    fun declineInvite() {
        viewModelScope.launch { repository.declineGroupInvite(chatId) }
    }

    /** Set or clear (empty) the user's own name for a contact. */
    fun renameContact(destHashHex: String, newName: String) {
        viewModelScope.launch {
            repository.renameContact(destHashHex, newName)
        }
    }

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

    /**
     * Lifecycle hook: tell [ConnectionStateManager] to keep an APP_LINK
     * open to the peer while the conversation is on screen.  Match iOS
     * `ConnectionStateManager.openConversation(peerHash:)` semantics.
     * Idempotent; safe to call repeatedly.  No-op for group chats.
     */
    fun onConversationVisible() {
        viewModelScope.launch {
            val c = repository.chatById(chatId).firstOrNull() ?: return@launch
            if (c.isGroup) return@launch
            val peer = c.memberHashes.hexToBytesOrNull() ?: return@launch
            ConnectionStateManager.openConversation(peer)
        }
    }

    /** Lifecycle hook: peer link can be torn down (matches iOS). */
    fun onConversationHidden() {
        viewModelScope.launch {
            val c = repository.chatById(chatId).firstOrNull() ?: return@launch
            if (c.isGroup) return@launch
            val peer = c.memberHashes.hexToBytesOrNull() ?: return@launch
            ConnectionStateManager.closeConversation(peer)
        }
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    /** Get attachments for a message as a reactive flow. */
    fun attachmentsFor(msgId: String): Flow<List<AttachmentEntity>> =
        repository.attachmentsForFlow(msgId)

    class Factory(
        private val chatId: String,
        private val repository: ChatRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConversationViewModel(chatId, repository) as T
    }
}
