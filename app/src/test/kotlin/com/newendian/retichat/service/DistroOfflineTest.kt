package com.newendian.retichat.service

import com.newendian.retichat.bridge.RetichatBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Offline use of a distro address (RFed-rust SPEC.md §17.15, James 2026-11-17):
 * the rules [DistroOffline] decides by, and how it is wired to the bridge.
 */
class DistroOfflineTest {
    private val rnodes = setOf("LoRa RNode")

    @Test
    fun aRandomPartIsSixteenLowercaseHexCharacters() {
        assertTrue(DistroOfflineRules.isRandomPart("0123456789abcdef"))
        assertFalse(DistroOfflineRules.isRandomPart("0123456789ABCDEF"))
        assertFalse(DistroOfflineRules.isRandomPart("0123456789abcde"))
        assertFalse(DistroOfflineRules.isRandomPart("0123456789abcdef0"))
        assertFalse(DistroOfflineRules.isRandomPart("0123456789abcdeg"))
    }

    @Test
    fun aMeshConnectionIsAnRtnodeOverBluetoothOrASavedRnodeAndEveryOtherIsInternet() {
        assertTrue(DistroOfflineRules.isMesh("PrnsBLE[0a1b2c3d4e5f60718293a4b5c6d7e8f9]", rnodes))
        assertTrue(DistroOfflineRules.isMesh("LoRa RNode", rnodes))
        for (internet in listOf("Default Public Endpoints", "Home", "AutoInterface[Local network]", "AutoInterfacePeer[wlan0/fe80::1]")) {
            assertFalse(internet, DistroOfflineRules.isMesh(internet, rnodes))
        }
    }

    @Test
    fun offlineNeedsTheToggleADistroRfedNotReachedAndNoInternetConnectionUp() {
        val mesh = listOf("PrnsBLE[00]")
        assertTrue(DistroOfflineRules.offline(true, true, false, mesh, rnodes))
        assertTrue("no interface at all is no internet either", DistroOfflineRules.offline(true, true, false, emptyList(), rnodes))
        // Each condition alone keeps it from being offline.
        assertFalse("the toggle is off by default", DistroOfflineRules.offline(false, true, false, mesh, rnodes))
        assertFalse(DistroOfflineRules.offline(true, false, false, mesh, rnodes))
        assertFalse("RFed reached", DistroOfflineRules.offline(true, true, true, mesh, rnodes))
        assertFalse("nothing known of RFed yet", DistroOfflineRules.offline(true, true, null, mesh, rnodes))
        // James: "it cannot be triggered by RFed disconnect alone".
        assertFalse(DistroOfflineRules.offline(true, true, false, mesh + "Home", rnodes))
    }

    @Test
    fun theLinkToRfedSaysReachedNotReachedOrNeitherAndAClosedSlowRouteIsNeither() {
        assertEquals(true, DistroOfflineRules.reached(RetichatBridge.AppLinkStatus.ACTIVE))
        assertEquals(true, DistroOfflineRules.reached(RetichatBridge.AppLinkStatus.RECOVERED))
        assertEquals(false, DistroOfflineRules.reached(RetichatBridge.AppLinkStatus.DISCONNECTED))
        // NONE: a link closed on purpose (a slow route closes its link after its requests), or none at all.
        assertNull(DistroOfflineRules.reached(RetichatBridge.AppLinkStatus.NONE))
        assertNull(DistroOfflineRules.reached(RetichatBridge.AppLinkStatus.PATH_REQUESTED))
        assertNull(DistroOfflineRules.reached(RetichatBridge.AppLinkStatus.ESTABLISHING))
    }

