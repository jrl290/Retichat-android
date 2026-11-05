package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Registers this device's FCM "wakeup" relay with rfed: the signed
 * `/notify/register` and `/notify/unregister` requests on [RfedLink]
 * (RFed-spec Link.md), each proved by rfed's msgpack `true`. Before
 * 2026-10-18 they were signed DATA packets to the `rfed.notify.register` /
 * `rfed.notify.unregister` destinations.
 *
 * Direct port of `Retichat-ios/Retichat/Services/RfedNotifyRegistrar.swift`.
 *
 * The `relayHex` we send is the rfed-fcm bridge's own rfed.notify
 * destination hash (32 hex chars), loaded from `PushBridgeConfig.json`.
 * With a Bluetooth radio configured, this device's own `rfed.notify` is
 * registered beside it ([registerMeshRelay], MeshWake.kt).
 *
 * Payload signed-array layout (matches iOS verbatim):
 *   value = msgpack fixarray-3 [str(op), str(relay_hex)|nil, bin(16 channel_hash) | nil]
 *   wire  = msgpack fixarray-3 [bin(value), bin(64) pubkey, bin(64) sig]
 *
 * Driven by the rfed.notify.register APP_LINK status callback so we only fire
 * once the persistent link is ACTIVE — never on a 5 s cold-start timeout, and
 * never with app-level retries.  See DESIGN_PRINCIPLES.md §1, §2, §3.
 */
object RfedNotifyRegistrar {

    private const val TAG = "RfedNotify"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val REGISTER = "/notify/register"
    private const val UNREGISTER = "/notify/unregister"

    /** One per RFed node (its rfed.link). */
    private val registrations = HashMap<String, HeldLinkRegistrations>()

    private fun registrationsFor(context: Context, node: String): HeldLinkRegistrations? {
        val app = context.applicationContext as? RetichatApp ?: return null
        val dest = RfedLink.destHash(node) ?: return null
        return synchronized(registrations) {
            registrations.getOrPut(node) {
                HeldLinkRegistrations(dest, "rfed", "link", scope, RfedLinkOps(app, node, REGISTER, scope))
            }
        }
    }

    /**
     * Owe the node this device's LXMF wake registration: sent on a held link
     * to `rfed.notify.register` once it is established, with its delivery
     * proof, and owed until then (see [HeldLinkRegistrations]). Already
     * registered in this process: nothing is sent.
     */
    fun registerIfNeeded(context: Context, identityHandle: Long) {
        val node = rfedNode(context) ?: return
        val relayHex = relayHexOrNull(context) ?: run {
            Log.i(TAG, "PushBridgeConfig.json missing or invalid — skipping register")
            return
        }
        val payload = buildSignedPayload(identityHandle, "register", relayHex, channelHash = null) ?: run {
            Log.w(TAG, "Failed to sign payload")
            return
        }
        val key = "lxmf relay=${relayHex.take(8)} identity=$identityHandle"
        scope.launch { registrationsFor(context, node)?.owe(key, payload) }
    }

    /**
     * Per-channel registration: owed like [registerIfNeeded]. While this
     * device hosts its own relay ([StackRuntime.meshRelayHex], MeshWake) that
     * relay is registered for the channel too, so the channel's wakes also
     * come over the mesh: they follow the channel's push toggle (James,
     * 2026-11-05).
     */
    fun registerForChannel(context: Context, identityHandle: Long, channelHash: ByteArray) {
        val node = rfedNode(context) ?: return
        StackRuntime.meshRelayHex?.let { meshRelayHex ->
            val payload = buildSignedPayload(identityHandle, "register", meshRelayHex, channelHash)
            if (payload != null) {
                val key = channelKey(channelHash, meshRelayHex, identityHandle)
                scope.launch { oweMeshRegistrations(context, node, listOf(key to payload)) }
            }
        }
        val relayHex = relayHexOrNull(context) ?: return
        val payload = buildSignedPayload(identityHandle, "register", relayHex, channelHash) ?: return
        scope.launch { registrationsFor(context, node)?.owe(channelKey(channelHash, relayHex, identityHandle), payload) }
    }

