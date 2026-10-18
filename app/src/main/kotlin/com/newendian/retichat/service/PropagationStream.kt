package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.RetichatBridge

/**
 * The live route RFed pushes LXMF messages on, for this device's own address
 * and for its distro (RFed SPEC §7 "Live delivery and its proof", §17.3).
 * Since 2026-10-18 it is the `/propagation/stream/open` binding on
 * [RfedLink]: each push is a `/lxmf/delivery` request on that link, and this
 * device's answer is its proof (RFed-spec Link.md). Before, it was its own
 * held link to `rfed.propagation.stream`.
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

    private class Session(
        val nodeHex: String,
        val routerHandle: Long,
        val identityHandle: Long,
        val selfDestHash: ByteArray,
    )

    @Volatile
    private var session: Session? = null

    /**
     * Bind the configured RFed node's rfed.link for LXMF pushes, now and on
     * every fresh link ([RfedLink] calls [onLinkActive]). Call after
     * [ConnectionStateManager.register]: it needs the router and APP_LINKs.
     */
    suspend fun start(app: RetichatApp, routerHandle: Long, identityHandle: Long, selfDestHash: ByteArray) {
        val nodeHex = UserPreferences.getEffectiveRfedNodeIdentityHash(app).lowercase()
        if (nodeHex.length != 32 || selfDestHash.size != 16) {
            session = null
            return
        }
        val current = Session(nodeHex, routerHandle, identityHandle, selfDestHash)
        session = current
        val dest = RfedLink.ensure(app, nodeHex) ?: return
        if (ConnectionStateManager.appLinkStatus(dest) == RetichatBridge.AppLinkStatus.ACTIVE) {
            sendOpen(app, current)
        }
    }

    /** [nodeHex]'s rfed.link is ACTIVE again: bind it, if it is this session's node. */
    suspend fun onLinkActive(app: RetichatApp, nodeHex: String) {
        val current = session ?: return
        if (current.nodeHex != nodeHex.lowercase()) return
        sendOpen(app, current)
    }

    /** A `/lxmf/delivery` push on rfed.link. True when this device now holds it. */
    suspend fun onLinkPush(app: RetichatApp, blob: ByteArray): Boolean {
        val current = session ?: return false
        return onPush(app, current, blob)
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
        val response = RfedLink.request(app, current.nodeHex, "/propagation/stream/open", payload)
        if (openAccepted(response)) {
            Log.i(TAG, "propagation.stream open for ${current.selfDestHash.toHex().take(8)}")
            // What rfed pushed down this device's previous link while it was
            // dead went unproven and waits in its queue for /rfed/pull, and
            // without a wake nothing fetches it. Pull once per fresh link, as
            // the web does after its binding (Retichat-js
            // _bindRfedLinkForDistroPush, then /distro/pull): an event, not a
            // timer. Found on staging 2026-10-06: a sibling's channel leave,
            // pushed in the seconds before the re-open, never arrived.
            if (DistroManager.deliveryHashHex != null) {
                Log.i(TAG, "pulled ${RfedDistroClient.pull(app)} blob(s) after the open")
            }
        } else {
            Log.w(TAG, "propagation.stream open rejected resp=${response?.toHex() ?: "null"}")
        }
    }

    /**
     * One push: the bare LXMF propagation blob, `dest(16) | encrypted`. A blob
     * for the distro goes to the distro client (the router holds no
     * destination for it); one for this device to the router, as a sync
     * would; one for neither is refused (Retichat-js _onRfedLinkPush).
     */
    private suspend fun onPush(app: RetichatApp, current: Session, blob: ByteArray): Boolean {
        if (session !== current || blob.size <= 16) return false
        val destHex = blob.copyOfRange(0, 16).toHex()
        val distroHex = DistroManager.deliveryHashHex
        if (distroHex != null && destHex == distroHex) {
            Log.i(TAG, "distro push ${blob.size}B")
            return RfedDistroClient.handleBlob(app, blob)
        }
        if (destHex != current.selfDestHash.toHex()) {
            Log.i(TAG, "push for ${destHex.take(8)}, neither this device nor its distro — refused")
            return false
        }
        if (!RetichatBridge.routerIngestPropagated(current.routerHandle, blob)) {
            Log.w(TAG, "push not taken by the router (${blob.size}B): ${RetichatBridge.lastError()}")
            return false
        }
        return true
    }

    /** `[true, nil]`: msgpack fixarray-2 whose first element is true. */
    internal fun openAccepted(response: ByteArray?): Boolean =
        response != null && response.size >= 2 &&
            (response[0].toInt() and 0xff) == 0x92 && (response[1].toInt() and 0xff) == 0xc3

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
