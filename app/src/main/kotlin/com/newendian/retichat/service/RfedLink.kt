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
 *
 * Over a slow route the device holds no link ([holdsNoLink], James
 * 2026-11-05: "for LoRa/Slow connections we can't maintain a link to
 * RFed"): RFed wakes its own wake address instead of pushing (MeshWake),
 * the link opens for requests only, and it closes once none waits.
 */
object RfedLink {

    private const val TAG = "RfedLink"

    const val PUSH_DELIVERY = "/delivery"
    const val PUSH_LXMF = "/lxmf/delivery"
    const val PUSH_NOTIFY = "/notify"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logEscapedExceptions(TAG))

    /** Runs of requests ([holding]) under way on each node: hex → how many. */
    private val runs = java.util.concurrent.ConcurrentHashMap<String, Int>()

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

    /** Whether [nodeHex]'s rfed.link is ACTIVE now. */
    fun isActive(nodeHex: String): Boolean =
        destHash(nodeHex)?.let { ConnectionStateManager.appLinkStatus(it) } == RetichatBridge.AppLinkStatus.ACTIVE

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
        val setup = setUpOnce(setUp, node, identityHandle) {
            RetichatBridge.appLinkSetOutboundSetup(
                identityHandle,
                dest,
                listOf(PUSH_DELIVERY, PUSH_LXMF, PUSH_NOTIFY),
                object : AppLinkInboundRequestCallback {
                    override fun onRequest(path: String, data: ByteArray): Boolean = onPush(app, path, data)
                },
            )
        }
        if (setup is Setup.Failed) {
            // Opened without its setup, the link would not identify (rfed
            // refuses the pulls) and would have no /delivery handler (rfed
            // cannot push on it): it is not opened, and the next request tries
            // the setup again.
            val why = setup.error?.toString() ?: RetichatBridge.lastError()
            Log.e(TAG, "rfed.link setup failed for ${node.take(8)}, link not opened: $why", setup.error)
            return null
        }
        if (setup == Setup.Fresh) {
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

    /** What [setUpOnce] did. */
    internal sealed interface Setup {
        /** Set up before, with this identity. */
        data object Held : Setup

        /** Set up now. */
        data object Fresh : Setup

        /** Refused ([error] null) or threw: not marked, so the next try runs [setUpOnce]'s setup again. */
        data class Failed(val error: Throwable?) : Setup
    }

    /**
     * Run [setup] for [node] once per [identityHandle], marked in [marks]
     * first so a concurrent ensure does not run it twice. A false return or
     * a throw undoes the mark: on 10-19 a stale native library threw
     * UnsatisfiedLinkError, the node stayed marked, and every later ensure
     * opened the link without its setup.
     */
    internal fun setUpOnce(marks: MutableMap<String, Long>, node: String, identityHandle: Long, setup: () -> Boolean): Setup {
        val fresh = synchronized(marks) {
            if (marks[node] == identityHandle) false else { marks[node] = identityHandle; true }
        }
        if (!fresh) return Setup.Held
        val error = try {
            if (setup()) return Setup.Fresh
            null
        } catch (e: Throwable) {
            e
        }
        synchronized(marks) { if (marks[node] == identityHandle) marks.remove(node) }
        return Setup.Failed(error)
    }

    /**
     * A request on [nodeHex]'s rfed.link. Returns the response bytes, or null
     * when the link or the request failed inside the 5 s budget.
     */
    suspend fun request(app: RetichatApp, nodeHex: String, path: String, payload: ByteArray): ByteArray? {
        val dest = ensure(app, nodeHex) ?: return null
        val answer = ConnectionStateManager.appLinkSend(dest, "rfed", "link", path, payload)
        closeIfIdle(nodeHex, dest)
        return answer
    }

    /**
     * Whether this device holds no link to [nodeHex]: its route was measured
     * slow (ConnectionStateManager.slowRouteSend) and it hosts its own wake
     * address (MeshWake), so RFed wakes it when something waits. Then nothing
     * opens the link just to be pushed to.
     */
    fun holdsNoLink(nodeHex: String): Boolean {
        val dest = destHash(nodeHex) ?: return false
        return RetichatBridge.appLinkRouteSpeed(dest) == RetichatBridge.RouteSpeed.SLOW &&
            StackRuntime.meshRelayHex != null
    }

    /**
     * Run [block], a run of requests to [nodeHexes] sent one after another,
     * holding each node's link from one request to the next: a link this
     * device doesn't hold ([holdsNoLink]) closes when the run ends, not
     * between its requests (staging, 2026-11-05: closed after each channel
     * pull, every pull opened a link of its own over LoRa).
     */
    suspend fun <T> holding(nodeHexes: Collection<String>, block: suspend () -> T): T {
        val nodes = nodeHexes.map { it.lowercase() }.toSet()
        nodes.forEach { runs.merge(it, 1, Int::plus) }
        try {
            return block()
        } finally {
            for (node in nodes) {
                runs.computeIfPresent(node) { _, n -> if (n <= 1) null else n - 1 }
                destHash(node)?.let { closeIfIdle(node, it) }
            }
        }
    }

    suspend fun <T> holding(nodeHex: String, block: suspend () -> T): T = holding(listOf(nodeHex), block)

    /**
     * After a request or a run: a link this device doesn't hold closes once
     * no run holds it and no other request waits its turn on it.
     */
    private fun closeIfIdle(nodeHex: String, dest: ByteArray) {
        if (!holdsNoLink(nodeHex) || runs.containsKey(nodeHex.lowercase()) ||
            !ConnectionStateManager.slowRouteIdle(dest) ||
            ConnectionStateManager.appLinkStatus(dest) == RetichatBridge.AppLinkStatus.NONE
        ) return
        Log.i(TAG, "${nodeHex.take(8)}: slow route, no request waiting: link closed; RFed wakes this device")
        ConnectionStateManager.closeAppLink(dest)
    }

    /**
     * rfed's refusal of a pull (`/channel/pull`, `/distro/pull`), named: an
     * error is a msgpack integer (RFed-rust lxmf_propagation::encode_error),
     * 0xF0 as uint8 `cc f0`, with the LXMF reference codes (LXMF/LXMPeer.py).
     * Null for anything else, a page among them.
     */
    internal fun pullRefusal(resp: ByteArray): String? {
        val code = when {
            resp.size == 2 && resp[0] == 0xcc.toByte() -> resp[1].toInt() and 0xff
            resp.size == 1 && resp[0] >= 0 -> resp[0].toInt()
            else -> return null
        }
        val name = when (code) {
            0xf0 -> "link not identified"
            0xf1 -> "no access"
            0xf3 -> "invalid key"
            0xf4 -> "invalid request data"
            0xf5 -> "invalid stamp"
            0xf6 -> "throttled"
            0xfd -> "not found"
            0xfe -> "timed out"
            else -> "an unknown error"
        }
        return "$name (0x%02X)".format(code)
    }

    /** The stack stopped: the next stack sets every link up again with its own identity. */
    fun reset() {
        synchronized(setUp) { setUp.clear() }
        synchronized(statusListeners) { statusListeners.clear() }
    }

    /**
     * A fresh link: bind it for LXMF and channel pushes (Link.md "The client
     * re-binds on every link"). A link this device doesn't hold
     * ([holdsNoLink]) opened for requests and closes after them: what RFed
     * holds is collected in order instead, the channels with push on only
     * ([RfedCollect]).
     */
    private suspend fun bind(app: RetichatApp, node: String) {
        // The posts queued or held for a link go on its every ACTIVE edge;
        // launched, they wait for none of the binding below.
        app.rfedChannelClient.onRfedLinkActive()
        if (holdsNoLink(node)) {
            RfedCollect.request(app, "rfed.link opened", linkOpened = true)
            return
        }
        bindPushes(app, node)
        // What the node deferred while the link was down, collected now
        // instead of on a wake or rfed's re-send (~30 min late on 10-19).
        // Last: a page can take the request's 5 s.
        try {
            app.rfedChannelClient.pullDeferredForNode(node)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "channel pull on rfed.link ACTIVE failed", e)
        }
    }

    /**
     * Ask the node to push on this link: the propagation stream opens first,
     * with its pull of the distro queue (PropagationStream.sendOpen), then the
     * channel stream (James, 2026-11-05: "distro and propagation should be
     * first").
     */
    private suspend fun bindPushes(app: RetichatApp, node: String) {
        try {
            PropagationStream.onLinkActive(app, node)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "propagation stream open on rfed.link failed", e)
        }
        try {
            app.rfedChannelClient.sendChannelStreamConfigForNode(node)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "channel stream open on rfed.link failed", e)
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
