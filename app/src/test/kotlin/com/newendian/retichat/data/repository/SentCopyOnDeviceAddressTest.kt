package com.newendian.retichat.data.repository

import com.newendian.retichat.bridge.LxmfFields
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RFed SPEC §17.11: a distro sent copy is addressed to the distro and reaches
 * a device only through the distro fan-out. One that arrives on the device's
 * own address is dropped first, whoever sent it and filter on or off, as iOS
 * (handleIncomingMessage) and the web (PrivacyFilter.acceptsMessage) drop it.
 * Until 2026-10-05 Android only checked the transfer marker there, so with
 * the filter off (or the distro allowlisted) the user's own message was
 * stored as an incoming DM from the distro.
 */
class SentCopyOnDeviceAddressTest {
    /** msgpack {0xFB: [type], 0xFC: 32 hex} with the type as str or bin. */
    private fun fields(type: String, asBin: Boolean = false): LxmfFields {
        val t = type.toByteArray()
        val typeBytes = if (asBin) byteArrayOf(0xc4.toByte(), t.size.toByte()) + t
                        else byteArrayOf((0xa0 or t.size).toByte()) + t
        val to = "0123456789abcdef0123456789abcdef".toByteArray()
        val raw = byteArrayOf(0x82.toByte(), 0xcc.toByte(), 0xfb.toByte()) + typeBytes +
            byteArrayOf(0xcc.toByte(), 0xfc.toByte(), 0xd9.toByte(), to.size.toByte()) + to
        return LxmfFields.decode(raw)
    }

    @Test
    fun aSentCopyMarkerIsRecognisedOnTheDeviceAddress() {
        assertTrue(DeliveryPolicy.isDistroSentCopy(fields(LxmfFields.DISTRO_SENT_TYPE)))
        assertTrue(DeliveryPolicy.isDistroSentCopy(fields(LxmfFields.DISTRO_SENT_TYPE, asBin = true)))
        assertFalse(DeliveryPolicy.isDistroSentCopy(fields(LxmfFields.DISTRO_TRANSFER_TYPE)))
        assertFalse(DeliveryPolicy.isDistroSentCopy(LxmfFields.decode(byteArrayOf(0x80.toByte()))))
    }

    @Test
    fun itIsDroppedBeforeThePrivacyFilterAndBeforeAnyWrite() {
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        val start = repo.indexOf("fun onMessageReceived(")
        val received = repo.substring(start, repo.indexOf("\n    fun onDistroMessageReceived(", start))
        // DeliveryPolicy.directRoute's first case (DirectRouteTest), acted on before anything else.
        val drop = received.indexOf("DeliveryPolicy.DirectRoute.SentCopy -> {")
        assertTrue(drop > received.indexOf("val route = DeliveryPolicy.directRoute(fields)"))
        // Its block returns before the next case.
        val block = received.substring(drop, received.indexOf("DeliveryPolicy.DirectRoute.BadGroupId -> {", drop))
        assertTrue(block.contains("                return\n            }"))
        // Before the filter and the group rule, and before any write.
        for (later in listOf("allowlisted(srcHex)", "DeliveryPolicy.groupMessage(", "ensureContact(srcHex)", "handleDirectMessage(")) {
            assertTrue(later, drop < received.indexOf(later))
        }
    }
}
