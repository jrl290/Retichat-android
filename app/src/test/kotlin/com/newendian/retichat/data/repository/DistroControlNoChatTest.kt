package com.newendian.retichat.data.repository

import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.data.repository.DeliveryPolicy.DirectRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Distro control message (0xFB `rfed.distro.*`: a sent copy, a transfer, a
 * channel join or leave, a group sync, or a kind a later build adds) is never
 * a message to show, as the web (Retichat-js 1ac43ff) and iOS treat it:
 *
 * - On the device's own address, one that is not a transfer and has no group
 *   id is dropped with a log line (DeliveryPolicy.directRoute). A group id
 *   still makes it the group's, and a transfer is still offered.
 * - On the distro unwrap (RfedDistroClient.handleBlob), one no branch acts on
 *   is dropped with a log line.
 *
 * Until 2026-10-24 both went on as a DM: a sibling on another build, or a
 * stranger, could put an empty message in a chat.
 */
class DistroControlNoChatTest {
    private val id = "9f8e7d6c5b4a39281706f5e4d3c2b1a0"
    private val transferKey = "ab".repeat(16)

    private fun route(raw: ByteArray) = DeliveryPolicy.directRoute(LxmfFields.decode(raw))

    @Test
    fun aChannelSyncOrAnUnknownDistroKindOnTheDeviceAddressIsDropped() {
        val join = arr(str("join"), str("news"), int(1_700_000_000_000))
        assertEquals(DirectRoute.DistroControl(LxmfFields.DISTRO_CHANNEL_TYPE),
            route(map(key(0xFB) to str("rfed.distro.channel"), key(0xFC) to join)))
        assertEquals("a channel sync without its 0xFC", DirectRoute.DistroControl(LxmfFields.DISTRO_CHANNEL_TYPE),
            route(map(key(0xFB) to str("rfed.distro.channel"))))
        assertEquals(DirectRoute.DistroControl(LxmfFields.DISTRO_GROUP_TYPE),
            route(map(key(0xFB) to str("rfed.distro.group"))))
        assertEquals("a kind a later build adds", DirectRoute.DistroControl("rfed.distro.newer"),
            route(map(key(0xFB) to str("rfed.distro.newer"), key(0xFC) to str(transferKey))))
        assertEquals("the type as bin", DirectRoute.DistroControl("rfed.distro.newer"),
            route(map(key(0xFB) to bin("rfed.distro.newer"))))
        // With text, a title or a name beside it, it is still no DM.
        assertEquals(DirectRoute.DistroControl("rfed.distro.newer"),
            route(map(key(0xFB) to str("rfed.distro.newer"), key(0xD1) to str("Pat"))))
    }

    @Test
    fun aGroupMessageBesideADistroMarkerIsStillTheGroups() {
        for (type in listOf("rfed.distro.channel", "rfed.distro.group", "rfed.distro.newer")) {
            assertEquals(type, DirectRoute.Group(id), route(map(key(0xFB) to str(type), key(0xA0) to str(id))))
        }
        // A malformed group id beside one is still refused first.
        assertEquals(DirectRoute.BadGroupId, route(map(key(0xFB) to str("rfed.distro.newer"), key(0xA0) to str("xyz"))))
    }

    @Test
    fun aTransferIsStillOfferedAndASentCopyStillDropped() {
        assertEquals(DirectRoute.Transfer(transferKey), route(map(key(0xFB) to str("rfed.distro.transfer"), key(0xFC) to str(transferKey))))
        // A transfer marker without a usable key stays a DM under the filter, as on iOS and the web.
        assertEquals(DirectRoute.Direct, route(map(key(0xFB) to str("rfed.distro.transfer"))))
        assertEquals(DirectRoute.SentCopy, route(map(key(0xFB) to str("rfed.distro.sent"), key(0xFC) to str(transferKey))))
    }

