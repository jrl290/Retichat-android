package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.AppLinkPacketCallback
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * `rfed.propagation.stream`: the live route RFed pushes LXMF messages on, for
 * this device's own address and for its distro (RFed SPEC §7 "Live delivery
 * and its proof", §17.3). A held link: each push arrives as a link packet,
 * which the link proves (Reticulum-rust `Link` proves every packet it hands
 * a packet callback), so RFed counts it delivered.
 *
 * Until 2026-09-26 Android never opened it. RFed had no proven route to a
 * running app, so propagated messages reached it only through an FCM push
 * and a sync, and distro messages only through an rfed.delivery packet
 * nothing confirmed (lost when the app had died).
 *
 * Port of Retichat-ios ChatRepository.configurePropagationStream.
 */
object PropagationStream {

    private const val TAG = "PropStream"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Session(
        val destHash: ByteArray,
        val routerHandle: Long,
        val identityHandle: Long,
        val selfDestHash: ByteArray,
    )

    @Volatile
    private var session: Session? = null

    /**
     * Open the stream to the configured RFed node and keep it open. Call after
     * [ConnectionStateManager.register]: it needs the router and APP_LINKs.
     * The open request goes out on every ACTIVE edge of the held link, since
     * RFed binds the link that asked.
     */
    suspend fun start(app: RetichatApp, routerHandle: Long, identityHandle: Long, selfDestHash: ByteArray) {
        val nodeHex = UserPreferences.getEffectiveRfedNodeIdentityHash(app)
        if (nodeHex.length != 32 || selfDestHash.size != 16) {
            session = null
            return
        }
        val destHex = RfedChannelClient.rfedDestHash(nodeHex, "rfed", listOf("propagation", "stream")) ?: return
        val destHash = FcmTokenRegistrar.hexToBytes(destHex) ?: return
        val current = Session(destHash, routerHandle, identityHandle, selfDestHash)
        session = current

        ConnectionStateManager.registerAppLinkPacketCallback(
            destHash,
            object : AppLinkPacketCallback {
                override fun onPacket(bytes: ByteArray) {
                    scope.launch {
                        runCatching { onPush(app, current, bytes) }
                            .onFailure { Log.e(TAG, "propagation.stream push failed", it) }
                    }
                }
            },
        )
        ConnectionStateManager.setAppLinkStatusHandler(destHash) { status ->
            if (status != RetichatBridge.AppLinkStatus.ACTIVE) return@setAppLinkStatusHandler
            scope.launch { sendOpen(app, current) }
        }
        if (ConnectionStateManager.appLinkStatus(destHash) == RetichatBridge.AppLinkStatus.ACTIVE) {
            sendOpen(app, current)
        } else {
            // prefersPersistentAppLink: a held link for propagation.stream.
            ConnectionStateManager.primeAppLink(destHash, "rfed", "propagation.stream")
        }
    }

    /** The stack is stopping: pushes for this session are no longer taken. */
    fun stop() {
        session = null
    }

    private suspend fun sendOpen(app: RetichatApp, current: Session) {
        if (session !== current) return
        val pubkey = RetichatBridge.identityPublicKey(current.identityHandle) ?: return
        val sig = RetichatBridge.identitySign(current.identityHandle, current.selfDestHash) ?: return
        val payload = app.rfedChannelClient.msgpackSigned(current.selfDestHash, pubkey, sig)
        val response = ConnectionStateManager.appLinkSend(
            current.destHash,
            "rfed",
            "propagation.stream",
            "/rfed/propagation/stream/open",
            payload,
        )
        if (openAccepted(response)) {
            Log.i(TAG, "propagation.stream open for ${current.selfDestHash.toHex().take(8)}")
        } else {
            Log.w(TAG, "propagation.stream open rejected resp=${response?.toHex() ?: "null"}")
        }
    }

    /**
     * One push: the bare LXMF propagation blob, `dest(16) | encrypted`. A blob
     * for the distro goes to the distro client (the router holds no
     * destination for it); anything else to the router, as a sync would.
     */
    private suspend fun onPush(app: RetichatApp, current: Session, blob: ByteArray) {
        if (session !== current || blob.size <= 16) return
        val distroHex = DistroManager.deliveryHashHex
        if (distroHex != null && blob.copyOfRange(0, 16).toHex() == distroHex) {
            Log.i(TAG, "distro push ${blob.size}B")
            RfedDistroClient.handleBlob(app, blob)
            return
        }
        if (!RetichatBridge.routerIngestPropagated(current.routerHandle, blob)) {
            Log.w(TAG, "push not taken by the router (${blob.size}B): ${RetichatBridge.lastError()}")
        }
    }

    /** `[true, nil]`: msgpack fixarray-2 whose first element is true. */
    internal fun openAccepted(response: ByteArray?): Boolean =
        response != null && response.size >= 2 &&
            (response[0].toInt() and 0xff) == 0x92 && (response[1].toInt() and 0xff) == 0xc3

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
