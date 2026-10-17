package com.newendian.retichat.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REACTIONS-DESIGN.md §3: field 0x40 (upstream LXMF 1.2.0 FIELD_REACTION) as
 * every receive path reads it, and the Retichat field's keys 11 (GROUP_POST_ID)
 * and 12 (SENT_HASH). Bin and str are both taken; integer keys of any width;
 * a missing key or content over 64 bytes is refused with a reason.
 */
class ReactionFieldTest {
    private val hash = ByteArray(32) { (0xA0 + it).toByte() }
    private val thumbsUp = "👍".toByteArray()

    // ---- A little msgpack writer: just what these maps need ----

    private fun bin(b: ByteArray) = byteArrayOf(0xC4.toByte(), b.size.toByte()) + b
    private fun str(b: ByteArray) = when {
        b.size < 32 -> byteArrayOf((0xA0 or b.size).toByte()) + b
        else -> byteArrayOf(0xD9.toByte(), b.size.toByte()) + b
    }
    private fun str(s: String) = str(s.toByteArray())
    private fun fixmap(vararg entries: ByteArray): ByteArray {
        require(entries.size % 2 == 0)
        return entries.fold(byteArrayOf((0x80 or (entries.size / 2)).toByte())) { acc, e -> acc + e }
    }
    private fun int(n: Int) = byteArrayOf(n.toByte())
    private fun uint8(n: Int) = byteArrayOf(0xCC.toByte(), n.toByte())
    private fun uint16(n: Int) = byteArrayOf(0xCD.toByte(), (n shr 8).toByte(), n.toByte())
    private fun uint32(n: Int) = byteArrayOf(0xCE.toByte(), 0, 0, (n shr 8).toByte(), n.toByte())

    private fun reactionOf(fields: ByteArray) = LxmfFields.read(fields).reaction()

    @Test
    fun theUpstreamFormIsReadBinAndBin() {
        // As LXMF-rust's message_set_reaction writes it (its own vector).
        val r = reactionOf(fixmap(int(0x40), fixmap(int(0x00), bin(hash), int(0x01), bin(thumbsUp))))
        r as LxmfFields.Reaction.Valid
        assertArrayEquals(hash, r.to)
        assertEquals("👍", r.content)
        assertEquals(hash.joinToString("") { "%02x".format(it) }, r.toHex)
    }

    @Test
    fun aStrIsTakenAsTheRawBytesItHolds() {
        // A str REACTION_TO holding raw hash bytes (not UTF-8) and a str content.
        val r = reactionOf(fixmap(int(0x40), fixmap(int(0x00), str(hash), int(0x01), str("❤️"))))
        r as LxmfFields.Reaction.Valid
        assertArrayEquals("the raw bytes, not a UTF-8 decoding of them", hash, r.to)
        assertEquals("❤️", r.content)
        // A 16-byte Group post id, the same either way.
        val postId = ByteArray(16) { it.toByte() }
        val g = reactionOf(fixmap(int(0x40), fixmap(int(0x00), bin(postId), int(0x01), str(""))))
        g as LxmfFields.Reaction.Valid
        assertArrayEquals(postId, g.to)
        assertEquals("an empty reaction is a removal, still a reaction", "", g.content)
    }

    @Test
    fun integerKeysOfAnyWidthAreTheSameKey() {
        for ((field, to, content) in listOf(
            Triple(uint8(0x40), uint16(0), uint32(1)),
            Triple(uint16(0x40), uint8(0), uint8(1)),
            Triple(uint32(0x40), uint32(0), uint16(1)),
        )) {
            val r = reactionOf(fixmap(field, fixmap(to, bin(hash), content, bin(thumbsUp))))
            assertTrue(r is LxmfFields.Reaction.Valid)
        }
    }

    @Test
    fun missingKeysAndOverLongContentAreRefusedWithAReason() {
        fun why(r: LxmfFields.Reaction) = (r as LxmfFields.Reaction.Invalid).why
        assertEquals("REACTION_TO is missing", why(reactionOf(fixmap(int(0x40), fixmap(int(0x01), bin(thumbsUp))))))
        assertEquals("REACTION_CONTENT is missing", why(reactionOf(fixmap(int(0x40), fixmap(int(0x00), bin(hash))))))
        assertEquals("REACTION_TO is not bin or str",
            why(reactionOf(fixmap(int(0x40), fixmap(int(0x00), int(5), int(0x01), bin(thumbsUp))))))
        assertEquals("REACTION_CONTENT is not bin or str",
            why(reactionOf(fixmap(int(0x40), fixmap(int(0x00), bin(hash), int(0x01), byteArrayOf(0xC0.toByte()))))))
        // 64 bytes is the most; 65 is refused, bin or str.
        val at64 = reactionOf(fixmap(int(0x40), fixmap(int(0x00), bin(hash), int(0x01), bin(ByteArray(64) { 'a'.code.toByte() }))))
        assertTrue(at64 is LxmfFields.Reaction.Valid)
        assertEquals("REACTION_CONTENT is 65 bytes, over 64",
            why(reactionOf(fixmap(int(0x40), fixmap(int(0x00), bin(hash), int(0x01), bin(ByteArray(65) { 'a'.code.toByte() }))))))
        assertEquals("REACTION_CONTENT is 65 bytes, over 64",
            why(reactionOf(fixmap(int(0x40), fixmap(int(0x00), bin(hash), int(0x01), str(ByteArray(65) { 'a'.code.toByte() }))))))
        assertEquals("REACTION_CONTENT is not UTF-8",
            why(reactionOf(fixmap(int(0x40), fixmap(int(0x00), bin(hash), int(0x01), bin(byteArrayOf(0xFF.toByte())))))))
    }

    @Test
    fun noMapAtFieldFortyIsNoReaction() {
        assertSame(LxmfFields.Reaction.None, LxmfFields.EMPTY.reaction())
        assertSame(LxmfFields.Reaction.None, reactionOf(fixmap(int(0x05), byteArrayOf(0x90.toByte()))))
        // Not a map: carry on as today (§3).
        assertSame(LxmfFields.Reaction.None, reactionOf(fixmap(int(0x40), bin(hash))))
        assertSame(LxmfFields.Reaction.None, reactionOf(fixmap(int(0x40), str("👍"))))
    }

    @Test
    fun theRetichatFieldsPostIdAndSentHash() {
        val postId = "00112233445566778899AABBCCDDEEFF"
        val sent = "ab".repeat(32)
        assertNull(LxmfFields.EMPTY.postId())
        assertNull(LxmfFields.EMPTY.sentHash())
        // {0xD1: {11: str, 12: str}}: 0xD1 is written as uint8.
        val d1 = uint8(0xD1)
        val read = LxmfFields.read(fixmap(d1, fixmap(int(11), str(postId), int(12), str(sent))))
        assertEquals(postId.lowercase(), read.postId())
        assertEquals(sent, read.sentHash())
        // Wrong type or length: none.
        val wrong = LxmfFields.read(fixmap(d1, fixmap(int(11), bin(postId.toByteArray()), int(12), str(sent.drop(2)))))
        assertNull(wrong.postId())
        assertNull(wrong.sentHash())
        assertNull(LxmfFields.read(fixmap(d1, fixmap(int(11), str("not hex at all, thirty-two chars")))).postId())
    }
}