    private fun channelKey(channelHash: ByteArray, relayHex: String, identityHandle: Long): String =
        "channel ${channelHash.toHex().take(8)} relay=${relayHex.take(8)} identity=$identityHandle"

    /**
     * Per-channel deregistration — best-effort single attempt, for the FCM
     * bridge's relay and, while it is hosted, this device's own.
     */
    fun deregisterForChannel(context: Context, identityHandle: Long, channelHash: ByteArray) {
        val node = rfedNode(context) ?: return
        val relays = listOfNotNull(relayHexOrNull(context), StackRuntime.meshRelayHex)
        for (relayHex in relays) {
            val payload = buildSignedPayload(identityHandle, "unregister", relayHex, channelHash) ?: continue
            scope.launch {
                // A registration still owed must not go out after this.
                registrationsFor(context, node)?.forget(channelKey(channelHash, relayHex, identityHandle))
                logSendResult("channel-unregister", sendOnce(context, node, UNREGISTER, payload))
            }
        }
    }

    /**
     * Best-effort unregister from an old rfed node (its identity hash) before
     * switching nodes, and the same for this device's own relay when one is
     * registered (the next start registers it with the new node).
     */
    fun deregisterFrom(context: Context, identityHandle: Long, oldRfedNodeIdentityHex: String) {
        val node = oldRfedNodeIdentityHex.lowercase().takeIf { RfedLink.destHash(it) != null } ?: return
        UserPreferences.getMeshWakeRelayHex(context)?.let { meshRelayHex ->
            val payload = buildSignedPayload(identityHandle, "unregister", meshRelayHex, channelHash = null)
            if (payload != null) {
                scope.launch { logSendResult("mesh-unregister-old", sendOnce(context, node, UNREGISTER, payload)) }
            }
        }
        val relayHex = relayHexOrNull(context) ?: return
        val payload = buildSignedPayload(identityHandle, "unregister", relayHex, channelHash = null) ?: return
        scope.launch { logSendResult("unregister-old", sendOnce(context, node, UNREGISTER, payload)) }
    }

    // ---- Mesh wake (MeshWake.kt) ----
    //
    // Port of registerMeshRelay / unregisterMeshRelay in
    // Retichat-ios/Retichat/Services/RfedNotifyRegistrar.swift, with the
    // relay's channel registrations added (James, 2026-11-05).

    private fun meshKey(relayHex: String, identityHandle: Long): String =
        "lxmf mesh relay=${relayHex.take(8)} identity=$identityHandle"

    /** One per RFed node: the mesh relay's unregistrations, owed like registrations. */
    private val meshUnregistrations = HashMap<String, HeldLinkRegistrations>()

    /**
     * The mesh relay's unregistrations rfed has not answered true yet (key →
     * payload), and the relay they are for. The saved relay
     * ([UserPreferences.getMeshWakeRelayHex]) is forgotten once none is left,
     * so a stop before then unregisters it again at the next start. rfed
     * answers true to an unregistration of a registration it does not hold,
     * so one sent twice still ends this.
     */
    private val meshUnproved = HashMap<String, ByteArray>()
    private var meshUnprovedRelayHex: String? = null

    private fun meshUnregistrationsFor(context: Context, node: String): HeldLinkRegistrations? {
        val app = context.applicationContext as? RetichatApp ?: return null
        val dest = RfedLink.destHash(node) ?: return null
        return synchronized(meshUnregistrations) {
            meshUnregistrations.getOrPut(node) {
                val ops = ProvedOps(RfedLinkOps(app, node, UNREGISTER, scope)) { payload -> meshUnregistrationProved(app, payload) }
                HeldLinkRegistrations(dest, "rfed", "link", scope, ops)
            }
        }
    }

