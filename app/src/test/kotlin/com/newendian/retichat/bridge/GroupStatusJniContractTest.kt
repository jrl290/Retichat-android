package com.newendian.retichat.bridge

import com.newendian.retichat.data.model.toHex
import com.newendian.retichat.data.repository.GroupStatusVectors
import com.newendian.retichat.data.repository.GroupStatuses
import com.newendian.retichat.names.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The Kotlin side of the JNI surface the group follow-up uses (member statuses
 * and keys, RFed-spec Group.md 2026-10-06), against the Rust crate (the .so is
 * not loaded in a JVM test): a missing symbol is an UnsatisfiedLinkError on the
 * first call, a callback whose descriptor drifted is a silent no-op on a Rust
 * thread, and a buffer one side writes and the other reads differently is a
 * message that never counts. Every new `external fun` is pinned to its Rust
 * symbol, as PropagationJniContractTest does for the distro outbox.
 */
class GroupStatusJniContractTest {
    private val bridge = File("src/main/kotlin/com/newendian/retichat/bridge/RetichatBridge.kt").readText()
    private val rust = File("../rust/retichat-jni/src/lib.rs").readText()
    private val glue = File("../rust/retichat-jni/src/group_status.rs").readText()
    private val v by lazy { GroupStatusVectors.load() }

    @Test
    fun everyNewExternalFunctionHasItsRustSymbolWithTheTypesKotlinDeclares() {
        for (name in listOf("nativeMessageSetRetichatStatuses", "nativeGroupUnpackPacked")) {
            assertTrue(name, Regex("private external fun $name\\(").containsMatchIn(bridge))
            assertTrue(name, rust.contains("fn Java_com_newendian_retichat_bridge_RetichatBridge_$name("))
        }
        // (handle: Long, elements: Array<ByteArray>): Int  <->  (env, class, handle: jlong, elements: JObjectArray) -> jint
        assertTrue(bridge.contains("private external fun nativeMessageSetRetichatStatuses(handle: Long, elements: Array<ByteArray>): Int"))
        val set = rust.substring(rust.indexOf("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeMessageSetRetichatStatuses("))
        val setSignature = set.substring(0, set.indexOf("{"))
        assertTrue(setSignature, setSignature.contains("handle: jlong") && setSignature.contains("elements: JObjectArray") && setSignature.contains("-> jint"))
        // (packed: ByteArray): ByteArray?  <->  (env, class, packed: JByteArray) -> jbyteArray
        assertTrue(bridge.contains("private external fun nativeGroupUnpackPacked(packed: ByteArray): ByteArray?"))
        val unpack = rust.substring(rust.indexOf("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeGroupUnpackPacked("))
        val unpackSignature = unpack.substring(0, unpack.indexOf("{"))
        assertTrue(unpackSignature, unpackSignature.contains("packed: JByteArray") && unpackSignature.contains("-> jbyteArray"))
        // The bytes are another member's: a panic is caught and reported as null, never the process's end.
        assertTrue(unpack.substring(0, unpack.indexOf("bytes_or_null(")).contains("std::panic::catch_unwind"))
    }

    @Test
    fun theDeliveryCallbackIsTheElevenArgumentOneKotlinDeclaresAndRustCalls() {
        val declared = bridge.replace(Regex("\\s+"), " ")
        assertTrue(declared.contains(
            "fun onMessage( hash: ByteArray, srcHash: ByteArray, destHash: ByteArray, title: String, content: String, " +
                "timestamp: Double, signatureValid: Boolean, unverifiedReason: Int, fieldsRaw: ByteArray, " +
                "senderKey: ByteArray?, packed: ByteArray?, )"
        ))
        assertTrue(bridge.contains("""(`([B[B[BLjava/lang/String;Ljava/lang/String;DZI[B[B[B)V`)"""))
        assertTrue(rust.contains(""""([B[B[BLjava/lang/String;Ljava/lang/String;DZI[B[B[B)V","""))
        // The one Kotlin implementation takes and passes both.
        val runtime = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()
        assertTrue(runtime.contains("unverifiedReason: Int, fieldsRaw: ByteArray, senderKey: ByteArray?, packed: ByteArray?,"))
        assertTrue(runtime.contains("unverifiedReason, fieldsRaw, senderKey, packed,"))
        // R8 keeps the callback and its implementations: Rust finds onMessage by name.
        val rules = File("proguard-rules.pro").readText()
        assertTrue(rules.contains("-keep interface com.newendian.retichat.bridge.MessageCallback { *; }"))
        assertTrue(rules.contains("-keep class * implements com.newendian.retichat.bridge.MessageCallback { *; }"))
    }

