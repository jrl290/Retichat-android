package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * This device as its own RFed Notify relay, so RFed can wake it over the
 * mesh (James, 2026-10-23: "have rfed send the notify ping directly to the
 * requesting device as well. Then RTNode would be able to push those
 * notifications to wake Retichat at low power"). Port of
 * Retichat-ios/tests/MeshWakeTests.swift.
 *
 * The rules are pure; the JNI, the registrar and the stack are asserted on
 * their source, as OnlineInterfaceCountContractTest does: the .so is not
 * loaded in a JVM test.
 */
class MeshWakeTest {

    // ── The rules ───────────────────────────────────────────────────────

    @Test
    fun wantedOnlyWithABluetoothRadio() {
        assertFalse("no Bluetooth radio: no relay of its own (FCM only)", MeshWake.wanted(savedRNode = false, rtnodeSwitchOn = false))
        assertTrue("an enabled saved RNode: hosted", MeshWake.wanted(savedRNode = true, rtnodeSwitchOn = false))
        assertTrue("the Nearby RTNode switch on: hosted", MeshWake.wanted(savedRNode = false, rtnodeSwitchOn = true))
        assertTrue("and with both", MeshWake.wanted(savedRNode = true, rtnodeSwitchOn = true))
    }

    private fun hash(byte: Int) = ByteArray(16) { byte.toByte() }
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** msgpack as RFed's encode_wake_payload writes it: str keys, bin8 values. */
    private fun key(s: String) = bytes(0xa0 or s.length) + s.toByteArray()
    private fun bin(d: ByteArray) = bytes(0xc4, d.size) + d

    private val receiver = hash(0x11)
    private val sender = hash(0x22)
    private val channel = hash(0x33)

    @Test
    fun parsesTheWakesRfedSends() {
        val lxmf = bytes(0x82) + key("receiver") + bin(receiver) + key("sender") + bin(sender)
        assertEquals("an LXMF wake: receiver and sender", MeshWake.Wake(receiver, sender), MeshWake.parse(lxmf))

        val distro = bytes(0x81) + key("receiver") + bin(receiver)
        assertEquals("a distro wake: the receiver alone", MeshWake.Wake(receiver), MeshWake.parse(distro))

        val chan = bytes(0x82) + key("channel") + bin(channel) + key("receiver") + bin(receiver)
        assertTrue("a channel wake, keys in any order", MeshWake.parse(chan)?.channel.contentEquals(channel))

        val map16 = bytes(0xde, 0x00, 0x01) + key("receiver") + bin(receiver)
        assertTrue("a map16 header", MeshWake.parse(map16)?.receiver.contentEquals(receiver))

        val later = bytes(0x83) + key("receiver") + bin(receiver) +
            key("message") + bin(ByteArray(32) { 0x44 }) +
            key("count") + bytes(0x05)
        assertTrue(
            "fields RFed adds later (a 32-byte bin, an int) do not stop the wake",
            MeshWake.parse(later)?.receiver.contentEquals(receiver),
        )
    }

    /** RFed-spec Notify.md 1cd0e63: the wake names its message by its transient id, and an LXMF wake names no sender. */
    @Test
    fun aWakeNamesItsMessage() {
        val message = ByteArray(32) { 0x44 }
        val named = bytes(0x82) + key("receiver") + bin(receiver) + key("message") + bin(message)
        assertEquals("a wake names its message", MeshWake.Wake(receiver, message = message), MeshWake.parse(named))
        val short = bytes(0x82) + key("receiver") + bin(receiver) + key("message") + bin(hash(0x44))
        val wake = MeshWake.parse(short)
        assertTrue("a message id that is not 32 bytes is left out, and the wake goes on", wake != null && wake.message == null)
    }

    @Test
    fun dropsAnythingElse() {
        val distro = bytes(0x81) + key("receiver") + bin(receiver)
        assertNull("no receiver: dropped", MeshWake.parse(bytes(0x81) + key("sender") + bin(sender)))
        assertNull("a receiver that is not 16 bytes: dropped", MeshWake.parse(bytes(0x81) + key("receiver") + bin(ByteArray(8) { 1 })))
        assertNull("a receiver as a string, not bin: dropped", MeshWake.parse(bytes(0x81) + key("receiver") + key("0123456789abcdef")))
        assertNull("trailing bytes: dropped", MeshWake.parse(distro + bytes(0x00)))
        assertNull("a short map: dropped", MeshWake.parse(bytes(0x82) + key("receiver") + bin(receiver)))
        assertNull("empty: dropped", MeshWake.parse(ByteArray(0)))
        assertNull("not a map: dropped", MeshWake.parse(bytes(0x93, 0x01, 0x02, 0x03)))
        assertNull("a cut-off value: dropped", MeshWake.parse(bytes(0x81) + key("receiver") + bytes(0xc4, 0x10, 0x01)))
    }

    // ── The source ──────────────────────────────────────────────────────

    private fun source(path: String) = File(path).readText()

    /** From [marker] up to the next function at the same depth (or the end). */
    private fun member(text: String, marker: String): String {
        val start = text.indexOf(marker)
        if (start < 0) return ""
        val rest = text.substring(start + marker.length)
        val ends = listOf("\n    fun ", "\n    private fun ", "\n    private suspend fun ", "\n    suspend fun ",
            "\n    internal fun ", "\n#[no_mangle]", "\n#[cfg(test)]", "\n}\n")
            .map { rest.indexOf(it) }.filter { it >= 0 }
        return rest.substring(0, ends.minOrNull() ?: rest.length)
    }

    private fun ordered(text: String, needles: List<String>): Boolean {
        var from = 0
        for (needle in needles) {
            val at = text.indexOf(needle, from)
            if (at < 0) return false
            from = at + needle.length
        }
        return true
    }

    @Test
    fun theJniHostsRfedNotifyAndDeliversEachWake() {
        val rust = source("../rust/retichat-jni/src/lib.rs")
        val bridge = source("src/main/kotlin/com/newendian/retichat/bridge/RetichatBridge.kt")

        assertTrue(
            "the relay is rfed.notify, inbound, under the device's identity",
            ordered(member(rust, "fn notify_relay_destination("), listOf("Destination::new_inbound(", "DestinationType::Single", "\"rfed\"", "\"notify\"")),
        )
        val start = member(rust, "fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeNotifyRelayStart(")
        assertTrue(
            "each wake goes to the callback; registered, then published so RFed has a path",
            ordered(start, listOf("set_packet_callback(", "Transport::register_destination(", "Transport::publish_destination(")),
        )
        assertTrue(
            "refreshed every 30 minutes like lxmf.delivery, with no app_data",
            start.contains("from_secs(30 * 60)") && start.contains("None,\n    );"),
        )
        assertFalse("wakes are not proved (RFed sends them unproved)", start.contains("set_proof_strategy"))
        assertTrue("the callback is onWake(ByteArray)", start.contains("\"onWake\",\n                \"([B)V\""))
        assertTrue("stop unpublishes it", member(rust, "fn notify_relay_stop(").contains("Transport::unpublish_destination("))
        assertTrue(
            rust.contains("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeNotifyRelayStop("),
        )

        assertTrue(bridge.contains("private external fun nativeNotifyRelayStart(identityHandle: Long, callback: MeshWakeCallback): ByteArray?"))
        assertTrue(bridge.contains("private external fun nativeNotifyRelayStop(): Int"))
        assertTrue(bridge.contains("fun onWake(payload: ByteArray)"))
        val proguard = source("proguard-rules.pro")
        assertTrue("the callback survives R8", proguard.contains("-keep interface com.newendian.retichat.bridge.MeshWakeCallback { *; }"))
    }

    @Test
    fun theRegistrarOwesTheRelayLikeTheFcmRelay() {
        val registrar = source("src/main/kotlin/com/newendian/retichat/service/RfedNotifyRegistrar.kt")

        val register = member(registrar, "fun registerMeshRelay(")
        assertTrue("registered through the owed registrations", register.contains("oweMeshRegistrations("))
        val payloads = member(registrar, "private fun meshPayloads(")
        assertTrue(
            "for LXMF wakes (direct messages and the distro), then each channel with push on",
            ordered(payloads, listOf("channelHash = null", "channelHashes.map", "channelKey(channelHash, relayHex, identityHandle)")),
        )
        val owe = member(registrar, "private suspend fun oweMeshRegistrations(")
        assertTrue(
            "an unregistration still owed is withdrawn before the registration is owed",
            ordered(owe, listOf("unregistrations?.forget(key)", "registrations.owe(key, payload)")),
        )

        val unregister = member(registrar, "fun unregisterMeshRelay(")
        assertTrue("unregistered as \"unregister\"", unregister.contains("meshPayloads(\"unregister\""))
        assertTrue(
            "a registration still owed is withdrawn, then the unregistration is owed",
            ordered(unregister, listOf("registrations?.forget(key)", "unregistrations.forget(key)", "unregistrations.owe(key, payload)")),
        )
        val unregistrations = member(registrar, "private fun meshUnregistrationsFor(")
        assertTrue("on /notify/unregister, proved by rfed's true", unregistrations.contains("RfedLinkOps(app, node, UNREGISTER, scope)"))
        assertTrue(
            "and the saved relay forgotten once rfed has proved all of them",
            ordered(member(registrar, "private fun meshUnregistrationProved("), listOf("meshUnproved.isNotEmpty()", "setMeshWakeRelayHex(context, null)")),
        )
        assertTrue(
            "a node change unregisters the mesh relay from the old node too",
            member(registrar, "fun deregisterFrom(").contains("getMeshWakeRelayHex"),
        )
    }

    @Test
    fun channelWakesFollowThePushToggle() {
        val registrar = source("src/main/kotlin/com/newendian/retichat/service/RfedNotifyRegistrar.kt")
        assertTrue(
            "registerForChannel registers the hosted relay for the channel too",
            member(registrar, "fun registerForChannel(").contains("StackRuntime.meshRelayHex"),
        )
        assertTrue(
            "deregisterForChannel unregisters it too",
            member(registrar, "fun deregisterForChannel(").contains("listOfNotNull(relayHexOrNull(context), StackRuntime.meshRelayHex)"),
        )
    }

    @Test
    fun theStackHostsItOnlyWithABluetoothRadio() {
        val stack = source("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt")

        val bootstrap = member(stack, "private suspend fun bootstrap(")
        assertTrue(
            "hosted after the lxmf.delivery publish, before the RTNode starts (its up-edge announces it)",
            ordered(bootstrap, listOf("transportPublishDestination(selfDestHash", "setUpMeshWake(app, withChannels = false)", "RTNodeBluetooth.start(")),
        )
        val setUp = member(stack, "private suspend fun setUpMeshWake(")
        assertTrue(
            "only with a Bluetooth radio configured",
            setUp.contains("MeshWake.wanted(") && setUp.contains("rtnodeSwitchOn = UserPreferences.isRtnodeBluetoothEnabled(app)"),
        )
        assertTrue(
            "hosted first, then remembered and registered",
            ordered(setUp, listOf("RetichatBridge.notifyRelayStart(", "meshRelayHex = relayHex", "setMeshWakeRelayHex(app, relayHex)", "registerMeshRelay(")),
        )
        assertTrue("with no radio, a relay registered before is unregistered", setUp.contains("unregisterMeshRelay("))

        val apply = member(stack, "suspend fun applyRtnodeBluetoothSetting(")
        assertTrue(
            "the switch applied on: hosted before Bluetooth starts",
            ordered(apply, listOf("setUpMeshWake(app, withChannels = true)", "RTNodeBluetooth.start(")),
        )
        assertTrue(
            "the switch applied off: Bluetooth stops, then the relay is unregistered",
            ordered(apply, listOf("RTNodeBluetooth.stop()", "setUpMeshWake(app, withChannels = true)")),
        )

        val stop = member(stack, "private fun shutdownNow(")
        assertTrue("stopped before the stack shuts down", ordered(stop, listOf("RetichatBridge.notifyRelayStop()", "RetichatBridge.shutdown()")))
    }

    @Test
    fun aWakeForThisDeviceCollectsAsAPushDoes() {
        val stack = source("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt")
        val fcm = source("src/main/kotlin/com/newendian/retichat/service/RetichatFcmService.kt")

        val wake = member(stack, "private fun onMeshWake(")
        assertTrue(
            "parsed; one for another receiver is ignored; a channel wake pulls that channel",
            ordered(wake, listOf("MeshWake.parse(payload)", "wake.receiver.contentEquals(selfDestHash)", "WakeWorker.enqueue(app)", "rfedChannelClient.pullDeferred(channel)")),
        )
        assertTrue("an FCM wake is handled the same way", fcm.contains("WakeWorker.enqueue(applicationContext)"))
    }
}
