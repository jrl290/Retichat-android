package com.newendian.retichat.service

import com.newendian.retichat.bridge.RetichatBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the distro outbox is wired into the app: who owes what, what starts it,
 * and what must never send a membership message (the Android classes are not
 * run in a JVM test; their source is what is pinned, as the other contract tests
 * here do).
 */
class DistroOutboxWiringTest {
    private fun read(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    private val runtime = read("service/DistroOutboxRuntime.kt")
    private val stack = read("service/StackRuntime.kt")
    private val channelClient = read("service/RfedChannelClient.kt")
    private val distroClient = read("service/RfedDistroClient.kt")
    private val repository = read("data/repository/ChatRepository.kt")

    private fun body(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue(from, start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue(to, end > start)
        return text.substring(start, end)
    }

    @Test
    fun theStackStartsTheOutboxAfterTheLinksAreWiredAndStopsItFirst() {
        val register = stack.indexOf("ConnectionStateManager.register(app, routerHandle)")
        val start = stack.indexOf("DistroOutboxRuntime.start(app)")
        assertTrue(register in 0 until start)
        // Before the propagation sync or any send can open the propagation link.
        assertTrue(start < stack.indexOf("PropagationStream.start("))
        val shutdown = body(stack, "private fun shutdownNow(", "ConnectionStateManager.unregister()")
        assertTrue(shutdown.contains("DistroOutboxRuntime.stop()"))
    }

    @Test
    fun onlyTheUsersOwnJoinLeaveAndSendOweAnything() {
        // C is owed from one place, RfedChannelClient.recordUsersAction; the sent-copy from one, ChatRepository.
        assertEquals(1, Regex("DistroOutboxRuntime\\.oweChannelSync\\(").findAll(channelClient).count())
        assertEquals(1, Regex("DistroOutboxRuntime\\.oweSentCopy\\(").findAll(repository).count())
        // Nothing else in the app owes the distro anything: not the stack, not the distro client's receive path.
        for ((name, text) in listOf("stack" to stack, "distro client" to distroClient)) {
            assertFalse(name, text.contains("oweChannelSync(") || text.contains("oweSentCopy("))
        }
        val record = body(channelClient, "private suspend fun recordUsersAction(", "suspend fun handleDistroChannelSync(")
        assertTrue(record.contains("if (!DistroManager.hasDistro) return"))
        // The record is made with or without a distro; the message only with one.
        assertTrue(record.indexOf("channelDao.upsertMembership(") < record.indexOf("DistroOutboxRuntime.oweChannelSync("))
    }

    @Test
    fun theUsersOwnActionIsTheOnlyCallerOfRecordUsersAction() {
        // Its declaration and its two callers, joinChannel and leaveChannel.
        assertEquals(3, Regex("recordUsersAction\\(").findAll(channelClient).count())
        val join = body(channelClient, "suspend fun joinChannel(", "suspend fun leaveChannel(")
        // A join of a channel already in the list is no change: no message.
        assertTrue(join.contains("if (existing == null) recordUsersAction(DistroChannelSync.OP_JOIN, entity)"))
        val leave = body(channelClient, "suspend fun leaveChannel(", "private suspend fun leaveHere(")
        assertTrue(leave.contains("recordUsersAction(DistroChannelSync.OP_LEAVE, entity)"))
        assertTrue(leave.indexOf("leaveHere(entity)") < leave.indexOf("recordUsersAction("))
    }

    @Test
    fun applyingASiblingsChangeSendsNothingAndSubscribesWithTheDevicesKey() {
        val apply = body(channelClient, "private suspend fun applyDistroChannelSync(", "private suspend fun subscribeStoredChannel(")
        // Never re-sent, never echoed: applying C calls neither the record nor the outbox.
        assertFalse(apply.contains("recordUsersAction"))
        assertFalse(apply.contains("oweChannelSync"))
        assertFalse(apply.contains("joinChannel("))
        assertFalse(apply.contains("leaveChannel("))
        // The leave is the shared clean-up, which sends nothing; the join is stored before it is subscribed.
        assertTrue(apply.contains("leaveHere(held)"))
        assertTrue(apply.indexOf("channelDao.upsert(entity)") < apply.indexOf("subscribeStoredChannel(entity)"))
        // The record is kept for every apply, including a leave (what stops an older join coming back).
        assertTrue(apply.contains("channelDao.upsertMembership(ChannelMembershipEntity(change.channelHash, change.op, change.atMs))"))
        val leaveHere = body(channelClient, "private suspend fun leaveHere(", "// ── Channels and the distro")
        assertFalse(leaveHere.contains("recordUsersAction"))
        assertFalse(leaveHere.contains("DistroOutboxRuntime"))
        // Subscribing is the device's, by the device's identity.
        val subscribe = body(channelClient, "private suspend fun subscribeStoredChannel(", "// ── Per-channel push toggle")
        assertTrue(subscribe.contains("StackRuntime.identityHandle"))
        assertFalse(subscribe.contains("DistroManager"))
    }

    @Test
    fun aMembershipMessageIsReadBeforeTheGenericDedupeAndTheDeliveryNotificationTest() {
        val handle = body(distroClient, "suspend fun handleBlob(", "private suspend fun handleChannelSync(")
        val read = handle.indexOf("DistroChannelSync.read(fieldsRaw)")
        assertTrue(read >= 0)
        assertTrue(read < handle.indexOf("DistroCodec.seenKey(srcHex, timestamp)"))
        assertTrue(read < handle.indexOf("if (isNotification) return"))
        val sync = body(distroClient, "private suspend fun handleChannelSync(", "/** Another device offered us a distro key")
        // Its own repeat key, and a dropped one is judged again.
        assertTrue(sync.contains("DistroChannelSync.repeatKey(srcHex, timestamp, marker)"))
        assertTrue(sync.contains("UserPreferences.forgetDistroSeen(context, key)"))
        // Rules 1 and 2: the source is D, and the signature is D's.
        assertTrue(sync.contains("val fromDistro = ownDistro != null && srcHex == ownDistro"))
        assertTrue(sync.contains("fromDistro && signatureValidated"))
    }

    @Test
    fun theSentCopyIsOwedNotHandedToTheRouterOnce() {
        val copy = body(repository, "private suspend fun sendDistroSentCopy(", "private suspend fun sendGroupMessage(")
        assertTrue(copy.contains("DistroOutboxRuntime.oweSentCopy(appContext, recipient, title, content)"))
        assertTrue(copy.contains("DistroCodec.shouldSendSentCopy("))
        // It no longer goes through the router, whose own re-send is paced by a timer and gives up after a count.
        assertFalse(copy.contains("messageSendViaAppLinks"))
        assertFalse(copy.contains("selectPropagationNode"))
        assertFalse(repository.contains("sentCopyHandles"))
        assertFalse(repository.contains("finishSentCopy"))
    }

    @Test
    fun anOwedMessageIsSignedByTheKeyItsSourceNames() {
        // The distro and the handle that signs as it are read together (DistroManager.signing), so a
        // generate, import or forget between two reads cannot give a source that the key does not match.
        val channel = body(runtime, "suspend fun oweChannelSync(", "suspend fun oweSentCopy(")
        val sent = body(runtime, "suspend fun oweSentCopy(", "private suspend fun pack(")
        for ((name, text) in listOf("C" to channel, "sent-copy" to sent)) {
            assertEquals(name, 1, Regex("DistroManager\\.signing\\(\\)").findAll(text).count())
            assertFalse(name, text.contains("DistroManager.identityHandle"))
            assertFalse(name, text.contains("DistroManager.state.value"))
        }
        val pack = body(runtime, "private suspend fun pack(", "private class JniPropagationPort(")
        assertFalse(pack.contains("DistroManager.identityHandle"))
        assertFalse(pack.contains("DistroManager.state"))
        assertTrue(pack.contains("identityHandle = signer"))
    }

    @Test
    fun theRuntimeStartsNoLinkAndOnlyALinksComingUpFlushes() {
        // It never opens or reopens the propagation link.
        assertFalse(runtime.contains("appLinkOpen"))
        assertFalse(runtime.contains("appLinkReopen"))
        assertFalse(runtime.contains("routerSetPropagationNode"))
        assertFalse(runtime.contains("selectPropagationNode"))
        // onLinkEstablished: the link callback's ACTIVE event and the check at start, nothing else.
        assertEquals(2, Regex("onLinkEstablished\\(").findAll(runtime).count())
        // onLinkRecovered: the link callback's RECOVERED event (a STALE link that heard the node
        // again), nothing else: no clock, no poll of the link.
        assertEquals(1, Regex("onLinkRecovered\\(").findAll(runtime).count())
        // Its one delay is the §1 watch, which only logs.
        assertEquals(1, Regex("\\bdelay\\(").findAll(runtime).count())
        assertTrue(body(runtime, "delay(DistroOutbox.PROOF_LIVE_MS + 1)", "return UploadLeft(").contains("Log.e("))
    }

    @Test
    fun anInterfaceComingOnlineIsTransportsUpEdgeTheOutboxHearsThroughOneCallbackAndNothingElse() {
        // James, 2026-10-06: an upload that never left the device goes when an interface comes back
        // online, not on Android's "network available" alone (it says only that the system has a network).
        val callback = body(runtime, "private val interfaceCallback = object : InterfaceOnlineCallback {", "// ---- What the user's actions owe ----")
        assertTrue(callback.contains("scope.launch { outbox.onInterfaceOnline(name) }"))
        assertFalse(callback.contains("runBlocking"))
        assertFalse(callback.contains("delay("))
        // The outbox's only caller of that method is the callback; the runtime registers it at each start and clears it at the stop.
        assertEquals(1, Regex("outbox\\.onInterfaceOnline\\(").findAll(runtime).count())
        assertEquals(1, Regex("RetichatBridge\\.interfaceOnlineRegister\\(").findAll(runtime).count())
        assertTrue(body(runtime, "fun stop() {", "private val linkCallback").contains("RetichatBridge.interfaceOnlineUnregister()"))
        // The system's own network events reach neither the runtime nor the outbox.
        val outbox = read("service/DistroOutbox.kt")
        for ((name, text) in listOf("runtime" to runtime, "outbox" to outbox)) {
            for (network in listOf("ConnectivityManager", "NetworkCallback", "onAvailable", "NetworkCapabilities")) {
                assertFalse("$name $network", text.contains(network))
            }
        }
        // Nothing else in the app calls it.
        for ((name, text) in listOf("stack" to stack, "channel client" to channelClient, "distro client" to distroClient, "repository" to repository)) {
            assertFalse(name, text.contains("onInterfaceOnline("))
        }
        // The Rust side tells Transport's up-edge, the one AppLinks re-attempts its links on, not a network event.
        val rust = File("../rust/retichat-jni/src/distro_outbox.rs").readText()
        assertTrue(rust.contains("Transport::add_interface_up_listener("))
    }

    @Test
    fun aStaleLinksRecoveryIsTheLinkCallbacksRecoveredEventForTheNodesLinkOnly() {
        val callback = body(runtime, "private val linkCallback = object : PropagationLinkCallback {", "// ---- What the user's actions owe ----")
        // The same guards for both events: only the propagation node's link, and only with the link's id.
        assertTrue(callback.contains("if (!destHash.contentEquals(node)) return"))
        assertTrue(callback.contains("val id = linkId?.takeIf { it.isNotEmpty() }?.toHex()"))
        // An establishment is ACTIVE; a STALE link's recovery is RECOVERED. Each is its own coming-up.
        assertTrue(callback.contains("status == RetichatBridge.AppLinkStatus.ACTIVE && id != null"))
        assertTrue(callback.contains("scope.launch { outbox.onLinkEstablished(id) }"))
        assertTrue(callback.contains("status == RetichatBridge.AppLinkStatus.RECOVERED && id != null"))
        assertTrue(callback.contains("scope.launch { outbox.onLinkRecovered(id) }"))
        // Neither runs on the Rust thread that told it, and nothing in the callback waits or delays.
        assertFalse(callback.contains("runBlocking"))
        assertFalse(callback.contains("delay("))
        // The status is AppLinks' own code 5, which no state of the link reuses.
        val codes = listOf(
            RetichatBridge.AppLinkStatus.NONE, RetichatBridge.AppLinkStatus.PATH_REQUESTED,
            RetichatBridge.AppLinkStatus.ESTABLISHING, RetichatBridge.AppLinkStatus.ACTIVE,
            RetichatBridge.AppLinkStatus.DISCONNECTED, RetichatBridge.AppLinkStatus.RECOVERED,
        )
        assertEquals(5, RetichatBridge.AppLinkStatus.RECOVERED)
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun bothKindsAreSealedRightAfterTheyArePackedAndOwedWithTheProofInTheSameWrite() {
        // DISTRO-SYNC-PROOF-DESIGN.md §5.1: sealed once, by the handle that signed it, right after the pack, off the
        // main thread; the entry is owed with both fields, so the one write that owes it keeps the proof.
        val pack = body(runtime, "private suspend fun pack(", "private class JniPropagationPort(")
        assertTrue(pack.contains("withContext(Dispatchers.IO)"))
        assertTrue(pack.indexOf("RetichatBridge.messagePack(handle)") in 0 until pack.indexOf("RetichatBridge.distroSeal(signer, packed)"))
        assertEquals(1, Regex("RetichatBridge\\.distroSeal\\(").findAll(runtime).count())
        // A seal that fails is said as an error and the message is still owed, without it.
        val failed = body(pack, "if (sealed == null) {", "Packed(packed, hash, sealed)")
        assertTrue(failed.contains("Log.e("))
        assertFalse(failed.contains("return"))
        val channel = body(runtime, "suspend fun oweChannelSync(", "suspend fun oweSentCopy(")
        val sent = body(runtime, "suspend fun oweSentCopy(", "private class Packed(")
        for ((name, text) in listOf("C" to channel, "sent-copy" to sent)) {
            assertTrue(name, text.contains("sealed = packed.sealed?.sealed, syncSig = packed.sealed?.syncSig,"))
            assertTrue(name, text.indexOf("val entry = OwedEntry(") < text.indexOf("outbox.owe(entry)"))
        }
        // The Room row carries both, and a row with one alone reads as legacy.
        val store = read("service/RoomOutboxStore.kt")
        assertTrue(store.contains("sealed = entry.sealed,"))
        assertTrue(store.contains("syncSig = entry.syncSig,"))
        assertTrue(store.contains("sealed = row.sealed?.takeIf { row.syncSig != null },"))
    }

    @Test
    fun thePortBuildsWhatTheGateSaysAndTheGateIsTheRfedNodesOwnPropagationHash() {
        val build = body(runtime, "override suspend fun build(entry: OwedEntry): ByteArray", "override fun upEdgeMark(): UpEdgeMark")
        assertTrue(build.contains("SyncProofGate.buildFor(entry, node.toHex(), PropagationSync.rfedDerivedPropagationHash(context))"))
        // A legacy entry: the old builder. A sealed one: its stored bytes, with the claim only as the gate says.
        assertTrue(build.contains("SyncProofGate.Build.Legacy -> RetichatBridge.propagationBuildUpload(key, entry.packed, node)"))
        assertTrue(build.contains("RetichatBridge.propagationBuildSealedUpload(key, entry.sealed!!, entry.syncSig!!, how.withClaim, node)"))
        assertEquals(1, Regex("propagationBuildSealedUpload\\(").findAll(runtime).count())
        assertEquals(1, Regex("propagationBuildUpload\\(").findAll(runtime).count())
        // The derived hash is the branch of resolvePropagationOverride used when no override is set, and nothing more.
        val sync = read("service/PropagationSync.kt")
        val derived = body(sync, "fun rfedDerivedPropagationHash(context: Context): String? {", "\n    }")
        assertTrue(derived.contains("UserPreferences.getEffectiveRfedNodeIdentityHash(context)"))
        assertTrue(derived.contains("FcmTokenRegistrar.rnsDestHash(rfedId, \"lxmf\", listOf(\"propagation\"))"))
        for (override in listOf("getRfedLxmfPropOverride", "getLxmfPropagationHash")) assertFalse(override, derived.contains(override))
        val resolve = body(sync, "fun resolvePropagationOverride(context: Context): String? {", "fun rfedDerivedPropagationHash(")
        assertTrue(resolve.indexOf("getRfedLxmfPropOverride") < resolve.indexOf("return rfedDerivedPropagationHash(context)"))
        assertTrue(resolve.indexOf("getLxmfPropagationHash") < resolve.indexOf("return rfedDerivedPropagationHash(context)"))
    }

    @Test
    fun aResourceThatNeverLeftReachesTheOutboxSinkAndEachUploadTakesItsMarkFromTransportsUpEdges() {
        val upload = body(runtime, "override fun upload(upload: ByteArray, sink: UploadSink): UploadLeft?", "return UploadLeft(")
        assertTrue(upload.contains("override fun onNeverLeft() { decided.set(true); scope.launch { sink.neverLeft() } }"))
        // Like the proof and the loss, handed to the outbox's one thread, never run on the Rust thread.
        assertFalse(upload.contains("runBlocking"))
        val mark = body(runtime, "override fun upEdgeMark(): UpEdgeMark {", "override fun upload(")
        assertTrue(mark.contains("val at = RetichatBridge.interfaceUpEdges()"))
        assertTrue(mark.contains("return UpEdgeMark { RetichatBridge.interfaceUpEdges() != at }"))
        assertFalse(mark.contains("delay("))
    }
}
