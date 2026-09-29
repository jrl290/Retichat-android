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
    fun nestingIsCappedSoItCannotOverflowTheStack() {
        // {0x05: [[[...[]...]]]}: the map is container 1, so 62 one-element
        // arrays and the empty one inside them reach container 64, the cap
        // (LXMF's own fields nest 3 deep: map, 0x05 array, [name, data]).
        val atCap = decode("8105" + "91".repeat(62) + "90")
        assertTrue(atCap.has(LxmfFields.FIELD_FILE_ATTACHMENTS))
        assertEquals(0, decode("8105" + "91".repeat(63) + "90").size)
        // Far deeper than any stack: malformed, not a StackOverflowError.
        assertEquals(0, decode("8105" + "91".repeat(200_000) + "90").size)
    }
}
