package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.DistroDirectCallback
import com.newendian.retichat.bridge.DistroDirectSendCallback
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.service.DistroCodec.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * The rules of offline use of a distro address (RFed-rust SPEC.md §17.15) that
 * [DistroOffline] decides by: pure, so the JVM tests pin them.
 */
object DistroOfflineRules {
    /** `<random>`: 16 lowercase hex characters, from 8 random bytes. */
    fun isRandomPart(s: String): Boolean = s.length == 16 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** The name Transport gives an RTNode reached over Bluetooth (Reticulum-rust prns-ble `interface_name`). */
    const val RTNODE_BLUETOOTH_PREFIX = "PrnsBLE["

    /** The interface types of a saved row that is an RNode. */
    val RNODE_TYPES = setOf("RNodeInterface", "RNodeMultiInterface")

    /**
     * A mesh connection: an interface to a radio mesh, an RTNode over Bluetooth
     * or an RNode ([rnodeNames], the names of the saved RNode rows). Every
     * other interface is an internet connection (§17.15).
     */
    fun isMesh(name: String, rnodeNames: Set<String>): Boolean =
        name.startsWith(RTNODE_BLUETOOTH_PREFIX) || name in rnodeNames

    /**
     * Whether the device is offline (§17.15): its offline toggle is on, it holds
     * a distro, its link to RFed failed to come up or closed ([rfedReached]
     * false; null, nothing known yet, is not), and none of the interfaces
     * [online] is an internet connection. Losing RFed alone never makes a device
     * offline while an internet connection is up (James: "it cannot be
     * triggered by RFed disconnect alone").
     */
    fun offline(enabled: Boolean, holdsDistro: Boolean, rfedReached: Boolean?, online: Collection<String>, rnodeNames: Set<String>): Boolean =
        enabled && holdsDistro && rfedReached == false && online.all { isMesh(it, rnodeNames) }

    /**
     * What a status of the link to RFed says: reached (ACTIVE, RECOVERED), not
     * reached (DISCONNECTED: it failed to come up, or closed), or neither (null:
     * NONE is a link closed on purpose, as a slow route closes its link after
     * its requests, or no link at all; the rest are on the way). An event, never
     * slowness.
     */
    fun reached(status: Int): Boolean? = when (status) {
        RetichatBridge.AppLinkStatus.ACTIVE, RetichatBridge.AppLinkStatus.RECOVERED -> true
        RetichatBridge.AppLinkStatus.DISCONNECTED -> false
        else -> null
    }

    /** The lookup address's answer while offline: the device's own offline address first, then its siblings heard offline. */
    fun lookup(own: String, siblings: Collection<String>): List<String> = listOf(own) + siblings.filter { it != own }

    /** A message the router still holds for the propagation node: it has not reached RFed. */
    fun waitingForRfed(state: Int): Boolean = state == RetichatBridge.MessageState.GENERATING ||
        state == RetichatBridge.MessageState.OUTBOUND || state == RetichatBridge.MessageState.SENDING

    /**
     * Whether a sender asks for a distro's offline devices at a send (§17.15):
     * a mesh connection is up, and since one last came up it has neither asked
     * for this distro nor heard from any of its offline addresses.
     */
    fun asks(meshUp: Boolean, askedSince: Boolean, heardSince: Boolean): Boolean = meshUp && !askedSince && !heardSince
}