    /**
     * Register this device's own relay (`rfed.notify` under its identity) for
     * its LXMF wakes, beside the FCM bridge's: RFed wakes every relay a
     * device registered, so this one gets the wake over the mesh. Also for
     * each of [channelHashes], the channels whose push toggle is on. Owed to
     * the node like the FCM relay's registration. Unregistrations of the
     * relay still owed from before are withdrawn.
     */
    fun registerMeshRelay(context: Context, identityHandle: Long, relayHex: String, channelHashes: List<ByteArray>) {
        val node = rfedNode(context) ?: return
        val items = meshPayloads("register", identityHandle, relayHex, channelHashes) ?: run {
            Log.w(TAG, "Failed to sign the mesh relay payload")
            return
        }
        synchronized(meshUnproved) {
            meshUnproved.clear()
            meshUnprovedRelayHex = null
        }
        scope.launch { oweMeshRegistrations(context, node, items) }
    }

    /**
     * Unregister the mesh relay from the current node (no Bluetooth radio is
     * configured any more), for its LXMF wakes and for each of
     * [channelHashes], owed until rfed answers true; then the saved relay is
     * forgotten.
     */
    fun unregisterMeshRelay(context: Context, identityHandle: Long, relayHex: String, channelHashes: List<ByteArray>) {
        val node = rfedNode(context) ?: return
        val items = meshPayloads("unregister", identityHandle, relayHex, channelHashes) ?: run {
            Log.w(TAG, "Failed to sign the mesh relay payload")
            return
        }
        synchronized(meshUnproved) {
            meshUnproved.clear()
            items.forEach { (key, payload) -> meshUnproved[key] = payload }
            meshUnprovedRelayHex = relayHex
        }
        scope.launch {
            // A registration still owed must not go out after this.
            val registrations = registrationsFor(context, node)
            items.forEach { (key, _) -> registrations?.forget(key) }
            val unregistrations = meshUnregistrationsFor(context, node) ?: return@launch
            for ((key, payload) in items) {
                // Delivered in an earlier round of this process: owed again.
                unregistrations.forget(key)
                unregistrations.owe(key, payload)
            }
        }
    }

    /** The LXMF registration (or unregistration) and one per channel, key → signed payload; null if one could not be signed. */
    private fun meshPayloads(
        operation: String,
        identityHandle: Long,
        relayHex: String,
        channelHashes: List<ByteArray>,
    ): List<Pair<String, ByteArray>>? {
        val lxmf = buildSignedPayload(identityHandle, operation, relayHex, channelHash = null) ?: return null
        val channels = channelHashes.map { channelHash ->
            val payload = buildSignedPayload(identityHandle, operation, relayHex, channelHash) ?: return null
            channelKey(channelHash, relayHex, identityHandle) to payload
        }
        return listOf(meshKey(relayHex, identityHandle) to lxmf) + channels
    }

    /** Owe [items] as registrations; an unregistration of the same key still owed is withdrawn first, so it cannot go out after them. */
    private suspend fun oweMeshRegistrations(context: Context, node: String, items: List<Pair<String, ByteArray>>) {
        val unregistrations = meshUnregistrationsFor(context, node)
        items.forEach { (key, _) -> unregistrations?.forget(key) }
        val registrations = registrationsFor(context, node) ?: return
        for ((key, payload) in items) registrations.owe(key, payload)
    }

    /** rfed answered true to one of the mesh relay's unregistrations: forget the relay once all are. */
    private fun meshUnregistrationProved(context: Context, payload: ByteArray) {
        val relayHex = synchronized(meshUnproved) {
            val key = meshUnproved.entries.firstOrNull { it.value.contentEquals(payload) }?.key ?: return
            meshUnproved.remove(key)
            if (meshUnproved.isNotEmpty()) return
            meshUnprovedRelayHex.also { meshUnprovedRelayHex = null } ?: return
        }
        if (UserPreferences.getMeshWakeRelayHex(context) == relayHex) {
            UserPreferences.setMeshWakeRelayHex(context, null)
        }
        Log.i(TAG, "Own relay ${relayHex.take(8)} unregistered from RFed; forgotten")
    }

