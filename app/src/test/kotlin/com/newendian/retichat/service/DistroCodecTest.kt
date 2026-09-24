package com.newendian.retichat.service

import com.newendian.retichat.service.DistroCodec.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroCodecTest {
    private val hex = (0 until 64).joinToString("") { "%02x".format(it) }

    @Test
    fun parsesEveryAcceptedKeyForm() {
        val expected = hex
        assertEquals(expected, DistroCodec.parsePrivateKey(hex)!!.toHex())
        assertEquals(expected, DistroCodec.parsePrivateKey("  ${hex.uppercase()}\n")!!.toHex())
        assertEquals(expected, DistroCodec.parsePrivateKey("rfed-distro-private-key://$hex")!!.toHex())
        assertEquals(expected, DistroCodec.parsePrivateKey("RFED-DISTRO-ID://$hex/")!!.toHex())
    }

    @Test
    fun rejectsWrongLengthOrJunk() {
        assertNull(DistroCodec.parsePrivateKey(hex.dropLast(2)))
        assertNull(DistroCodec.parsePrivateKey("lxma://$hex"))
        assertNull(DistroCodec.parsePrivateKey("zz" + hex.drop(2)))
        assertNull(DistroCodec.parsePrivateKey(""))
    }

    @Test
    fun exportUriRoundTrips() {
        val key = DistroCodec.hexToBytes(hex)!!
        val uri = DistroCodec.exportUri(key)
        assertTrue(uri.startsWith("rfed-distro-private-key://"))
        assertEquals(hex, DistroCodec.parsePrivateKey(uri)!!.toHex())
    }

    @Test
    fun affirmativeResponses() {
        assertTrue(DistroCodec.isAffirmative(byteArrayOf(0xc3.toByte())))
        assertTrue(DistroCodec.isAffirmative(byteArrayOf(0x92.toByte(), 0xc3.toByte(), 0xc0.toByte())))
        assertFalse(DistroCodec.isAffirmative(byteArrayOf(0xc2.toByte())))
        assertFalse(DistroCodec.isAffirmative(byteArrayOf(0x91.toByte(), 0xc2.toByte())))
        assertFalse(DistroCodec.isAffirmative(null))
        assertFalse(DistroCodec.isAffirmative(ByteArray(0)))
    }

    @Test
    fun decodesPullResponse() {
        val hash = ByteArray(16) { 0x11 }
        val blob = ByteArray(40) { 0x22 }
        val data = byteArrayOf(0x92.toByte(), 0x91.toByte(), 0x92.toByte(), 0xc4.toByte(), 16) + hash +
            byteArrayOf(0xc4.toByte(), 40) + blob + byteArrayOf(0xc3.toByte())
        val (pairs, more) = DistroCodec.decodePullResponse(data)!!
        assertEquals(1, pairs.size)
        assertTrue(pairs[0].first.contentEquals(hash))
        assertTrue(pairs[0].second.contentEquals(blob))
        assertTrue(more)
        val empty = byteArrayOf(0x92.toByte(), 0x90.toByte(), 0xc2.toByte())
        assertEquals(0, DistroCodec.decodePullResponse(empty)!!.first.size)
        assertNull(DistroCodec.decodePullResponse(byteArrayOf(0x91.toByte(), 0x90.toByte())))
    }

    @Test
    fun seenListIsCappedAndDeduped() {
        var seen = emptyList<String>()
        repeat(600) { seen = DistroCodec.appendSeen(seen, "k$it", cap = 500) }
        assertEquals(500, seen.size)
        assertEquals("k100", seen.first())
        assertEquals(seen, DistroCodec.appendSeen(seen, "k599", cap = 500))
        assertNotNull(DistroCodec.messageId("ab", 1.5, "hi"))
        assertEquals(32, DistroCodec.messageId("ab", 1.5, "hi").length)
    }
}
