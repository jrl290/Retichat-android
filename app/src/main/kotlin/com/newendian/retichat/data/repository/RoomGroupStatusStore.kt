package com.newendian.retichat.data.repository

import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.db.dao.ChatDao
import com.newendian.retichat.data.db.dao.GroupStatusDao
import com.newendian.retichat.data.db.dao.MessageDao
import com.newendian.retichat.data.db.entity.ChatEntity
import com.newendian.retichat.data.db.entity.GroupHeldEntity
import com.newendian.retichat.data.db.entity.GroupMemberEntity
import com.newendian.retichat.data.db.entity.GroupStatusEntity
import com.newendian.retichat.data.db.entity.MessageEntity
import com.newendian.retichat.names.SystemText

/**
 * [GroupStatusStore] in Room: the group and its members (`chats`,
 * `group_members`), the creator's copies (`group_statuses`) and the entries
 * held for a key (`group_held`), database 14. What passes the privacy filter
 * and whose key a hidden contact row holds are the repository's
 * ([allowContact], [keepContactKey]): it owns the contact rows.
 */
class RoomGroupStatusStore(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val dao: GroupStatusDao,
    private val allowContact: suspend (String) -> Unit,
    private val keepContactKey: suspend (String, ByteArray) -> Unit,
) : GroupStatusStore {
    override suspend fun chatByGroupId(groupId: String): ChatEntity? =
        chatDao.findByGroupId(groupId)?.takeIf { it.isGroup }

    override suspend fun chatById(chatId: String): ChatEntity? =
        chatDao.findById(chatId)?.takeIf { it.isGroup }

    override suspend fun members(chatId: String): List<GroupMemberEntity> = messageDao.groupMembersList(chatId)

    override suspend fun setMemberStatus(chatId: String, memberHex: String, status: String) =
        messageDao.setGroupMemberStatus(chatId, memberHex, status)

    override suspend fun memberLine(chatId: String, memberHex: String, joined: Boolean, id: String, timestampMs: Long, otherDevice: Boolean) {
        // A system line about the member (name resolved when shown), or about
        // another device of the user's ("Your other device …").
        messageDao.upsert(
            MessageEntity(
                id = id,
                chatId = chatId,
                senderHashHex = memberHex,
                content = if (joined) "joined the group" else "left the group",
                timestamp = timestampMs,
                isOutbound = false,
                state = RetichatBridge.MessageState.DELIVERED,
                systemKind = if (otherDevice) SystemText.OTHER_DEVICE else SystemText.MEMBER,
            )
        )
    }

    override suspend fun allow(memberHex: String) = allowContact(memberHex)

    override suspend fun keepKey(memberHex: String, publicKey: ByteArray) = keepContactKey(memberHex, publicKey)

    override suspend fun copy(chatId: String, memberHex: String): StatusCopy? =
        dao.copy(chatId, memberHex)?.let { StatusCopy(it.memberHex, it.action, it.packed) }

    override suspend fun putCopy(chatId: String, memberHex: String, action: String, packed: ByteArray) =
        dao.putCopy(GroupStatusEntity(chatId, memberHex, action, packed))

    override suspend fun copies(chatId: String): List<StatusCopy> =
        dao.copies(chatId).map { StatusCopy(it.memberHex, it.action, it.packed) }

    override suspend fun held(): List<HeldEntry> = dao.held().map {
        HeldEntry(it.rowId, it.chatId, it.srcHex, it.action, it.msgHash, it.packed)
    }

    override suspend fun heldCount(): Int = dao.heldCount()

    override suspend fun heldCountFor(chatId: String, srcHex: String): Int = dao.heldCountFor(chatId, srcHex)

    override suspend fun heldFrom(srcHex: String): Int = dao.heldFrom(srcHex)

    override suspend fun isHeld(msgHash: String): Boolean = dao.heldWithHash(msgHash) > 0

    override suspend fun hold(entry: HeldEntry) {
        dao.hold(GroupHeldEntity(chatId = entry.chatId, srcHex = entry.srcHex, action = entry.action, msgHash = entry.msgHash, packed = entry.packed))
    }

    override suspend fun release(entry: HeldEntry) {
        dao.release(entry.id)
    }
}