    @Test
    fun theCallbackIsRegisteredOnTheRouterToBeGivenTheMessageItselfAndRemembersNothing() {
        val registration = rust.substring(rust.indexOf("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeRouterSetDeliveryCallback("))
        val body = registration.substring(0, registration.indexOf("/// A byte array for Kotlin"))
        assertTrue(body.contains("register_delivery_callback(Arc::new(deliver_to_kotlin))"))
        assertFalse("the snapshot has neither the packed bytes nor the signature", body.contains("lxmf::router_set_delivery_callback("))
        // What the glue does to a message: judge it, never remember a key (Kotlin does, after the group rule).
        assertFalse(glue.substringBefore("#[cfg(test)]").contains("remember_destination"))
        assertFalse(glue.substringBefore("#[cfg(test)]").contains("thread::sleep"))
        assertFalse(glue.substringBefore("#[cfg(test)]").contains("thread::spawn"))
    }

    @Test
    fun theLimitsAndTheKeyAreTheSameInKotlinAndInRust() {
        fun rustConst(name: String) = Regex("pub const $name: (?:usize|u8) = ([0-9A-Za-z_ +]+);").find(glue)!!.groupValues[1]
        assertEquals("2048", rustConst("STATUS_PAYLOAD_LIMIT"))
        assertEquals("96", rustConst("PACKED_HEADER_LEN"))
        assertEquals("PACKED_HEADER_LEN + STATUS_PAYLOAD_LIMIT", rustConst("STATUS_PACKED_LIMIT"))
        assertEquals("10", rustConst("RF_GROUP_STATUSES"))
        assertEquals(2048, GroupStatuses.STATUS_PAYLOAD_LIMIT)
        assertEquals(96, GroupStatuses.PACKED_HEADER_LENGTH)
        assertEquals(2144, GroupStatuses.STATUS_PACKED_LIMIT)
        assertEquals(10, LxmfFields.RF_GROUP_STATUSES)
        assertEquals(64, UnpackedGroupMessage.KEY_LENGTH)
        // The verdict codes are the router's unverified_reason, which Signature names.
        assertEquals(0, Signature.VALIDATED)
        assertEquals(1, Signature.SOURCE_UNKNOWN)
        assertEquals(2, Signature.INVALID)
        assertTrue(glue.contains("pub const VALIDATED: u8 = 0;"))
        assertTrue(glue.contains("pub const SOURCE_UNKNOWN: u8 = LXMessage::SOURCE_UNKNOWN;"))
        assertTrue(glue.contains("pub const SIGNATURE_INVALID: u8 = LXMessage::SIGNATURE_INVALID;"))
    }

