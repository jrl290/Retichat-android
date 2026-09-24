package com.newendian.retichat.service

import com.newendian.retichat.service.DistroCodec.SentCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFed SPEC §17.11 sent-message sync: the pure send/receive decisions. */
class DistroSentCopyTest {
    private val distro = "d".repeat(32)
    private val device = "a".repeat(32)
    private val sibling = "b".repeat(32)
    private val recipient = "c".repeat(32)

    @Test
    fun isHex32AcceptsOnlyLowercase32Hex() {
        assertTrue(DistroCodec.isHex32(recipient))
        assertTrue(DistroCodec.isHex32("0123456789abcdef0123456789abcdef"))
        assertFalse(DistroCodec.isHex32(null))
        assertFalse(DistroCodec.isHex32(""))
        assertFalse(DistroCodec.isHex32(recipient.dropLast(1)))
        assertFalse(DistroCodec.isHex32(recipient + "c"))
        assertFalse(DistroCodec.isHex32(recipient.uppercase()))
        assertFalse(DistroCodec.isHex32("g" + recipient.drop(1)))
    }

    @Test
    fun copySentOnlyForMessagesSentAsTheDistroToSomeoneElse() {
        assertTrue(DistroCodec.shouldSendSentCopy(distro, distro, recipient))
        // Sent as the device (no distro, or distro loaded after the send).
        assertFalse(DistroCodec.shouldSendSentCopy(device, distro, recipient))
        assertFalse(DistroCodec.shouldSendSentCopy(device, null, recipient))
        // A message to the distro itself gets no copy.
        assertFalse(DistroCodec.shouldSendSentCopy(distro, distro, distro))
        assertFalse(DistroCodec.shouldSendSentCopy("", "", recipient))
    }

    @Test
    fun unmarkedMessageKeepsTodaysBehaviour() {
        assertEquals(SentCopy.NotACopy, DistroCodec.classifySentCopy(distro, distro, device, null, null))
        assertEquals(SentCopy.NotACopy, DistroCodec.classifySentCopy(recipient, distro, device, null, null))
    }

    @Test
    fun siblingCopyIsStoredForItsRecipient() {
        assertEquals(
            SentCopy.Store(recipient),
            DistroCodec.classifySentCopy(distro, distro, device, recipient, sibling),
        )
        // 0xFD absent: lxmf_rust reports an empty sent_by; still a sibling's copy.
        assertEquals(
            SentCopy.Store(recipient),
            DistroCodec.classifySentCopy(distro, distro, device, recipient, ""),
        )
    }

    @Test
    fun ownEchoIsDroppedEvenWithABadRecipient() {
        assertEquals(SentCopy.OwnEcho, DistroCodec.classifySentCopy(distro, distro, device, recipient, device))
        assertEquals(SentCopy.OwnEcho, DistroCodec.classifySentCopy(distro, distro, device, null, device))
    }

    @Test
    fun emptySentByIsNeverOurEchoWhenOwnAddressUnknown() {
        assertEquals(
            SentCopy.Store(recipient),
            DistroCodec.classifySentCopy(distro, distro, "", recipient, ""),
        )
    }

    @Test
    fun markerFromAnotherSourceIsForeign() {
        assertEquals(SentCopy.Foreign, DistroCodec.classifySentCopy(sibling, distro, device, recipient, sibling))
        assertEquals(SentCopy.Foreign, DistroCodec.classifySentCopy(distro, null, device, recipient, sibling))
    }

    @Test
    fun badOrSelfRecipientIsMalformed() {
        // lxmf_rust leaves sent_to null when 0xFC is not 32 hex.
        assertEquals(SentCopy.Malformed(null), DistroCodec.classifySentCopy(distro, distro, device, null, sibling))
        assertEquals(
            SentCopy.Malformed("xyz"),
            DistroCodec.classifySentCopy(distro, distro, device, "xyz", sibling),
        )
        assertEquals(
            SentCopy.Malformed(distro),
            DistroCodec.classifySentCopy(distro, distro, device, distro, sibling),
        )
    }

    @Test
    fun copyIdMatchesTheFanOutIdSoStreamAndPullDedupe() {
        val a = DistroCodec.messageId(distro, 1_700_000_000.25, "hi")
        assertEquals(a, DistroCodec.messageId(distro, 1_700_000_000.25, "hi"))
        assertFalse(a == DistroCodec.messageId(distro, 1_700_000_000.5, "hi"))
    }
}
