package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** rfed.link (RFed-spec/Link.md): the publish answer, and the order the link is set up in. */
class RfedLinkTest {

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    @Test
    fun aPublishAnswerIsAcceptedOrGivesItsReason() {
        // [true, nil]
        assertNull(RfedChannelClient.publishRefusal(byteArrayOf(0x92.toByte(), 0xc3.toByte(), 0xc0.toByte())))
        // [false, "stamp"]
        val stamp = byteArrayOf(0x92.toByte(), 0xc2.toByte(), 0xa5.toByte()) + "stamp".toByteArray()
        assertEquals("stamp", RfedChannelClient.publishRefusal(stamp))
        // [false, str8 of 40 bytes]
        val long = "x".repeat(40)
        val str8 = byteArrayOf(0x92.toByte(), 0xc2.toByte(), 0xd9.toByte(), 40) + long.toByteArray()
        assertEquals(long, RfedChannelClient.publishRefusal(str8))
        // [false, nil], or a reason cut short
        assertEquals("a refusal", RfedChannelClient.publishRefusal(byteArrayOf(0x92.toByte(), 0xc2.toByte(), 0xc0.toByte())))
        assertEquals("a refusal", RfedChannelClient.publishRefusal(byteArrayOf(0x92.toByte(), 0xc2.toByte(), 0xa5.toByte(), 0x73)))
        assertEquals("nothing within 5 s", RfedChannelClient.publishRefusal(null))
        assertEquals("an unreadable answer c3", RfedChannelClient.publishRefusal(byteArrayOf(0xc3.toByte())))
    }

    @Test
    fun theLinkIsSetUpBeforeItIsOpenedAndRebindsOnEveryActiveEdge() {
        val link = src("service/RfedLink.kt")
        val ensure = link.substringAfter("suspend fun ensure(").substringBefore("    /**")
        val setup = ensure.indexOf("RetichatBridge.appLinkSetOutboundSetup(")
        assertTrue(setup >= 0 && setup < ensure.indexOf("ConnectionStateManager.primeAppLink(dest, \"rfed\", \"link\")"))
        assertTrue(ensure.contains("listOf(PUSH_DELIVERY, PUSH_LXMF, PUSH_NOTIFY)"))
        assertTrue(ensure.contains("if (status != RetichatBridge.AppLinkStatus.ACTIVE) return@setAppLinkStatusHandler\n                scope.launch { bind(app, node) }"))
        // One held link for every RFed operation: the only rfed aspect kept open.
        val csm = src("service/ConnectionStateManager.kt")
        assertEquals(
            listOf("rfed.link"),
            Regex("""appLinkRegisterReconnect\(routerHandle, "(rfed\.[a-z.]+)"\)""").findAll(csm).map { it.groupValues[1] }.toList(),
        )
    }
}
