package com.newendian.retichat.bridge

import com.newendian.retichat.service.DistroCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fields map is the sender's bytes: on the distro path exactly as packed,
 * on the direct path an rmpv re-encoding of them (which keeps ext values).
 * Whatever they hold, [LxmfFields.decode] returns. Before 2026-09-29 an ext
 * value left the reader out of step, it then took a length from the sender's
 * bytes and allocated it, and the OutOfMemoryError (an Error, which decode's
 * `catch (e: Exception)` does not catch) killed the app before the message
 * was stored. Any stranger could send that to a distro address.
 */
class LxmfFieldsHostileInputTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** decode, with anything thrown (an Error included) turned into a failure naming the input. */
    private fun decode(hexBytes: String): LxmfFields = try {
        LxmfFields.decode(hex(hexBytes))
    } catch (t: Throwable) {
        throw AssertionError("decode(${hexBytes.take(40)}) threw ${t::class.java.name}", t)
    }

    @Test
    fun theReviewedDistroPayloadDecodesInsteadOfKillingTheApp() {
        // "fields" as lxmf_rust unwrap_blob().to_json() gave it for the payload
        // [ts, b"", b"caption", {0x99: fixext16(1, C6 7F FF FF FF 00 x11), 0x05: []}]:
        // valid msgpack, the sender's bytes, read as RfedDistroClient reads them.
        val raw = DistroCodec.fieldsBytes("gsyZ2AHGf////wAAAAAAAAAAAAAABZA=")!!
        val fields = try {
            LxmfFields.decode(raw)
        } catch (t: Throwable) {
            throw AssertionError("decode threw ${t::class.java.name}", t)
        }
        // The ext value is stepped over, so the map is read in step: both keys.
        assertEquals(2, fields.size)
        assertTrue(fields.has(0x99))
        assertTrue(fields.has(LxmfFields.FIELD_FILE_ATTACHMENTS))
        assertTrue(fields.getFileAttachments().isEmpty())
    }

    @Test
    fun everyExtFormIsSteppedOverAndTheAttachmentAfterItKept() {
        // Each ext's data holds a bin32 header claiming 2 GB: read out of step,
        // the reader would take it as a length.
        val payload = "c67fffffff"
        val exts = listOf(
            "d401" + "c6",                              // fixext1
            "d501" + "c67f",                            // fixext2
            "d601" + "c67fffff",                        // fixext4
            "d701" + payload + "000000",                // fixext8
            "d801" + payload + "00".repeat(11),         // fixext16
            "c705" + "01" + payload,                    // ext8
            "c80005" + "01" + payload,                  // ext16
            "c900000005" + "01" + payload,              // ext32
            "c700" + "01",                              // ext8, no data
        )
        for (ext in exts) {
            // {0x99: ext, 0x05: [["a.jpg", bin 01 02 03]]}; the bin ends the buffer.
            val fields = decode("82" + "cc99" + ext + "05" + "91" + "92" + "a5612e6a7067" + "c403010203")
            assertEquals(ext, 2, fields.size)
            val attachments = fields.getFileAttachments()
            assertEquals(ext, listOf("a.jpg"), attachments.map { it.first })
            assertArrayEquals(ext, byteArrayOf(1, 2, 3), attachments[0].second)
        }
    }

    @Test
    fun aLengthOrCountPastTheEndIsMalformedNeverAnAllocation() {
        for (bad in listOf(
            "8105c67fffffff",           // bin32 of 2^31-1 bytes
            "8105db7fffffff",           // str32
            "8105dd7fffffff",           // array32 of 2^31-1 elements
            "8105df7fffffff0590",       // map32 inside, one entry present
            "df7fffffff0590",           // map32 at the top
            "8105c6ffffffff",           // bin32 length read as negative
            "8105ddffffffff",           // array32 count read as negative
            "8105c97fffffff01",         // ext32 of 2^31-1 bytes
            "8105d801c6",               // fixext16 with 1 of its 16 bytes
            "8105c40a0102",             // bin8 of 10 with 2 there
        )) {
            assertEquals(bad, 0, decode(bad).size)
        }
    }

    @Test
    fun nestingIsSteppedOverToDepth493SoItCannotOverflowTheStackOrEmptyTheMap() {
        // {0x05: [[[...[]...]]], 0x02: 7}: containers below the deepest an accessor
        // reads (3: map, 0x05 array, [name, data]) are stepped over whole, without
        // recursion, and the rest of the map is read in step, down to depth 493 (RFed SPEC
        // §17.14 "Nesting": 0x05's value at depth 2, so `depth` + 1 arrays end at depth + 2).
        // Until 2026-10-11 a container below 64 made the whole map unreadable.
        for (depth in listOf(3, 62, 63, 491)) {
            val fields = decode("8205" + "91".repeat(depth) + "90" + "0207")
            assertEquals("$depth deep", 2, fields.size)
            assertTrue(fields.has(LxmfFields.FIELD_FILE_ATTACHMENTS))
            assertTrue(fields.getFileAttachments().isEmpty())
            assertEquals("$depth deep", 7L, fields.getInt(0x02))
        }
        // One level more, or far more, is refused whole, as Python LXMF refuses it: no fields, and
        // no StackOverflowError. Until the b873c00 round 200 000 levels were stepped over and read.
        for (depth in listOf(492, 200_000)) {
            assertEquals("$depth deep", 0, decode("8205" + "91".repeat(depth) + "90" + "0207").size)
            val received = LxmfFields.receive(hex("8205" + "91".repeat(depth) + "90" + "0207"))
            assertTrue("$depth deep: $received", received is LxmfFields.Received.Refused && received.why.contains("a value at depth 494 is nested past depth 493"))
        }
        // An attachment pair at its own depth is read, whatever is nested beside it (to the limit).
        val pair = "92" + "a161" + "c40101"          // ["a", b"\x01"]
        val withDeep = decode("8205" + "92" + pair + "91".repeat(490) + "90" + "0207")
        assertEquals(1, withDeep.getFileAttachments().size)
        assertEquals("a", withDeep.getFileAttachments()[0].first)
    }

    @Test
    fun aValueSteppedOverIsCheckedAgainstTheBytesLeft() {
        // Deeper than an accessor reads, so stepped over: each length and count is
        // checked as the reader checks it, and nothing is allocated.
        val below = "8105" + "91919191"            // {0x05: [[[[ ... ]]]]}: the fourth array is stepped over
        for (bad in listOf(
            "ddffffffff",              // array32 of 2^32-1 elements
            "dfffffffff",              // map32 of 2^32-1 entries
            "dc0005",                  // array16 of 5 with nothing there
            "c6ffffffff",              // bin32 of 2^32-1 bytes
            "c97fffffff01",            // ext32 of 2^31-1 bytes
            "d801c6",                  // fixext16 with 1 of its 16 bytes
            "db00000002a1",            // str32 of 2 with 1 there
            "cb0000",                  // float64 cut short
            "92",                      // an array of 2 with none there
        )) {
            assertEquals(bad, 0, decode(below + bad).size)
            var reported: Throwable? = null
            LxmfFields.receive(hex(below + bad), onFailure = { reported = it })
            assertTrue("$bad: reported", reported is IllegalArgumentException || reported is java.nio.BufferUnderflowException)
        }
        // Every type stepped over in step: the key after them is read.
        val values = listOf(
            "00", "ff", "a3616263", "c0", "c1", "c2", "c3",
            "c40100", "c5000100", "c60000000100",                         // bin 8, 16, 32 of one byte
            "c7010000", "c800010000", "c9000000010000",                   // ext 8, 16, 32 of one byte
            "ca00000000", "cb0000000000000000",                           // float 32, 64
            "cc01", "cd0001", "ce00000001", "cf0000000000000001",         // uint 8..64
            "d001", "d10001", "d200000001", "d30000000000000001",         // int 8..64
            "d40100", "d5010000", "d601" + "00".repeat(4), "d701" + "00".repeat(8), "d801" + "00".repeat(16),
            "d90161", "da000161", "db0000000161",                         // str 8, 16, 32
            "dc0000", "dd00000000", "de0000", "df00000000", "80", "90",   // empty containers
            "920102", "810102", "dc0002" + "0102", "de0001" + "0102",    // and ones holding values
        )
        val stepped = "dc" + "%04x".format(values.size) + values.joinToString("")
        val ok = decode("8205" + "919191" + stepped + "0207")
        assertEquals(7L, ok.getInt(0x02))
    }
}
