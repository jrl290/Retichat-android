package com.retichat.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.retichat.app.data.db.entity.AttachmentEntity
import com.retichat.app.data.db.entity.ChatEntity
import com.retichat.app.data.db.entity.GroupMemberEntity
import com.retichat.app.data.db.entity.MessageEntity
import com.retichat.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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

    /** Map of destination-hash-hex → display name, updated live from Room. */
    val contactNames: StateFlow<Map<String, String>> =
        repository.contacts()
            .map { contacts ->
                contacts.associate { c ->
                    c.destHash.joinToString("") { "%02x".format(it) } to c.displayName
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Chat entity for this conversation (provides the chat display name). */
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

    /** Rename a contact (and the DM chat). */
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
