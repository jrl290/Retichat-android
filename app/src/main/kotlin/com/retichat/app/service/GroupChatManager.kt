package com.retichat.app.service

import com.retichat.app.bridge.RetichatBridge
import com.retichat.app.data.db.dao.MessageDao
import com.retichat.app.data.db.entity.DeliveryTrackingEntity
import com.retichat.app.data.db.entity.GroupMemberEntity
import com.retichat.app.data.model.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Implements the group chat fanout protocol:
 *
 * 1. Every participant sends individually to every other participant.
 * 2. If a participant declares *low bandwidth*, it picks one non-low-BW
 *    member to relay on its behalf to the remaining recipients.
 * 3. If no relay is available, the sender completes the fanout itself.
 * 4. Idempotency: each (messageId, memberHash) pair is tracked so
 *    duplicates are ignored.
 *
 * All state is persisted in the [DeliveryTrackingEntity] table so that
 * a partial fanout can resume after a restart.
 */
class GroupChatManager(
    private val messageDao: MessageDao,
    private val scope: CoroutineScope,
    private val selfDestHash: ByteArray,
    private val routerHandle: Long,
) {

    /**
     * Fan out a group message to all members except self.
     *
     * @param chatId     group chat ID
     * @param messageId  unique message identifier (local)
     * @param content    message text
     * @param members    all group members (canonical order)
     * @param attachments optional attachment pairs (filename, data)
     */
    fun fanOut(
        chatId: String,
        messageId: String,
        content: String,
        members: List<GroupMemberEntity>,
        attachments: List<Pair<String, ByteArray>> = emptyList(),
    ) {
        val selfHex = selfDestHash.toHex()
        val targets = members.filter { it.destHashHex != selfHex }

        // Check which members have NOT been sent to yet
        scope.launch(Dispatchers.IO) {
            val tracking = messageDao.trackingFor(messageId)
            val alreadySent = tracking.filter { it.delivered }.map { it.memberHashHex }.toSet()

            val remaining = targets.filter { it.destHashHex !in alreadySent }
            if (remaining.isEmpty()) return@launch

            // Find low-bandwidth members
            val lowBwMembers = remaining.filter { it.isLowBandwidth }.map { it.destHashHex }.toSet()
            val normalMembers = remaining.filter { it.destHashHex !in lowBwMembers }

            // Try to relay through a normal-bandwidth member if we are low-BW
            // (Future: detect local bandwidth and request relay)

            // For now: direct fanout to all remaining
            remaining.forEach { member ->
                sendToMember(chatId, messageId, content, member, attachments)
            }
        }
    }

    /**
     * Relay a message on behalf of another sender.
     *
     * Called when we receive a relay request from a low-bandwidth participant.
     */
    fun relay(
        chatId: String,
        messageId: String,
        content: String,
        originalSenderHex: String,
        targets: List<GroupMemberEntity>,
        attachments: List<Pair<String, ByteArray>> = emptyList(),
    ) {
        scope.launch(Dispatchers.IO) {
            targets.forEach { member ->
                // TODO: set FIELD_GROUP_RELAY_FOR in the LXMF message fields
                sendToMember(chatId, messageId, content, member, attachments)
            }
        }
    }

    private suspend fun sendToMember(
        chatId: String,
        messageId: String,
        content: String,
        member: GroupMemberEntity,
        attachments: List<Pair<String, ByteArray>>,
    ) {
        val destHash = member.destHashHex.chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()

        val handle = RetichatBridge.messageCreate(
            destHash = destHash,
            srcHash = selfDestHash,
            content = content,
            method = RetichatBridge.DeliveryMethod.DIRECT,
        )
        if (handle == 0L) return

        attachments.forEach { (name, data) ->
            RetichatBridge.messageAddAttachment(handle, name, data)
        }

        RetichatBridge.messageSend(routerHandle, handle)

        // Mark as delivered (optimistic – real confirmation comes from proofs)
        messageDao.upsertTracking(
            DeliveryTrackingEntity(
                messageId = messageId,
                chatId = chatId,
                memberHashHex = member.destHashHex,
                delivered = true,
                deliveredAt = System.currentTimeMillis(),
            )
        )
    }
}
