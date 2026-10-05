package com.newendian.retichat.data.repository

import com.newendian.retichat.bridge.LxmfFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RFed SPEC §17.9 on the device address: a distro identity transfer is
 * offered only when the privacy filter lets its sender through, as the web
 * offers it (James, 2026-10-05; Retichat-js PrivacyFilter.acceptsMessage).
 * Until 2026-10-05 Android offered one from anyone, before the filter, so
 * any stranger could put the Import Distro Identity prompt in front of the
 * user.
 */
class DistroTransferFilterTest {
    private fun str(s: String): ByteArray {
        val b = s.toByteArray()
        return if (b.size < 32) byteArrayOf((0xa0 or b.size).toByte()) + b
               else byteArrayOf(0xd9.toByte(), b.size.toByte()) + b
    }

    /** msgpack {0xFB: [type], 0xFC: [data]?}. */
    private fun fields(type: String, data: String?): LxmfFields {
        val entries = (byteArrayOf(0xcc.toByte(), 0xfb.toByte()) + str(type)) +
            (if (data == null) ByteArray(0) else byteArrayOf(0xcc.toByte(), 0xfc.toByte()) + str(data))
        return LxmfFields.decode(byteArrayOf((0x80 or if (data == null) 1 else 2).toByte()) + entries)
    }

    private val key = "ab".repeat(32)

    @Test
    fun aTransferOnTheDeviceAddressCarriesItsKey() {
        assertEquals(key, DeliveryPolicy.distroTransferKey(fields(LxmfFields.DISTRO_TRANSFER_TYPE, key)))
        assertNull(DeliveryPolicy.distroTransferKey(fields(LxmfFields.DISTRO_TRANSFER_TYPE, null)))
        assertNull(DeliveryPolicy.distroTransferKey(fields(LxmfFields.DISTRO_SENT_TYPE, key)))
        assertNull(DeliveryPolicy.distroTransferKey(LxmfFields.decode(byteArrayOf(0x80.toByte()))))
    }

    @Test
    fun itIsOfferedOnlyThroughThePrivacyFilter() {
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        val start = repo.indexOf("fun onMessageReceived(")
        val received = repo.substring(start, repo.indexOf("\n    fun onDistroMessageReceived(", start))
        // One offer, inside the filter's yes.
        assertEquals(1, Regex("RfedDistroClient\\.offerTransfer\\(").findAll(received).count())
        val gate = received.indexOf("if (transferKey != null) {\n                if (allowlisted(srcHex)) {\n" +
            "                    RfedDistroClient.offerTransfer(srcHex, transferKey)")
        assertTrue("the offer is gated by the privacy filter", gate >= 0)
        // Offered or dropped, it is never processed as a message.
        val block = received.substring(gate, received.indexOf("// The privacy filter and the group rule", gate))
        assertTrue(block.contains("return@launch"))
        // Before any write or group handling.
        for (later in listOf("DeliveryPolicy.groupMessage(", "ensureContact(srcHex)", "handleDirectMessage(")) {
            assertTrue(later, gate < received.indexOf(later))
        }
    }
}