    @Test
    fun onlyTheDistroPrefixIsAControlMessage() {
        for (type in listOf("rfed.distro", "rfed.distrox", "Rfed.distro.newer", "x.rfed.distro.newer", "")) {
            assertEquals(type, DirectRoute.Direct, route(map(key(0xFB) to str(type))))
            assertNull(type, DeliveryPolicy.distroControlType(LxmfFields.decode(map(key(0xFB) to str(type)))))
        }
        assertEquals(DirectRoute.Direct, route(map(key(0xFB) to int(5))))
        assertEquals(DirectRoute.Direct, route(map()))
        assertNull(DeliveryPolicy.distroControlType(LxmfFields.decode(null)))
        assertEquals("rfed.distro.sent", DeliveryPolicy.distroControlType(LxmfFields.decode(map(key(0xFB) to str("rfed.distro.sent")))))
    }

    @Test
    fun theDirectPathDropsItWithALineBeforeAnythingIsStored() {
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        val start = repo.indexOf("fun onMessageReceived(")
        val received = repo.substring(start, repo.indexOf("\n    fun onDistroMessageReceived(", start))
        val drop = received.indexOf("is DeliveryPolicy.DirectRoute.DistroControl -> {")
        assertTrue(drop > received.indexOf("val route = DeliveryPolicy.directRoute(received)"))
        val block = received.substring(drop, received.indexOf("else -> Unit", drop))
        assertTrue(block.contains("Log.i(TAG, \"onMessageReceived: DROPPED Distro control message \${route.type} from"))
        assertTrue(block.contains("                return\n            }"))
        for (later in listOf("allowlisted(srcHex)", "ensureContact(srcHex)", "handleDirectMessage(")) {
            assertTrue(later, drop < received.indexOf(later))
        }
    }

    @Test
    fun theDistroUnwrapDropsAKindNoBranchTookBeforeItBecomesADm() {
        val client = File("src/main/kotlin/com/newendian/retichat/service/RfedDistroClient.kt").readText()
        // handleBlob opens the blob and hands it to handleUnwrapped (RFed SPEC §17.15: a message received direct too).
        val start = client.indexOf("suspend fun handleUnwrapped(")
        val blob = client.substring(start, client.indexOf("\n    /**", start))
        val drop = blob.indexOf("DeliveryPolicy.distroControlType(LxmfFields.decode(fieldsRaw))?.let { type ->")
        assertTrue(drop >= 0)
        // After every branch that acts on a Distro control message …
        for (earlier in listOf(
            "handleChannelSync(context, srcHex, timestamp, channelSync,",
            "handleGroupSync(context, blob, groupKeys,",
            "offerTransfer(srcHex, transferKey)",
            "app.repository.onDistroSentCopy(",
        )) {
            assertTrue(earlier, blob.indexOf(earlier) in 0 until drop)
        }
        // … and before the message is filed as a DM.
        assertTrue(drop < blob.indexOf("app.repository.onDistroMessageReceived("))
        val block = blob.substring(drop, blob.indexOf("val srcHash", drop))
        assertTrue(block.contains("Log.i(TAG, \"Distro control message \$type from"))
        assertTrue(block.contains("return false"))
    }

    // ---- msgpack bytes ----

    private fun str(s: String): ByteArray {
        val b = s.toByteArray()
        return (if (b.size < 32) byteArrayOf((0xA0 or b.size).toByte()) else byteArrayOf(0xD9.toByte(), b.size.toByte())) + b
    }
    private fun bin(s: String): ByteArray = s.toByteArray().let { byteArrayOf(0xC4.toByte(), it.size.toByte()) + it }
    private fun int(n: Long): ByteArray =
        if (n in 0..0x7F) byteArrayOf(n.toByte())
        else byteArrayOf(0xCF.toByte()) + ByteArray(8) { i -> (n ushr (56 - 8 * i)).toByte() }
    private fun arr(vararg items: ByteArray): ByteArray = items.fold(byteArrayOf((0x90 or items.size).toByte())) { acc, x -> acc + x }
    private fun key(k: Int): ByteArray = if (k < 0x80) byteArrayOf(k.toByte()) else byteArrayOf(0xCC.toByte(), k.toByte())
    private fun map(vararg entries: Pair<ByteArray, ByteArray>): ByteArray =
        entries.fold(byteArrayOf((0x80 or entries.size).toByte())) { acc, (k, value) -> acc + k + value }
}
