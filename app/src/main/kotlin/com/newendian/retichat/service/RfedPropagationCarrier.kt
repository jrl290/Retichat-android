package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.PropagationCarrierCallback
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * LXMF propagation over rfed.link (RFed-spec/Link.md, "LXMF propagation on
 * `rfed.link`", 2026-11-13). An RFed node that announces protocol version 2
 * takes the router's uploads and message get as requests on its rfed.link, so
 * this device holds one link to the node and none to its `lxmf.propagation`.
 *
 * The router hands each request here, and it goes as every other request on
 * the link does ([RfedLink.request]): over a slow route the link opens for it
 * and closes once nothing waits. With a node that announces version 1, or
 * none yet, the carrier is removed and the router links to
 * `lxmf.propagation` as before.
 */
object RfedPropagationCarrier {

    private const val TAG = "RfedPropagation"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logEscapedExceptions(TAG))

    /** The `lxmf.propagation` the carrier is installed for (hex). */
    @Volatile
    private var carried: String? = null

    /** Whether the router's propagation to [node] (an `lxmf.propagation` hash) goes on rfed.link. */
    fun carries(node: ByteArray): Boolean = carried == node.joinToString("") { "%02x".format(it) }

    /**
     * Install the router's carrier to [nodeHex]'s rfed.link when the node
     * announces version 2 with a propagation stamp cost, and remove it
     * otherwise; only for the RFed node this device propagates through
     * (Settings, else the default). Returns the `lxmf.propagation` hash the
     * carrier reaches: the router uses it while that is its propagation node.
     */
    fun sync(app: RetichatApp, nodeHex: String): ByteArray? {
        val node = nodeHex.lowercase()
        // Only the RFed node this device propagates through decides: it also
        // links to the nodes its channels live on, and a carrier to one of
        // those would take this one's place.
        if (node != UserPreferences.getEffectiveRfedNodeIdentityHash(app).lowercase()) return null
        val router = StackRuntime.routerHandle
        val dest = RfedLink.destHash(node)
        if (router == 0L || dest == null) return null
        val propagation = RetichatBridge.routerSyncRfedCarrier(router, dest, object : PropagationCarrierCallback {
            override fun onRequest(token: Long, path: String, data: ByteArray) {
                scope.launch {
                    var answer: ByteArray? = null
                    try {
                        answer = RfedLink.request(app, node, path, data)
                    } finally {
                        // Answered once, whatever happened: the router does
                        // not wait on a request that ended.
                        RetichatBridge.propagationCarrierComplete(token, answer)
                    }
                }
            }
        })
        val now = propagation?.joinToString("") { "%02x".format(it) }
        if (now != carried) {
            carried = now
            Log.i(
                TAG,
                if (now != null) "${node.take(8)}: LXMF propagation on rfed.link (protocol version 2)"
                else "${node.take(8)}: LXMF propagation on lxmf.propagation",
            )
            // What the distro outbox owes follows the propagation to its link.
            DistroOutboxRuntime.linkChanged()
        }
        return propagation
    }
}