    /** [HeldLinkOps] that also tells [proved] each payload rfed answered true. Port of `ProvedOps` in the iOS registrar. */
    private class ProvedOps(
        private val ops: HeldLinkOps,
        private val proved: (ByteArray) -> Unit,
    ) : HeldLinkOps by ops {
        override suspend fun sendData(destHash: ByteArray, app: String, aspectsCsv: String, payload: ByteArray): Boolean {
            val ok = ops.sendData(destHash, app, aspectsCsv, payload)
            if (ok) proved(payload)
            return ok
        }
    }

    // ---- Internal ----

    /// Single attempt: one request on the node's rfed.link, proved by its `true`.
    /// // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
    private suspend fun sendOnce(context: Context, node: String, path: String, payload: ByteArray): Boolean {
        val app = context.applicationContext as? RetichatApp ?: return false
        val answer = RfedLink.request(app, node, path, payload)
        return answer != null && answer.size == 1 && answer[0] == 0xc3.toByte()
    }

    private fun logSendResult(kind: String, delivered: Boolean) {
        if (delivered) Log.i(TAG, "$kind: rfed answered true")
        else Log.w(TAG, "$kind: no true from rfed within budget")
    }

    /** The configured RFed node's identity hash, lowercased, or null when there is none. */
    private fun rfedNode(context: Context): String? =
        UserPreferences.getEffectiveRfedNodeIdentityHash(context).lowercase()
            .takeIf { it.isNotEmpty() && RfedLink.destHash(it) != null }

    private fun relayHexOrNull(context: Context): String? {
        return FcmBridgeHashes.relayHex(context)
    }

    /**
     * Build the msgpack-3 signed payload:
     *   value = fixarray-3 [str(op), str(relayHex)|nil, bin(16) channelHash | nil]
     *   wire  = fixarray-3 [bin(value), bin(64) pubkey, bin(64) sig]
     */
    private fun buildSignedPayload(
        identityHandle: Long,
        operation: String,
        relayHex: String?,
        channelHash: ByteArray?,
    ): ByteArray? {
        val value = ArrayList<Byte>(80)
        value.add(0x93.toByte())                     // fixarray-3
        val opBytes = operation.toByteArray()
        value.add((0xa0 or opBytes.size).toByte())
        value.addAll(opBytes.toList())
        if (relayHex != null) {
            val relayBytes = relayHex.toByteArray()
            when {
                relayBytes.size <= 31 -> value.add((0xa0 or relayBytes.size).toByte())
                relayBytes.size <= 0xff -> {
                    value.add(0xd9.toByte())
                    value.add(relayBytes.size.toByte())
                }
                else -> {
                    value.add(0xda.toByte())
                    value.add(((relayBytes.size shr 8) and 0xff).toByte())
                    value.add((relayBytes.size and 0xff).toByte())
                }
            }
            value.addAll(relayBytes.toList())
        } else {
            value.add(0xc0.toByte())
        }
        if (channelHash != null) {
            value.add(0xc4.toByte())
            value.add(channelHash.size.toByte())
            value.addAll(channelHash.toList())
        } else {
            value.add(0xc0.toByte())                 // nil
        }
        val valueBytes = value.toByteArray()
        val pubkey = RetichatBridge.identityPublicKey(identityHandle) ?: return null
        val sig = RetichatBridge.identitySign(identityHandle, valueBytes) ?: return null
        return msgpackFixarray3Bin(valueBytes, pubkey, sig)
    }

    private fun msgpackFixarray3Bin(a: ByteArray, b: ByteArray, c: ByteArray): ByteArray {
        val out = ArrayList<Byte>(3 + 2 * 3 + a.size + b.size + c.size)
        out.add(0x93.toByte())
        for (item in listOf(a, b, c)) {
            if (item.size <= 0xff) {
                out.add(0xc4.toByte()); out.add(item.size.toByte())
            } else {
                out.add(0xc5.toByte())
                out.add(((item.size shr 8) and 0xff).toByte())
                out.add((item.size and 0xff).toByte())
            }
            out.addAll(item.toList())
        }
        return out.toByteArray()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}

