package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.AppLinkInboundRequestCallback
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * `rfed.link`: one held link to the RFed node that carries every RFed
 * operation, both ways (RFed-spec/Link.md). The client's requests go as
 * requests on their path (`/channel/subscribe`, `/channel/publish`,
 * `/distro/pull`, ...); the node's pushes come back on the same link as
 * requests to `/delivery` (channel posts), `/lxmf/delivery` (LXMF messages
 * for this device or its distro) and `/notify` (a wake), and this client's
 * answer to each is the delivery proof.
 *
 * Before 2026-10-18 Android used a destination per operation: a link per
 * aspect, publishes as unanswered DATA, and pushes on two stream links.
 * The web moved to rfed.link in September (Retichat-js `_rfedRequest`,
 * `_onRfedLinkPush`); this is its port.
 *
 * The link identifies as this device once established, before ACTIVE, so
 * the pulls that authenticate by link identity can follow at once, and its
 * push handlers are set before the link is created (a link captures its
 * destination at creation). Pushes are bound per link: every ACTIVE edge
 * sends the stream-open requests again.
 */
object RfedLink {

    private const val TAG = "RfedLink"

    const val PUSH_DELIVERY = "/delivery"
    const val PUSH_LXMF = "/lxmf/delivery"
    const val PUSH_NOTIFY = "/notify"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logEscapedExceptions(TAG))

    /** Nodes whose rfed.link this stack has set up: hex → identity handle it was set up with. */
    private val setUp = mutableMapOf<String, Long>()

    /** Others that follow a node's link status (the registrations owed on it): node → owner → handler. */
    private val statusListeners = mutableMapOf<String, MutableMap<String, (Int) -> Unit>>()

    /**
     * Follow [nodeHex]'s rfed.link status as [owner] (one handler per owner,
     * replaced on a repeat). ConnectionStateManager keeps one status handler
     * per destination, and on rfed.link that one is this object's.
     */
    fun setStatusListener(nodeHex: String, owner: String, handler: (Int) -> Unit) {
        synchronized(statusListeners) {
            statusListeners.getOrPut(nodeHex.lowercase()) { mutableMapOf() }[owner] = handler
        }
    }

    /** The `rfed.link` destination hash of [nodeHex]. */
    fun destHash(nodeHex: String): ByteArray? =
        RfedChannelClient.rfedDestHash(nodeHex.lowercase(), "rfed", listOf("link"))
            ?.let { FcmTokenRegistrar.hexToBytes(it) }

    /**
     * Set up the link to [nodeHex] (once per node and identity) and start
     * opening it. Returns its destination hash, or null with no identity or
     * a bad node hash.
     */
    suspend fun ensure(app: RetichatApp, nodeHex: String): ByteArray? {
        val node = nodeHex.lowercase()
        val dest = destHash(node) ?: return null
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return null
        val fresh = synchronized(setUp) {
            if (setUp[node] == identityHandle) false else { setUp[node] = identityHandle; true }
        }
        if (fresh) {
            val ok = RetichatBridge.appLinkSetOutboundSetup(
                identityHandle,
                dest,
                listOf(PUSH_DELIVERY, PUSH_LXMF, PUSH_NOTIFY),
                object : AppLinkInboundRequestCallback {
                    override fun onRequest(path: String, data: ByteArray): Boolean = onPush(app, path, data)
                },
            )
            if (!ok) {
                Log.w(TAG, "rfed.link setup refused for ${node.take(8)}: ${RetichatBridge.lastError()}")
                synchronized(setUp) { setUp.remove(node) }
                return null
            }
            ConnectionStateManager.setAppLinkStatusHandler(dest) { status ->
                val listeners = synchronized(statusListeners) { statusListeners[node]?.values?.toList().orEmpty() }
                listeners.forEach { runCatching { it(status) } }
                if (status != RetichatBridge.AppLinkStatus.ACTIVE) return@setAppLinkStatusHandler
                scope.launch { bind(app, node) }
            }
        }
        if (ConnectionStateManager.appLinkStatus(dest) != RetichatBridge.AppLinkStatus.ACTIVE) {
            ConnectionStateManager.primeAppLink(dest, "rfed", "link")
        }
        return dest
    }

    /**
     * A request on [nodeHex]'s rfed.link. Returns the response bytes, or null
     * when the link or the request failed inside the 5 s budget.
     */
    suspend fun request(app: RetichatApp, nodeHex: String, path: String, payload: ByteArray): ByteArray? {
        val dest = ensure(app, nodeHex) ?: return null
        return ConnectionStateManager.appLinkSend(dest, "rfed", "link", path, payload)
    }

    /** The stack stopped: the next stack sets every link up again with its own identity. */
    fun reset() {
        synchronized(setUp) { setUp.clear() }
        synchronized(statusListeners) { statusListeners.clear() }
    }

    /** A fresh link: bind it for channel and LXMF pushes (Link.md "The client re-binds on every link"). */
    private suspend fun bind(app: RetichatApp, node: String) {
        try {
            app.rfedChannelClient.sendChannelStreamConfigForNode(node)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "channel stream open on rfed.link failed", e)
        }
        try {
            PropagationStream.onLinkActive(app, node)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "propagation stream open on rfed.link failed", e)
        }
    }

    /**
     * A push from the node. True only when this device now holds the blob;
     * false is a refusal the node records instead of deferring (Link.md
     * "The response is the delivery proof"). Runs on a link worker thread,
     * which waits for the answer.
     */
    private fun onPush(app: RetichatApp, path: String, data: ByteArray): Boolean = try {
        when (path) {
            PUSH_DELIVERY -> runBlocking(Dispatchers.IO) { app.rfedChannelClient.dispatchInboundBlob(data) }
            PUSH_LXMF -> runBlocking(Dispatchers.IO) { PropagationStream.onLinkPush(app, data) }
            PUSH_NOTIFY -> {
                // A wake: something was deferred for this device, collected with
                // /distro/pull. Acknowledged at once; the pull is its own request
                // (Retichat-js _onRfedLinkPush).
                scope.launch { if (DistroManager.deliveryHashHex != null) RfedDistroClient.pull(app) }
                true
            }
            else -> false
        }
    } catch (e: Exception) {
        Log.e(TAG, "push on $path failed", e)
        false
    }
}
