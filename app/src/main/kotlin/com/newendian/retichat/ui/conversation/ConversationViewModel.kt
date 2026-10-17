package com.newendian.retichat.ui.conversation

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.newendian.retichat.PhotoLocationNotRemoved
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.data.db.entity.AttachmentEntity
import com.newendian.retichat.data.db.entity.ChatEntity
import com.newendian.retichat.data.db.entity.GroupMemberEntity
import com.newendian.retichat.data.db.entity.MessageEntity
import com.newendian.retichat.data.model.Contact
import com.newendian.retichat.data.repository.ChatRepository
import com.newendian.retichat.data.repository.GroupActing
import com.newendian.retichat.names.NameBook
import com.newendian.retichat.service.ConnectionStateManager
import com.newendian.retichat.service.DistroManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
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

    /**
     * Every peer with a row, hidden ones included (resolved names), for the DM
     * peer's own name slot: a stranger's conversation is no contact, and the
     * user can still name it.
     */
    val contacts: StateFlow<List<Contact>> =
        repository.peers()
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

    /**
     * Who acts in this group from this device (GROUPS-DISTRO-DESIGN.md §8.7,
     * §13): whether it is a D-group, whether its own address can be signed for
     * now, and every self address ("You"). Null for a DM. Re-read when the
     * group, its list, the distro held or whether the stack is up changes.
     */
    val groupActing: StateFlow<GroupActing?> =
        combine(repository.chatById(chatId), repository.groupMembers(chatId), DistroManager.state, repository.groupActionsReady) { chat, _, _, _ ->
            chat?.takeIf { it.isGroup }?.let { repository.groupActing(it) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Every address the user sends this chat as (REACTIONS-DESIGN.md §3
     * "Display"): a chip is highlighted when one of its reactors is one of them.
     * Re-read when the chat, its list or the distro held changes.
     */
    val reactionSelves: StateFlow<Set<String>> =
        combine(repository.chatById(chatId), repository.groupMembers(chatId), DistroManager.state) { chat, _, _ ->
            chat?.let { repository.reactionSelves(it) } ?: emptySet()
        }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * The user picked [emoji] on [msg] (§7: the long-press row or a chip): it
     * becomes theirs, or is removed if it already is. Applied and sent by the
     * repository on a coroutine: the UI thread never waits on it.
     */
    fun react(msg: MessageEntity, emoji: String) {
        viewModelScope.launch { repository.react(chatId, msg.id, emoji) }
    }

    fun acceptInvite() {
        viewModelScope.launch { repository.acceptGroupInvite(chatId) }
    }

    /** Decline the invite; [onDeclined] only when it was done (it is refused while nothing can be sent). */
    fun declineInvite(onDeclined: () -> Unit) {
        viewModelScope.launch { if (repository.declineGroupInvite(chatId)) onDeclined() }
    }

    /** Set or clear (empty) the user's own name for a contact. */
    fun renameContact(destHashHex: String, newName: String) {
        viewModelScope.launch {
            repository.renameContact(destHashHex, newName)
        }
    }

    /**
     * Send [content]. [onRefused] runs when a group post was refused with
     * nothing stored or sent (GROUPS-DISTRO-DESIGN.md §8.7: its distro went
     * since the screen was drawn), so the screen can put the draft back; nothing
     * is said (James, 2026-10-11). A post typed at start-up is not refused: it
     * waits, as a DM does. It runs on Main.
     */
    fun send(content: String, onRefused: () -> Unit = {}) {
        val text = OutgoingText.of(content)
        if (text.isEmpty()) return
        viewModelScope.launch {
            repository.sendMessage(chatId, text)?.let { onRefused() }
        }
    }

    /** As [send], with one attachment. */
    fun sendWithAttachment(content: String, filename: String, data: ByteArray, onRefused: () -> Unit = {}) {
        val text = OutgoingText.of(content)
        viewModelScope.launch {
            try {
                repository.sendMessage(chatId, text, listOf(filename to data))?.let { onRefused() }
            } catch (e: PhotoLocationNotRemoved) {
                // A photo leaves without where it was taken (James, 2026-10-05):
                // nothing was stored or sent; say why (this runs on Main).
                Log.w("ConversationVM", "not sent: ${e.message}")
                RetichatApp.appInstance?.let { Toast.makeText(it, e.message, Toast.LENGTH_LONG).show() }
            }
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
