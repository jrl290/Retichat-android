package com.newendian.retichat.bridge

import com.newendian.retichat.bridge.MsgpackKeys.Reason
import com.newendian.retichat.bridge.MsgpackKeys.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app's own judge of what Python LXMF refuses whole (MsgpackKeys, RFed
 * SPEC §17.14 "Repeated keys", "Unhashable keys", "Nesting"), for what the
 * group sync vectors (GroupEntryVectorsTest) do not pin: where depth starts,
 * the words and key names of each refusal, a timestamp's instant to the
 * microsecond, and bytes cut short.
 */
class MsgpackKeysTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun refusal(bytes: ByteArray, depth: Int = MsgpackKeys.FIELDS_DEPTH) = MsgpackKeys.refusal(bytes, depth)

    @Test
    fun theWordsAreLxmfRustsAndNameTheReferencesException() {
        assertEquals(
            "a map at depth 1 repeats a key (160), as Python LXMF refuses it (umsgpack DuplicateKeyException; RFed SPEC §17.14 \"Repeated keys\")",
            Refusal(Reason.REPEATED_KEY, 1, "160").why,
        )
        assertEquals(
            "a map at depth 2 has a key Python cannot hash (map 80), as Python LXMF refuses it (umsgpack UnhashableKeyException; RFed SPEC §17.14 \"Unhashable keys\")",
            Refusal(Reason.UNHASHABLE_KEY, 2, "map 80").why,
        )
        assertEquals(
            "a value at depth 494 is nested past depth 493, as Python LXMF refuses it (RecursionError on its delivery path; RFed SPEC §17.14 \"Nesting\")",
            Refusal(Reason.TOO_DEEP, 494, null).why,
        )
    }

    @Test
    fun depthCountsFromThePayloadSoTheFieldsMapIsOne() {
        // {0x77: 492 nested arrays, the innermost empty}: at the limit as a fields map, past it one
        // level down (the payload's own depth 0 is not the fields map's).
        val field = b(0x81, 0x77) + ByteArray(491) { 0x91.toByte() } + b(0x90)
        assertNull(refusal(field))
        assertEquals(Refusal(Reason.TOO_DEEP, 494, null), refusal(field, 2))
        assertNull(refusal(field, 0))
        // A scalar counts as a value: 491 arrays and an int at depth 493 read, 492 refused.
        assertNull(refusal(b(0x81, 0x77) + ByteArray(491) { 0x91.toByte() } + b(0x00)))
        assertEquals(Refusal(Reason.TOO_DEEP, 494, null), refusal(b(0x81, 0x77) + ByteArray(492) { 0x91.toByte() } + b(0x00)))
    }

    @Test
    fun eachKeyIsNamedByItsValueAndTheMapByItsOwnDepth() {
        fun repeated(key: ByteArray) = refusal(b(0x82) + key + b(0x00) + key + b(0x01))
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "-1"), repeated(b(0xD0, 0xFF)))
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "18446744073709551615"), repeated(b(0xCF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "\"a\\\"b\""), repeated(b(0xA3, 0x61, 0x22, 0x62)))
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "bin 0102"), repeated(b(0xC4, 0x02, 0x01, 0x02)))
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "ext 5 00"), repeated(b(0xD4, 0x05, 0x00)))
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "nil"), repeated(b(0xC0)))
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "true"), repeated(b(0xC3)))
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "1.5"), repeated(b(0xCB, 0x3F, 0xF8, 0, 0, 0, 0, 0, 0)))
        // The second key is named, as it is written: 1, then 1.0.
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "1.0"), refusal(b(0x82, 0x01, 0x00, 0xCA, 0x3F, 0x80, 0x00, 0x00, 0x01)))
        // In the Retichat field's map (depth 2), and a long key cut to 24 bytes.
        val long = b(0xC4, 30) + ByteArray(30) { 0x07 }
        assertEquals(
            Refusal(Reason.REPEATED_KEY, 2, "bin " + "07".repeat(24) + "..."),
            refusal(b(0x81, 0xCC, 0xD1, 0x82) + long + b(0x00) + long + b(0x01)),
        )
        // An unhashable key is named by its bytes.
        assertEquals(Refusal(Reason.UNHASHABLE_KEY, 1, "map 810102"), refusal(b(0x81, 0x81, 0x01, 0x02, 0x00)))
        assertEquals(Refusal(Reason.UNHASHABLE_KEY, 1, "array 9180"), refusal(b(0x81, 0x91, 0x80, 0x00)))
    }

    @Test
    fun aTimestampIsOneKeyWithAnotherOfItsInstantToTheMicrosecond() {
        val epoch32 = b(0xD6, 0xFF, 0, 0, 0, 0)
        val epoch96 = b(0xC7, 12, 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        // 999 ns past the epoch (timestamp 64: ns in the top 30 bits) floors to the same microsecond;
        // 1000 ns does not.
        fun ts64(ns: Long) = b(0xD7, 0xFF) + java.nio.ByteBuffer.allocate(8).putLong(ns shl 34).array()
        fun twice(a: ByteArray, c: ByteArray) = refusal(b(0x82) + a + b(0x00) + c + b(0x01))
        assertEquals(Reason.REPEATED_KEY, twice(epoch32, epoch96)?.reason)
        assertEquals(Reason.REPEATED_KEY, twice(epoch32, ts64(999))?.reason)
        assertNull(twice(epoch32, ts64(1000)))
        // Timestamp 96 with negative seconds: one second before the epoch, as 64-bit seconds.
        val before = b(0xC7, 12, 0xFF, 0, 0, 0, 0) + ByteArray(8) { 0xFF.toByte() }
        assertEquals(Reason.REPEATED_KEY, twice(before, before)?.reason)
        assertNull(twice(before, epoch96))
        // Another length is an ext like any other, by its bytes; a timestamp is never an int.
        assertEquals(Reason.REPEATED_KEY, twice(b(0xD4, 0xFF, 0x00), b(0xC7, 1, 0xFF, 0x00))?.reason)
        assertNull(twice(epoch32, b(0x00)))
    }

    @Test
    fun bytesCutShortEndTheWalkButARefusalBeforeThemIsFound() {
        // {1: 0, 1: 1, 2: "ab" cut short}: the repeat is met first.
        assertEquals(Reason.REPEATED_KEY, refusal(b(0x83, 0x01, 0x00, 0x01, 0x01, 0x02, 0xA2, 0x61))?.reason)
        // {1: "ab" cut short, 1: 1}: the walk ends at the cut; the reader fails on it.
        assertNull(refusal(b(0x82, 0x01, 0xA2, 0x61)))
        // A count no bytes can hold, a 32-bit length past the end: no allocation, no refusal.
        assertNull(refusal(b(0xDF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x00)))
        assertNull(refusal(b(0x81, 0x01, 0xC6, 0xFF, 0xFF, 0xFF, 0xFF)))
        assertNull(refusal(ByteArray(0)))
    }

    @Test
    fun aReservedByteIsReadAsNilAsLxmfRustReadsIt() {
        // 0xc1 is never used; rmpv (LXMF-rust) reads it as nil, so it is nil's key here too.
        // Python refuses it outright (ReservedCodeException), a shape not yet ruled on.
        assertEquals(Refusal(Reason.REPEATED_KEY, 1, "nil"), refusal(b(0x82, 0xC0, 0x00, 0xC1, 0x01)))
    }
}