    @Test
    fun theLookupNamesTheDevicesOwnAddressFirstThenItsSiblingsOnce() {
        assertEquals(listOf("aaaaaaaaaaaaaaaa"), DistroOfflineRules.lookup("aaaaaaaaaaaaaaaa", emptyList()))
        assertEquals(
            listOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"),
            DistroOfflineRules.lookup("aaaaaaaaaaaaaaaa", listOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")),
        )
    }

    @Test
    fun aSenderAsksOnlyWithAMeshConnectionUpAndOncePerComingUpUnlessItHeardFromTheDistro() {
        assertTrue(DistroOfflineRules.asks(meshUp = true, askedSince = false, heardSince = false))
        assertFalse(DistroOfflineRules.asks(meshUp = false, askedSince = false, heardSince = false))
        assertFalse(DistroOfflineRules.asks(meshUp = true, askedSince = true, heardSince = false))
        assertFalse(DistroOfflineRules.asks(meshUp = true, askedSince = false, heardSince = true))
    }

    @Test
    fun aMessageWaitsForRfedUntilTheRouterHasHandedItToTheNode() {
        for (s in listOf(RetichatBridge.MessageState.GENERATING, RetichatBridge.MessageState.OUTBOUND, RetichatBridge.MessageState.SENDING)) {
            assertTrue(DistroOfflineRules.waitingForRfed(s))
        }
        for (s in listOf(RetichatBridge.MessageState.SENT, RetichatBridge.MessageState.DELIVERED, RetichatBridge.MessageState.FAILED, -1)) {
            assertFalse(DistroOfflineRules.waitingForRfed(s))
        }
    }

    // ---- Wiring ----

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val bridge = src("bridge/RetichatBridge.kt")
    private val rust = File("../rust/retichat-jni/src/lib.rs").readText()
    private val offline = src("service/DistroOffline.kt")

    @Test
    fun everyNewNativeHasItsRustSymbolAndTheCallbacksTheirDescriptorsAndKeepRules() {
        for (name in listOf(
            "nativeDistroDirectRegister", "nativeDistroDirectUnregister", "nativeDistroDirectHold", "nativeDistroDirectSetAppData",
            "nativeDistroDirectAnnounce", "nativeDistroDirectSign", "nativePropagationBuildDirectUpload", "nativeDistroDirectCopy",
            "nativeDistroDirectSend", "nativeDistroDirectAsk", "nativeOnlineInterfaces",
        )) {
            assertTrue(name, bridge.contains("private external fun $name("))
            assertTrue(name, rust.contains("fn Java_com_newendian_retichat_bridge_RetichatBridge_$name("))
        }
        assertTrue(bridge.contains("fun onDirect(distro: ByteArray, sealed: ByteArray, stamp: ByteArray): Int"))
        assertTrue(rust.contains("\"onDirect\", \"([B[B[B)I\""))
        assertTrue(bridge.contains("fun onOfflineAddress(distro: ByteArray, random: String, back: Boolean)"))
        assertTrue(rust.contains("\"onOfflineAddress\", \"([BLjava/lang/String;Z)V\""))
        assertTrue(bridge.contains("fun onLookup(distro: ByteArray, randoms: Array<String>)"))
        assertTrue(rust.contains("\"onLookup\", \"([B[Ljava/lang/String;)V\""))
        assertTrue(bridge.contains("fun onOutcome(outcome: Int)"))
        assertTrue(rust.contains("\"onOutcome\", \"(I)V\""))
        val rules = File("proguard-rules.pro").readText()
        for (callback in listOf("DistroDirectCallback", "DistroDirectSendCallback")) {
            assertTrue(callback, rules.contains("-keep interface com.newendian.retichat.bridge.$callback { *; }"))
            assertTrue(callback, rules.contains("-keep class * implements com.newendian.retichat.bridge.$callback { *; }"))
            assertTrue(callback, Regex("@Keep\\s+interface $callback").containsMatchIn(bridge))
        }
        // The answer's bits are the crate's.
        assertTrue(bridge.contains("const val HELD = 1"))
        assertTrue(bridge.contains("const val BACK = 2"))
        val direct = File("../rust/retichat-jni/src/distro_direct.rs").readText()
        assertTrue(direct.contains("pub const HELD: u8 = 1;"))
        assertTrue(direct.contains("pub const BACK: u8 = 2;"))
    }

    @Test
    fun aSendGoesFromOnePlaceAndAFailureIsNeverSentAgain() {
        // DESIGN_PRINCIPLES §3: one call site sends; the outcome handler sends nothing.
        assertEquals(1, Regex("RetichatBridge\\.distroDirectSend\\(").findAll(offline).count())
        val sent = offline.substring(offline.indexOf("private fun sent("), offline.indexOf("private fun waiting("))
        assertFalse(sent.contains("distroDirectSend"))
        assertFalse(sent.contains("sendTo("))
        assertFalse(sent.contains("sendKept("))
        // A failure or a device that is back is no longer live.
        assertTrue(sent.contains("if (!delivered || back) live[d]?.remove(random)"))
    }

    @Test
    fun theDeviceAnnouncesOnlyOnItsMeshConnectionsAndNeverOnATimer() {
        assertEquals(1, Regex("RetichatBridge\\.distroDirectAnnounce\\(").findAll(offline).count())
        for (call in Regex("announce\\(([^\\n]*)\\)").findAll(offline).map { it.groupValues[1] }.filter { (it.startsWith("pair") || it.startsWith("it,")) && !it.contains(": HeldPair") }) {
            assertTrue(call, call.contains("meshUp") || call.contains("online.filter(::isMesh)"))
        }
        for (timer in listOf("delay(", "Timer(", "schedule", "postDelayed", "withTimeout")) assertFalse(timer, offline.contains(timer))
    }

    @Test
    fun theStackStartsAndStopsItAndTheDistroSendHandsItEachMessage() {
        val stack = src("service/StackRuntime.kt")
        assertTrue(stack.contains("runCatching { DistroOffline.start(app) }"))
        assertTrue(stack.indexOf("DistroOutboxRuntime.stop()") in 0 until stack.indexOf("DistroOffline.stop()"))
        val repo = src("data/repository/ChatRepository.kt")
        assertEquals(1, Regex("DistroOffline\\.sentToDistro\\(").findAll(repo).count())
        // Only a message to a distro address, once it has gone to the router through propagation.
        val distroBranch = repo.substring(repo.indexOf("is a distro address — propagating"), repo.indexOf("// Register the AppLinks spec synchronously"))
        assertTrue(distroBranch.contains("DistroOffline.sentToDistro(appContext, destHash, handle, localId)"))
    }
}