/**
 * Offline use of a distro address on Android (RFed-rust SPEC.md §17.15, James
 * 2026-11-17): the device that cannot reach RFed, and the sender that reaches
 * it over a mesh.
 *
 * **The device.** While its offline toggle is on ([setEnabled], Settings'
 * "Offline use", off by default) and it is offline ([DistroOfflineRules.offline]),
 * it holds its offline address `rfed.distro.direct.<random>` and the lookup
 * address `rfed.distro.direct`, both with the distro's key, until the app
 * restarts; `<random>` is made once per distro and kept
 * ([UserPreferences.distroOfflineRandom]). It announces the offline address,
 * and the lookup address with it (2026-11-18: relays then keep the lookup's
 * answer), on each of its mesh connections when it goes offline, and on a mesh
 * connection that comes up while it is offline; never on a timer. Once it
 * reaches RFed again, or the toggle is turned off, it is back: it announces
 * once on its mesh connections that it is back, and the lookup answers nothing
 * and is not announced. A message that comes to the offline address is kept as
 * Distro mail from RFed is ([RfedDistroClient.takeDirect]) and owed to RFed as
 * a sent-copy is ([DistroOutboxRuntime.oweDirect]).
 *
 * **The sender.** When this device sends a message to a distro, it also sends
 * the same sealed message, with the same stamp, to each offline address of
 * that distro that is live (a fan-out, [sentToDistro]). It keeps every offline
 * address it learns ([UserPreferences.keepDistroOfflineAddress]); one is live
 * from when its announce, or an answer for it, says it is offline, or a lookup
 * names it, until one says it is back or a send to it fails. It watches every
 * distro it knows (at start, at a send, and when it keeps a new address): the
 * distro's lookup address and each kept offline address pass the app's drop of
 * announces, so it hears a device go offline when it announces (2026-11-18).
 * Once per distro each time a mesh connection comes up, a send still asks over
 * the mesh connections, for a device it missed: a path request for each kept
 * address and for the lookup. A message that went direct first and fell back
 * to RFed gets its copy too ([fallbackSent]). When an address becomes live, it
 * is sent each message for the distro still waiting to reach RFed, and each
 * sent after asking, that it has not had. A failed send is said
 * in the log and never repeated (DESIGN_PRINCIPLES §3); the message still goes
 * through RFed. A send the device proves, or answers it holds, shows the
 * message delivered.
 *
 * Every event runs on one thread (`distro-offline`), in order. The RFed link's
 * status, the interfaces' edges and the announces heard are its events; it
 * starts no timer.
 */
object DistroOffline {
    private const val TAG = "DistroOffline"
    private const val LISTENER = "distro-offline"

