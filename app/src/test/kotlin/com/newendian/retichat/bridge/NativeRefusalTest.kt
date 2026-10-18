package com.newendian.retichat.bridge

import com.newendian.retichat.service.DistroUnwrapOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RFed SPEC §17.14 "Repeated keys" (James, 2026-10-11: parity with Python
 * LXMF): a message whose payload repeats a key is refused whole by the native
 * layer, which writes the one log line. The app takes the error as a refusal on
 * every path that can report one, writes no second line, and stores, marks as
 * seen or applies nothing. On the direct path the router refuses it before the
 * delivery callback (LXMF-rust tests/repeated_keys_log.rs), so the app never
 * hears of it.
 */
class NativeRefusalTest {
    private val unwrapError =
        "refused whole: a map at depth 2 repeats a key (1), as Python LXMF refuses it (umsgpack DuplicateKeyException; RFed SPEC §17.14 \"Repeated keys\")"

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    @Test
    fun theWordsAreLxmfRustsAsTheJniCratePinsThem() {
        assertEquals("refused whole:", NativeRefusal.REFUSED_WHOLE)
        val jni = File("../rust/retichat-jni/src/group_status.rs").readText()
        assertTrue(jni.contains("assert_eq!(lxmf_rust::msgpack_keys::REFUSED_WHOLE, \"${NativeRefusal.REFUSED_WHOLE}\");"))
        // ...and the unwrap's and the group receipt's errors carry them.
        assertTrue(jni.contains("err.contains(lxmf_rust::msgpack_keys::REFUSED_WHOLE)"))
    }

    @Test
    fun aRefusalIsKnownByItsWordsWhereverTheyStand() {
        assertTrue(NativeRefusal.isRefusal(unwrapError))
        // The channel codec puts its own words first.
        assertTrue(NativeRefusal.isRefusal("LXMessage::unpack: $unwrapError"))
        assertFalse(NativeRefusal.isRefusal(null))
        assertFalse(NativeRefusal.isRefusal(""))
        assertFalse(NativeRefusal.isRefusal("msgpack decode: invalid marker"))
        assertFalse(NativeRefusal.isRefusal("channel: prelude key does not produce the claimed source"))
    }

    @Test
    fun theDistroUnwrapsAnswerIsARefusalARejectionAnotherDistrosBlobOrAMessage() {
        assertEquals(DistroUnwrapOutcome.Refused, DistroUnwrapOutcome.of(null) { unwrapError })
        assertEquals(DistroUnwrapOutcome.Rejected("decrypt failed"), DistroUnwrapOutcome.of(null) { "decrypt failed" })
        assertEquals(DistroUnwrapOutcome.Rejected(null), DistroUnwrapOutcome.of(null) { null })
        // The error is asked only for a null answer.
        assertEquals(DistroUnwrapOutcome.OtherDistro, DistroUnwrapOutcome.of("") { error("not asked") })
        assertEquals(DistroUnwrapOutcome.Message("{\"hash\":\"x\"}"), DistroUnwrapOutcome.of("{\"hash\":\"x\"}") { error("not asked") })
    }

    @Test
    fun theDistroPathEndsARefusalBeforeAnythingIsReadMarkedOrStoredWithNoSecondLine() {
        val client = src("service/RfedDistroClient.kt")
        val blob = client.substringAfter("suspend fun handleBlob(context: Context, blob: ByteArray): Boolean {").substringBefore("\n    /**")
        val outcome = blob.indexOf("DistroUnwrapOutcome.of(RetichatBridge.distroUnwrap(distro, blob)) { RetichatBridge.lastError() }")
        val refused = blob.indexOf("DistroUnwrapOutcome.Refused -> return false\n")
        assertTrue(outcome >= 0 && refused > outcome)
        assertTrue(refused < blob.indexOf("val o = JSONObject(json)"))
        assertTrue(refused < blob.indexOf("UserPreferences.markDistroSeen("))
        // The one Log line about a failed unwrap is the rejection's, never the refusal's.
        assertEquals(1, Regex("blob rejected").findAll(blob).count())
        assertTrue(blob.contains("is DistroUnwrapOutcome.Rejected -> {\n                Log.w(TAG, \"blob rejected: \${unwrapped.error}\")"))
    }

    @Test
    fun theChannelPathEndsARefusalWithNoSecondLine() {
        val channel = src("service/RfedChannelClient.kt")
        val dispatch = channel.substringAfter("private suspend fun dispatchBlob(").substringBefore("val result = ChannelLxmUnpackResult.parse(raw)")
        val refused = dispatch.indexOf("if (NativeRefusal.isRefusal(error)) return@withContext")
        assertTrue(refused > dispatch.indexOf("val error = RetichatBridge.lastError()"))
        assertTrue(refused < dispatch.indexOf("Log.w(TAG, \"channelLxmUnpack failed"))
    }

    @Test
    fun theGroupReceiptTellsARefusalFromAMessageThatIsNotLxmf() {
        val bridge = src("bridge/RetichatBridge.kt")
        val unpack = bridge.substringAfter("fun groupUnpackPacked(packed: ByteArray): GroupUnpack {").substringBefore("\n    }")
        assertTrue(unpack.contains("?: return if (NativeRefusal.isRefusal(lastError())) GroupUnpack.Refused else GroupUnpack.NotLxmf"))
        // A reply the bridge cannot read is no refusal: lastError is asked only for a null reply.
        assertTrue(unpack.contains("UnpackedGroupMessage.parse(raw)?.let { GroupUnpack.Unpacked(it) } ?: GroupUnpack.NotLxmf"))
    }

    @Test
    fun theDirectPathHearsOnlyWhatTheRouterDelivered() {
        // The router refuses the message before its delivery callback, which is the only way a
        // message reaches onMessageReceived.
        val jni = File("../rust/retichat-jni/src/lib.rs").readText()
        assertTrue(jni.contains("router.register_delivery_callback(Arc::new(deliver_to_kotlin));"))
        val callers = File("src/main/kotlin").walk().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\.onMessageReceived\(""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(listOf("StackRuntime.kt"), callers)
    }
}