    /**
     * The unpack buffer of the vectors' first message, pinned to one literal: the
     * Rust test `the_unpack_buffer_is_exactly_these_bytes_which_kotlins_reader_pins_too`
     * writes exactly these bytes, so a writer and a reader that drift apart cannot both pass.
     */
    private val elementHex = "0001727e215e19daf66bf647e96143a22117315f18700c02eab7992d9c2ee50374e2a7fea02b1dd94ea73fd11703b1457433b358d68d9d7ba707a320a0659f40b05241daac4ee0a00000c8de11a32801ffcf9761b8e3a1689eb1ebdc1a5b8191e610aea8b7134a9a53712852128a569309197de73a28fa5acdb41cee640031f5122194437016a6ac0119000000d681ccd18500c405416c69636501d920356537316163326430623163346636653961336438653766366335623461333904a661636365707405d920333135663138373030633032656162373939326439633265653530333734653209d97933313566313837303063303265616237393932643963326565353033373465323a794e34526f7967422f382b5859626a6a6f57696573657663476c75426b65595172716933453071615533456f55684b4b56704d4a4758336e4f696a3657733230484f356b4144483145694755513341577071774247513d3d"

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun theUnpackBufferIsExactlyTheseBytesWhichRustsWriterPinsTooAndReadsAsTheVectorSays() {
        assertTrue("the same literal is in the Rust test", glue.contains("const ELEMENT_HEX: &str = \"$elementHex\";"))
        val m = v.messages.getValue("alice_accept_keyed_retichat")
        val parsed = UnpackedGroupMessage.parse(unhex(elementHex))
        assertNotNull(parsed)
        parsed!!
        assertEquals(Signature.VALIDATED, parsed.signature)
        assertEquals(v.hashOf("creator"), parsed.destination.toHex())
        assertEquals(v.hashOf("alice"), parsed.source.toHex())
        assertEquals(m.hashHex, parsed.hash.toHex())
        assertEquals(1790000002.5, parsed.timestamp, 0.0)
        assertArrayEquals(v.identities.getValue("alice").publicKey, parsed.senderKey)
        assertArrayEquals(m.fields, parsed.fieldsRaw)
        // The layout: the offsets the doc table gives.
        val bytes = unhex(elementHex)
        assertEquals(74, UnpackedGroupMessage.FIXED_LENGTH)
        assertEquals(1, bytes[1].toInt())
        assertEquals(bytes.size, 74 + 64 + 4 + parsed.fieldsRaw.size)
        assertEquals(parsed.fieldsRaw.size, ByteBuffer.wrap(bytes, 138, 4).int)
    }

    @Test
    fun aBufferWithoutAKeyReadsWithNoKeyAndOneThatIsNotWhatRustWritesIsNotRead() {
        val bytes = unhex(elementHex)
        // Without the key block: the flag is 0 and the 64 bytes are gone.
        val keyless = bytes.copyOfRange(0, 1) + byteArrayOf(0) + bytes.copyOfRange(2, 74) + bytes.copyOfRange(138, bytes.size)
        val parsed = UnpackedGroupMessage.parse(keyless)!!
        assertNull(parsed.senderKey)
        assertEquals(bytes.size - 64, keyless.size)
        // Every way of not being the buffer: too short, a verdict or a flag nobody writes, a length that lies, trailing bytes.
        assertNull(UnpackedGroupMessage.parse(ByteArray(0)))
        assertNull(UnpackedGroupMessage.parse(bytes.copyOf(77)))
        assertNull(UnpackedGroupMessage.parse(bytes.copyOf().also { it[0] = 3 }))
        assertNull(UnpackedGroupMessage.parse(bytes.copyOf().also { it[1] = 2 }))
        assertNull(UnpackedGroupMessage.parse(bytes.copyOf().also { it[141] = (it[141] + 1).toByte() }))
        assertNull(UnpackedGroupMessage.parse(bytes + byteArrayOf(0)))
        assertNull(UnpackedGroupMessage.parse(bytes.copyOf(bytes.size - 1)))
        // A key flag with no room for the key.
        assertNull(UnpackedGroupMessage.parse(keyless.copyOf().also { it[1] = 1 }))
        // A negative length (a 32-bit value above 2^31) is no length.
        assertNull(UnpackedGroupMessage.parse(keyless.copyOf().also { it[74] = 0x80.toByte() }))
    }
}