    private val serial = Executors.newSingleThreadExecutor { r -> Thread(r, "distro-offline").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var app: RetichatApp? = null
    @Volatile private var started = false
    private var distroWatch = false

    // ---- The device (read and written on [serial]) ----

    /** The names of the saved RNode rows, read at the stack's start. */
    private var rnodeNames: Set<String> = emptySet()

    /** The interfaces online at the last edge. */
    private var online: Set<String> = emptySet()

    /** What the link to RFed last said: reached, not reached, or nothing yet in this stack. */
    private var rfedReached: Boolean? = null

    /** Offline: went offline and is not back. Read by [callback] on Rust threads. */
    @Volatile private var offline = false

    /** The pair of addresses held for the distro, once it has gone offline in this run. */
    private class HeldPair(val distroHex: String, val random: String, val offline: ByteArray)
    private var held: HeldPair? = null

    /** Siblings' offline addresses heard announced offline since this device last went offline. */
    private val siblings = LinkedHashSet<String>()

    // ---- The sender (read and written on [serial]) ----

    /** A copy of a message sent to a distro: the outbound message's [handle] says whether it has reached RFed. */
    private class Copy(val localId: String, val msgHash: String, val bytes: ByteArray, val handle: Long, var afterAsk: Boolean) {
        val sentTo = HashSet<String>()
    }

    /** Distro (hex) -> the copies kept for its offline addresses. */
    private val copies = HashMap<String, MutableList<Copy>>()

    /** Distro (hex) -> its offline addresses (random parts) live now. */
    private val live = HashMap<String, MutableSet<String>>()

    /** Distros asked for since a mesh connection last came up. */
    private val asked = HashSet<String>()

    /** Distros heard from (an offline address's announce or answer, a lookup answer) since a mesh connection last came up. */
    private val heard = HashSet<String>()

    /** Distro (hex) -> the kept random parts watched for in this run, its lookup with them. */
    private val watching = HashMap<String, Set<String>>()

    // ---- Start and stop ----

    /**
     * The stack is up (its bootstrap, once the router exists): read the saved
     * RNode rows, listen to the link to RFed and to what comes to the offline
     * address. What was held in an earlier start stays held (Reticulum keeps
     * it until the app restarts), and so does being offline; what the new
     * stack's link to RFed says decides from here, and the mesh connections
     * already up count as come up ([startedWithMeshUp]).
     */
    suspend fun start(app: RetichatApp) {
        this.app = app
        val rnodes = app.database.interfaceConfigDao().enabledInterfaces()
            .filter { it.type in DistroOfflineRules.RNODE_TYPES }.map { it.name }.toSet()
        withContext(serial) {
            rnodeNames = rnodes
            online = RetichatBridge.onlineInterfaces().toSet()
            rfedReached = null
            // The sender listens for every distro it knows, not only those it sends to in this run.
            watching.clear()
            for (d in UserPreferences.getDistroContacts(app)) watch(d)
        }
        if (!RetichatBridge.distroDirectRegister(callback)) {
            Log.e(TAG, "offline use of the distro address not registered: ${RetichatBridge.lastError()}")
        }
        val node = UserPreferences.getEffectiveRfedNodeIdentityHash(app)
        if (node.length == 32) {
            RfedLink.setStatusListener(node, LISTENER) { status -> scope.launch { onRfedStatus(status) } }
        }
        started = true
        // After the registrations: what an announce brings finds them in place.
        scope.launch { startedWithMeshUp() }
        if (!distroWatch) {
            distroWatch = true
            scope.launch { DistroManager.state.collect { evaluate("the distro held changed") } }
        }
    }

    /** The stack is stopping: nothing is told until the next start, and a copy that comes meanwhile is refused (said). */
    fun stop() {
        started = false
        if (RetichatBridge.isLoaded) RetichatBridge.distroDirectUnregister()
    }

    /**
     * The offline toggle (Settings, "Offline use"): turned on, the device goes
     * offline when it next is; turned off while offline, it is back.
     */
    fun setEnabled(context: Context, on: Boolean) {
        UserPreferences.setDistroOfflineEnabled(context, on)
        scope.launch { evaluate(if (on) "the offline toggle was turned on" else "the offline toggle was turned off") }
    }

    /** An interface came up or went down (Transport's edge: the stack's interface count callback). */
    fun onInterfaceEdge() {
        if (!started) return
        scope.launch { interfacesChanged() }
    }

    // ---- The device ----

    private fun isMesh(name: String) = DistroOfflineRules.isMesh(name, rnodeNames)

    private fun interfacesChanged() {
        val app = app ?: return
        val now = RetichatBridge.onlineInterfaces().toSet()
        val meshUp = (now - online).filter(::isMesh)
        online = now
        if (meshUp.isNotEmpty()) {
            meshCameUp(meshUp, "the mesh connection ${meshUp.joinToString()} came up")
            // A device that holds no link to RFed (a slow route) tries RFed when a mesh connection
            // comes up, and decides from that attempt (§17.15).
            val node = UserPreferences.getEffectiveRfedNodeIdentityHash(app)
            if (UserPreferences.isDistroOfflineEnabled(app) && rfedReached != true && node.length == 32 && RfedLink.holdsNoLink(node)) {
                RfedCollect.request(app, "a mesh connection came up (§17.15)")
            }
        }
        evaluate("the interfaces changed")
    }

    /**
     * Mesh connections came up (§17.15): the sender asks for each distro once
     * more, at its next send, and an offline device announces its offline
     * address on them. For one that comes up while the stack runs
     * ([interfacesChanged]) and for those already up when it starts
     * ([startedWithMeshUp]).
     */
    private fun meshCameUp(meshUp: List<String>, why: String) {
        // The sender: each distro is asked for once more, at its next send.
        asked.clear()
        heard.clear()
        copies.values.forEach { list -> list.forEach { it.afterAsk = false } }
        // The device: offline, it announces on a mesh connection that comes up.
        held?.takeIf { offline }?.let { announce(it, meshUp, why) }
    }

    /**
     * A new stack's mesh connections are new ones, whether they came up before
     * [start] or after it: those already up count as come up here, as a later
     * one does in [interfacesChanged]. Settings' Apply restarts the stack inside
     * the process (StackRuntime.restart), and its connections come up before
     * [start] runs, so until 2026-11-18 the old stack's asked and heard carried
     * over and the next send to a distro did not ask (the M5 device test). Not
     * [interfacesChanged]'s attempt at RFed: the stack's own start tries RFed.
     */
    private fun startedWithMeshUp() {
        if (!started) return
        val meshUp = online.filter(::isMesh)
        if (meshUp.isNotEmpty()) meshCameUp(meshUp, "the stack started with ${meshUp.joinToString()} up")
    }

    private fun onRfedStatus(status: Int) {
        val reached = DistroOfflineRules.reached(status) ?: return
        if (reached == rfedReached) return
        rfedReached = reached
        evaluate(if (reached) "RFed was reached" else "the link to RFed failed or closed")
    }

    /** Offline or back, by what is known now. */
    private fun evaluate(why: String) {
        val app = app ?: return
        val enabled = UserPreferences.isDistroOfflineEnabled(app)
        val signing = DistroManager.signing()
        held?.takeIf { offline }?.let { pair ->
            val back = when {
                !enabled -> "the offline toggle was turned off"
                rfedReached == true -> "RFed was reached again"
                pair.distroHex != signing?.first?.deliveryHashHex -> "the distro held changed"
                else -> null
            }
            if (back != null) goBack(pair, back)
        }
        if (!offline && signing != null && DistroOfflineRules.offline(enabled, true, rfedReached, online, rnodeNames)) {
            goOffline(signing.first, signing.second, why)
        }
    }

    private fun goOffline(distro: DistroManager.DistroState, handle: Long, why: String) {
        val app = app ?: return
        val random = UserPreferences.distroOfflineRandom(app, distro.deliveryHashHex) ?: run {
            Log.e(TAG, "offline ($why), but its random part could not be kept: no offline address is held (§17.15)")
            return
        }
        val node = PropagationSync.rfedDerivedPropagationHash(app)?.let(DistroCodec::hexToBytes)
        if (node == null) Log.w(TAG, "the RFed node's lxmf.propagation is not known: a message that comes direct cannot have its stamp checked, and is refused")
        val ratchets = File(app.filesDir, "distro-direct").apply { mkdirs() }
            .resolve("${distro.deliveryHashHex}-$random.ratchets").absolutePath
        siblings.clear()
        val address = RetichatBridge.distroDirectHold(handle, random, ratchets, node, back = false, lookup = arrayOf(random)) ?: run {
            Log.e(TAG, "offline ($why), but the addresses could not be held: ${RetichatBridge.lastError()} (§17.15)")
            return
        }
        val pair = HeldPair(distro.deliveryHashHex, random, address)
        held = pair
        offline = true
        Log.i(TAG, "offline ($why): the distro ${distro.deliveryHashHex.take(8)} is reached at ${address.toHex().take(8)} over the mesh (§17.15)")
        announce(pair, online.filter(::isMesh), "it went offline")
    }

    private fun goBack(pair: HeldPair, why: String) {
        offline = false
        siblings.clear()
        if (!RetichatBridge.distroDirectSetAppData(pair.offline, back = true, lookup = emptyArray())) {
            Log.w(TAG, "back ($why), but the addresses' app_data was not set: ${RetichatBridge.lastError()}")
        }
        Log.i(TAG, "back ($why): the offline address says so once on each mesh connection (§17.15)")
        announce(pair, online.filter(::isMesh), "it is back")
    }

    private fun announce(pair: HeldPair, interfaces: Collection<String>, why: String) {
        if (interfaces.isEmpty()) {
            Log.i(TAG, "$why: no mesh connection is up, so nothing is announced now (§17.15)")
            return
        }
        val count = RetichatBridge.distroDirectAnnounce(pair.offline, interfaces.toTypedArray())
        if (count < 0) Log.w(TAG, "$why: the offline address was not announced: ${RetichatBridge.lastError()}")
        else Log.i(TAG, "$why: the offline address ${pair.offline.toHex().take(8)} announced on $count mesh connection(s) (§17.15)")
    }

    /** What comes to the offline address, and what is heard, from the Rust threads. */
    private val callback = object : DistroDirectCallback {
        override fun onDirect(distro: ByteArray, sealed: ByteArray, stamp: ByteArray): Int {
            val app = app ?: return 0
            val back = if (offline) 0 else RetichatBridge.DistroDirect.BACK
            val kept = try {
                // The packet's proof, or the request's answer, waits for this: the message is kept first.
                runBlocking(Dispatchers.IO) { RfedDistroClient.takeDirect(app, sealed, stamp) }
            } catch (e: Exception) {
                Log.e(TAG, "a message received direct could not be kept: ${e.message}")
                false
            }
            Log.i(TAG, "a message came direct to the offline address of ${distro.toHex().take(8)}: ${if (kept) "held" else "not kept"} (§17.15)")
            return (if (kept) RetichatBridge.DistroDirect.HELD else 0) or back
        }

        override fun onOfflineAddress(distro: ByteArray, random: String, back: Boolean) {
            val d = distro.toHex()
            scope.launch { heardOfflineAddress(d, random, back) }
        }

        override fun onLookup(distro: ByteArray, randoms: Array<String>) {
            val d = distro.toHex()
            val named = randoms.toList()
            scope.launch { heardLookup(d, named) }
        }
    }

    // ---- The sender ----

    /** A distro this device sends to: a contact it knows as one, or an address that announced it is one (§17.10). */
    private fun sendsTo(app: RetichatApp, d: String): Boolean =
        UserPreferences.isDistroContact(app, d) || DistroCodec.hexToBytes(d)?.let(RetichatBridge::peerIsDistro) == true

    private fun heardOfflineAddress(d: String, random: String, back: Boolean) {
        val app = app ?: return
        if (!DistroOfflineRules.isRandomPart(random)) return
        if (d == DistroManager.deliveryHashHex) {
            // A sibling of this device's own distro: the lookup names those heard offline since it went offline.
            val pair = held ?: return
            if (!offline || random == pair.random) return
            val changed = if (back) siblings.remove(random) else siblings.add(random)
            if (changed && !RetichatBridge.distroDirectSetAppData(pair.offline, back = false, lookup = DistroOfflineRules.lookup(pair.random, siblings).toTypedArray())) {
                Log.w(TAG, "the lookup's answer was not updated: ${RetichatBridge.lastError()}")
            }
            return
        }
        if (!sendsTo(app, d)) return
        if (UserPreferences.keepDistroOfflineAddress(app, d, random)) {
            Log.i(TAG, "kept ${d.take(8)}'s offline address $random (§17.15)")
            watch(d)
        }
        heard.add(d)
        val addresses = live.getOrPut(d) { HashSet() }
        if (back) {
            if (addresses.remove(random)) Log.i(TAG, "${d.take(8)}'s offline address $random says it is back: it is no longer sent to (§17.15)")
            return
        }
        if (addresses.add(random)) Log.i(TAG, "${d.take(8)}'s offline address $random is live (§17.15)")
        sendKept(d, listOf(random))
    }

    private fun heardLookup(d: String, randoms: List<String>) {
        val app = app ?: return
        if (d == DistroManager.deliveryHashHex || !sendsTo(app, d)) return
        heard.add(d)
        val named = randoms.filter(DistroOfflineRules::isRandomPart)
        if (named.isEmpty()) return
        Log.i(TAG, "${d.take(8)}'s lookup names ${named.size} offline address(es) (§17.15)")
        for (random in named) {
            UserPreferences.keepDistroOfflineAddress(app, d, random)
            live.getOrPut(d) { HashSet() }.add(random)
        }
        watch(d)
        sendKept(d, named)
    }

    /**
     * Watch for the announces of [d]'s lookup address and of each offline
     * address of [d] kept, so they pass the app's drop of announces (§17.15,
     * 2026-11-18): this device hears one of [d]'s devices go offline when it
     * announces, not only when it next asks. Told again only when something
     * new is kept; not recorded when it fails ([d]'s identity not known yet),
     * so the next event for [d] tells it again.
     */
    private fun watch(d: String) {
        val app = app ?: return
        val distro = DistroCodec.hexToBytes(d) ?: return
        val kept = UserPreferences.distroOfflineKept(app, d)
        if (watching[d] == kept) return
        val count = RetichatBridge.distroDirectWatch(distro, kept.toTypedArray())
        if (count < 0) {
            Log.w(TAG, "${d.take(8)}'s offline devices are not watched for yet: ${RetichatBridge.lastError()} (§17.15)")
            return
        }
        watching[d] = kept
        Log.i(TAG, "watching for ${d.take(8)}'s lookup and ${kept.size} kept offline address(es) (§17.15)")
    }

    /**
     * A message this device just sent to the distro [distro] through
     * propagation (the outbound message [handle], its row [localId]): made into
     * a copy for the distro's offline addresses, off the outbox's and this
     * thread's work (a stamp not made yet is mined here, the one the router
     * would mine), then sent to each live one, asking first when it is time.
     * Never throws.
     */
    fun sentToDistro(context: Context, distro: ByteArray, handle: Long, localId: String) {
        val app = context.applicationContext as? RetichatApp ?: return
        makeCopy(app, distro.toHex(), handle, localId)
    }

    /**
     * The propagated copy of a message that went direct first (ChatRepository's
     * fall-back to RFed), [handle] the clone the router sends, its row [localId]:
     * made into a copy for the offline devices as [sentToDistro] makes one when
     * [peer] is a distro this device sends to. The clone's handle stays
     * ChatRepository's, as the send's own does in [sentToDistro]: the bubble
     * follows it, so nothing here releases it. Never throws.
     */
    fun fallbackSent(context: Context, peer: ByteArray, handle: Long, localId: String) {
        val app = context.applicationContext as? RetichatApp ?: return
        val d = peer.toHex()
        scope.launch {
            if (started && sendsTo(app, d)) makeCopy(app, d, handle, localId)
        }
    }

    /** Watch [d] on [serial], make the copy of [handle] off it, then fan it out there. */
    private fun makeCopy(app: RetichatApp, d: String, handle: Long, localId: String) {
        io.launch {
            try {
                withContext(serial) { watch(d) }
                val node = PropagationSync.resolvePropagationOverride(app)?.trim()?.lowercase()?.let(DistroCodec::hexToBytes)
                if (node == null) {
                    Log.i(TAG, "no propagation node is configured: the message to ${d.take(8)} has no copy for its offline devices (§17.15)")
                    return@launch
                }
                val copy = RetichatBridge.distroDirectCopy(handle, node) ?: run {
                    Log.w(TAG, "the message to ${d.take(8)} has no copy for its offline devices: ${RetichatBridge.lastError()} (§17.15)")
                    return@launch
                }
                val hash = RetichatBridge.messageGetHash(handle)?.toHex() ?: ""
                withContext(serial) { fanOut(app, d, Copy(localId, hash, copy, handle, afterAsk = false)) }
            } catch (e: Exception) {
                Log.w(TAG, "the message to ${d.take(8)} was not sent to its offline devices: ${e.message}")
            }
        }
    }

    private fun fanOut(app: RetichatApp, d: String, copy: Copy) {
        prune(d)
        val meshUp = online.filter(::isMesh)
        val asks = DistroOfflineRules.asks(meshUp.isNotEmpty(), d in asked, d in heard)
        if (asks || d in asked) copy.afterAsk = true
        copies.getOrPut(d) { mutableListOf() }.add(copy)
        if (asks) {
            asked.add(d)
            val distro = DistroCodec.hexToBytes(d) ?: return
            val kept = UserPreferences.distroOfflineKept(app, d).toTypedArray()
            val count = RetichatBridge.distroDirectAsk(distro, kept, withLookup = true, interfaces = meshUp.toTypedArray())
            if (count < 0) Log.w(TAG, "asking for ${d.take(8)}'s offline devices failed: ${RetichatBridge.lastError()}")
            else Log.i(TAG, "asked for ${d.take(8)}'s offline devices: $count path request(s) over ${meshUp.size} mesh connection(s) (§17.15)")
        }
        val addresses = live[d].orEmpty().toList()
        if (addresses.isNotEmpty()) sendTo(d, addresses, listOf(copy))
    }

    /** Send each kept copy for [d] that [randoms] should have: still waiting to reach RFed, or sent after asking. */
    private fun sendKept(d: String, randoms: List<String>) {
        prune(d)
        val kept = copies[d].orEmpty().filter { it.afterAsk || waiting(it) }
        if (kept.isNotEmpty()) sendTo(d, randoms, kept)
    }

    /** Send [list] to each of [randoms] that has not had it; an address with no path is asked for once, over the mesh. */
    private fun sendTo(d: String, randoms: List<String>, list: List<Copy>) {
        val distro = DistroCodec.hexToBytes(d) ?: return
        val noPath = LinkedHashSet<String>()
        for (random in randoms) {
            for (copy in list) {
                if (random in copy.sentTo) continue
                when (RetichatBridge.distroDirectSend(distro, random, copy.bytes, outcome(d, random, copy))) {
                    1 -> {
                        copy.sentTo.add(random)
                        Log.i(TAG, "the message ${copy.msgHash.take(8)} went to ${d.take(8)}'s offline address $random (§17.15)")
                    }
                    0 -> {
                        noPath.add(random)
                        break
                    }
                    else -> Log.w(TAG, "the message ${copy.msgHash.take(8)} did not go to ${d.take(8)}'s offline address $random: ${RetichatBridge.lastError()}")
                }
            }
        }
        val meshUp = online.filter(::isMesh)
        if (noPath.isNotEmpty() && meshUp.isNotEmpty()) {
            // Its answer makes the address live again, and the copies go then.
            RetichatBridge.distroDirectAsk(distro, noPath.toTypedArray(), withLookup = false, interfaces = meshUp.toTypedArray())
            Log.i(TAG, "no path to ${d.take(8)}'s offline address(es) ${noPath.joinToString()}: asked for one over the mesh (§17.15)")
        }
    }

    private fun outcome(d: String, random: String, copy: Copy) = object : DistroDirectSendCallback {
        override fun onOutcome(outcome: Int) {
            scope.launch { sent(d, random, copy, outcome) }
        }
    }

    private fun sent(d: String, random: String, copy: Copy, outcome: Int) {
        val delivered = outcome and RetichatBridge.DistroDirect.HELD != 0
        val back = outcome and RetichatBridge.DistroDirect.BACK != 0
        if (delivered) {
            Log.i(TAG, "the message ${copy.msgHash.take(8)} reached ${d.take(8)}'s offline address $random${if (back) ", which is back" else ""} (§17.15)")
            app?.let { app -> io.launch { app.repository.onDistroDirectDelivered(copy.localId) } }
        } else {
            // Reported, never repeated (DESIGN_PRINCIPLES §3): the message still goes through RFed.
            Log.w(TAG, "the message ${copy.msgHash.take(8)} did not reach ${d.take(8)}'s offline address $random: not sent again, and it goes through RFed as before (§17.15)")
        }
        if (!delivered || back) live[d]?.remove(random)
    }

    private fun waiting(copy: Copy): Boolean = DistroOfflineRules.waitingForRfed(RetichatBridge.messageGetState(copy.handle))

    /** Keep only the copies an address that becomes live could still need: waiting to reach RFed, or sent after asking. */
    private fun prune(d: String) {
        copies[d]?.removeAll { !it.afterAsk && !waiting(it) }
        if (copies[d]?.isEmpty() == true) copies.remove(d)
    }
}
