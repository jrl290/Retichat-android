package com.retichat.app.service

import com.retichat.app.bridge.LxmfFields
import com.retichat.app.bridge.RetichatBridge
import com.retichat.app.data.db.dao.MessageDao
import com.retichat.app.data.db.entity.DeliveryTrackingEntity
import com.retichat.app.data.model.toHex
import com.retichat.app.data.model.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages group-chat relay state and delivery tracking.
 *
 * The main fanout and relay logic lives in [ChatRepository].
 * This class provides helper utilities for checking delivery status
 * and resuming incomplete fanouts after a restart.
 */
class GroupChatManager(
    private val messageDao: MessageDao,
    private val scope: CoroutineScope,
    private val selfDestHash: ByteArray,
    private val routerHandle: Long,
    private val identityHandle: Long,
) {

    /**
     * Resume any incomplete group message fanouts.
     *
     * Called after app restart to check for partially-sent group messages.
     */
    fun resumeIncompleteFanouts() {
        // Future: query DeliveryTrackingEntity for undelivered entries
        // and re-attempt sending. For now, stale sends are marked FAILED
        // by failStaleOutbound().
    }

    /**
     * Request a relay from [relayerHex] for the given group message.
     *
     * Sends a message with GROUP_RELAY_FOR field set to our own hash,
     * asking the relayer to forward to remaining members.
     */
    fun requestRelay(
        groupIdHex: String,
        relayerHex: String,
        content: String,
    ) {
        val selfHex = selfDestHash.toHex()
        scope.launch(Dispatchers.IO) {
            val handle = RetichatBridge.messageCreate(
                destHash = relayerHex.hexToBytes(),
                srcHash = selfDestHash,
                content = content,
                method = RetichatBridge.DeliveryMethod.DIRECT,
                identityHandle = identityHandle,
            )
            if (handle != 0L) {
                RetichatBridge.messageAddFieldString(
                    handle, LxmfFields.GROUP_ID, groupIdHex,
                )
                RetichatBridge.messageAddFieldString(
                    handle, LxmfFields.GROUP_SENDER, selfHex,
                )
                RetichatBridge.messageAddFieldString(
                    handle, LxmfFields.GROUP_RELAY_FOR, selfHex,
                )
                RetichatBridge.messageSend(routerHandle, handle)
            }
        }
    }
}
