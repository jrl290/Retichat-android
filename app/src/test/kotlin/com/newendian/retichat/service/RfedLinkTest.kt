package com.newendian.retichat.service

import com.newendian.retichat.data.db.entity.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** rfed.link (RFed-spec/Link.md): the publish and pull answers, and how the link is set up and bound. */
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
        assertEquals("no answer", RfedChannelClient.publishRefusal(null))
        assertEquals("an unreadable answer c3", RfedChannelClient.publishRefusal(byteArrayOf(0xc3.toByte())))
    }

    @Test
    fun aPublishSendsItsPayloadAsAMsgpackBin() {
        // A request's data is a msgpack value: Reticulum-rust hands a bin's
        // bytes to rfed and re-encodes anything else (link.rs). Until
        // 2026-10-18 the post went bare, so its bytes were read as msgpack and
        // rewritten: a channel hash starting 0x9a is a 10-element array, its
        // 0xd2 (int32) came back as 0xce (uint32), and rfed fanned the post
        // out to a channel nobody holds (staging, iOS, 2026-10-18 16:11).
        val send = src("service/RfedChannelClient.kt")
        assertTrue(send.contains("RfedLink.request(app, rfedNode, \"/channel/publish\", msgpackBin(finalPayload))"))
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

    @Test
    fun aSetupThatThrowsIsNotMarkedAndIsTriedAgain() {
        // 10-19 00:26:44: a stale libretichat_jni.so had no
        // nativeAppLinkSetOutboundSetup. The UnsatisfiedLinkError left the node
        // marked, so every later ensure() skipped the setup and opened
        // rfed.link with no LINKIDENTIFY and no /delivery handlers.
        val marks = mutableMapOf<String, Long>()
        val missing = UnsatisfiedLinkError("nativeAppLinkSetOutboundSetup")
        val failed = RfedLink.setUpOnce(marks, "node", 7L) { throw missing }
        assertTrue(failed is RfedLink.Setup.Failed)
        assertSame(missing, (failed as RfedLink.Setup.Failed).error)
        assertTrue(marks.isEmpty())

        // A refusal is not marked either.
        assertEquals(RfedLink.Setup.Failed(null), RfedLink.setUpOnce(marks, "node", 7L) { false })
        assertTrue(marks.isEmpty())

        var calls = 0
        assertSame(RfedLink.Setup.Fresh, RfedLink.setUpOnce(marks, "node", 7L) { calls++; true })
        assertSame(RfedLink.Setup.Held, RfedLink.setUpOnce(marks, "node", 7L) { calls++; true })
        assertEquals(1, calls)
        assertEquals(mapOf("node" to 7L), marks)
        // A new stack's identity sets the link up again.
        assertSame(RfedLink.Setup.Fresh, RfedLink.setUpOnce(marks, "node", 8L) { calls++; true })
        assertEquals(2, calls)

        // ensure() opens nothing after a failed setup.
        val ensure = src("service/RfedLink.kt").substringAfter("suspend fun ensure(").substringBefore("    /**")
        val failure = ensure.indexOf("is Setup.Failed")
        assertTrue(failure >= 0 && failure < ensure.indexOf("ConnectionStateManager.primeAppLink("))
        assertTrue(ensure.contains("rfed.link setup failed"))
    }

    @Test
    fun aPullRefusalIsNamed() {
        // rfed answers an error as a msgpack integer (RFed-rust
        // destinations.rs channel.pull, lxmf_propagation::encode_error): 0xF0
        // is uint8 0xcc 0xf0. Until 10-19 it was logged as "malformed response (2 bytes)".
        fun refusal(vararg b: Int) = RfedLink.pullRefusal(ByteArray(b.size) { b[it].toByte() })
        assertEquals("link not identified (0xF0)", refusal(0xcc, 0xf0))
        assertEquals("no access (0xF1)", refusal(0xcc, 0xf1))
        assertEquals("invalid key (0xF3)", refusal(0xcc, 0xf3))
        assertEquals("invalid request data (0xF4)", refusal(0xcc, 0xf4))
        assertEquals("invalid stamp (0xF5)", refusal(0xcc, 0xf5))
        assertEquals("throttled (0xF6)", refusal(0xcc, 0xf6))
        assertEquals("not found (0xFD)", refusal(0xcc, 0xfd))
        assertEquals("timed out (0xFE)", refusal(0xcc, 0xfe))
        assertEquals("an unknown error (0xE0)", refusal(0xcc, 0xe0))
        assertEquals("an unknown error (0x05)", refusal(0x05))
        // A page ([[], false]) and anything else is not a refusal.
        assertNull(refusal(0x92, 0x90, 0xc2))
        assertNull(refusal(0xcc))
        assertNull(refusal())

        val pull = src("service/RfedChannelClient.kt").substringAfter("suspend fun pullDeferred(").substringBefore("    // ──")
        val named = pull.indexOf("RfedLink.pullRefusal(resp)")
        assertTrue(named >= 0 && named < pull.indexOf("decodePullResponse(resp)"))
        assertTrue(pull.contains("\"PULL: rfed refused the pull: \$refusal\""))
    }

    @Test
    fun aDistroPullRefusalIsNamed() {
        // /distro/pull answers an unidentified link the same way (RFed-rust
        // destinations.rs delivery pull): until 10-19 the pull ended on
        // "malformed response (2 bytes)".
        val pull = src("service/RfedDistroClient.kt").substringAfter("suspend fun pullOutcome(").substringBefore("    /**")
        val named = pull.indexOf("RfedLink.pullRefusal(resp)")
        assertTrue(named >= 0 && named < pull.indexOf("DistroCodec.decodePullResponse(resp)"))
        assertTrue(pull.contains("\"rfed refused the pull: \$refusal\""))
    }

    @Test
    fun anActiveEdgePullsWhatTheNodeDeferred() {
        // 10-19: posts deferred while rfed.link was down came ~30 min late, on
        // rfed's re-send. A fresh link now collects them itself, after binding
        // the stream, for the channels on that node only.
        val channels = listOf(
            ChannelEntity(id = "aa", channelName = "a", rfedNodeIdentityHashHex = "Node1"),
            ChannelEntity(id = "AA", channelName = "a again", rfedNodeIdentityHashHex = "node1"),
            ChannelEntity(id = "bb", channelName = "b", rfedNodeIdentityHashHex = "node1"),
            ChannelEntity(id = "cc", channelName = "c", rfedNodeIdentityHashHex = "node2"),
        )
        assertEquals(listOf("aa", "bb"), RfedChannelClient.channelsOnNode(channels, "node1").map { it.id })
        assertEquals(listOf("cc"), RfedChannelClient.channelsOnNode(channels, "NODE2").map { it.id })

        val link = src("service/RfedLink.kt")
        val bind = link.substringAfter("private suspend fun bind(").substringBefore("    /**")
        val stream = bind.indexOf("bindPushes(app, node)")
        val pull = bind.indexOf("app.rfedChannelClient.pullDeferredForNode(node)")
        assertTrue(stream >= 0 && pull > stream)
        val pushes = link.substringAfter("private suspend fun bindPushes(").substringBefore("    /**")
        assertTrue(pushes.contains("app.rfedChannelClient.sendChannelStreamConfigForNode(node)"))
        assertTrue(pushes.contains("PropagationStream.onLinkActive(app, node)"))
    }

    @Test
    fun overASlowRouteTheLinkOpensForRequestsOnly() {
        // James, 2026-11-05: "for LoRa/Slow connections we can't maintain a
        // link to RFed". Measured slow, with this device's own wake address
        // hosted, RFed wakes the device instead of pushing: no push binding,
        // nothing opens the link to be pushed to, and it closes once no
        // request waits its turn.
        val link = src("service/RfedLink.kt")
        val holds = link.substringAfter("fun holdsNoLink(").substringBefore("    /**")
        assertTrue(holds.contains("RetichatBridge.appLinkRouteSpeed(dest) == RetichatBridge.RouteSpeed.SLOW"))
        assertTrue(holds.contains("StackRuntime.meshRelayHex != null"))
        val bind = link.substringAfter("private suspend fun bind(").substringBefore("    /**")
        assertTrue(bind.contains("if (!holdsNoLink(node)) bindPushes(app, node)"))
        val request = link.substringAfter("suspend fun request(").substringBefore("    /**")
        assertTrue(request.indexOf("closeIfIdle(nodeHex, dest)") > request.indexOf("ConnectionStateManager.appLinkSend("))
        val close = link.substringAfter("private fun closeIfIdle(").substringBefore("    /**")
        assertTrue(close.contains("ConnectionStateManager.slowRouteIdle(dest)"))
        assertTrue(close.contains("ConnectionStateManager.closeAppLink(dest)"))

        // Staging, 2026-11-05: closed after each request, a run of channel
        // pulls opened a link per pull over LoRa. A run holds it to its end.
        assertTrue(close.contains("runs.containsKey(nodeHex.lowercase())"))
        val holding = link.substringAfter("suspend fun <T> holding(nodeHexes: Collection<String>").substringBefore("    suspend fun")
        assertTrue(holding.substringAfter("} finally {").contains("closeIfIdle(node, it)"))
        val client = src("service/RfedChannelClient.kt")
        assertTrue(client.contains("RfedLink.holding(nodeIdentityHashHex) { for (channel in channels) {"))
        assertTrue(client.contains("RfedLink.holding(channels.map { it.rfedNodeIdentityHashHex }) { for (channel in channels) {"))
        assertTrue(client.contains("RfedLink.holding(channelDao.activeChannels().map { it.rfedNodeIdentityHashHex }) { for (row in rows) {"))
        assertTrue(src("service/RfedDistroClient.kt").contains("RfedLink.holding(node) { DistroPullRounds.drain("))
        val held = src("service/HeldLinkRegistrations.kt")
        assertTrue(held.contains("ops.inOneRun { for ((key, payload) in owed.entries.toList()) {"))
        assertTrue(held.contains("override suspend fun inOneRun(block: suspend () -> Unit) = RfedLink.holding(nodeHex, block)"))

        // A request is counted from before its turn until it ends.
        val csm = src("service/ConnectionStateManager.kt")
        val send = csm.substringAfter("private suspend fun slowRouteSend(").substringBefore("    /**")
        val counted = send.indexOf("slowRouteWaiting.merge(key, 1, Int::plus)")
        assertTrue(counted >= 0 && counted < send.indexOf("slowRouteTurns.getOrPut(key)"))
        assertTrue(send.substringAfter("} finally {").contains("slowRouteWaiting.computeIfPresent(key)"))

        // The stream opens don't open the link on such a route.
        val stream = src("service/PropagationStream.kt").substringAfter("suspend fun start(").substringBefore("    /**")
        assertTrue(stream.indexOf("if (RfedLink.holdsNoLink(nodeHex)) return") in 0 until stream.indexOf("RfedLink.ensure("))
        val channels = src("service/RfedChannelClient.kt")
        val reconfigure = channels.substringBefore("val streamDest = RfedLink.ensure(app, nodeIdentityHashHex)").takeLast(400)
        assertTrue(reconfigure.contains("if (RfedLink.holdsNoLink(nodeIdentityHashHex)) {"))

        // The casual poll waits six hours there; wakes sync at once.
        val app = File("src/main/kotlin/com/newendian/retichat/RetichatApp.kt").readText()
        assertTrue(app.contains("private const val WOKEN_POLL_INTERVAL_MS = 6 * 60 * 60 * 1000L"))
        assertTrue(app.contains("if (RfedLink.holdsNoLink(node)) WOKEN_POLL_INTERVAL_MS else 0L"))
    }
}
