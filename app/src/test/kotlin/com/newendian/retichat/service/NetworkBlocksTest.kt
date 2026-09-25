package com.newendian.retichat.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NetworkBlocksTest {
    @Test
    fun onlyABlockThatLiftsIsAnEvent() {
        val blocks = NetworkBlocks()
        assertFalse("unblocked right after onAvailable: no event", blocks.update("wifi", false))
        assertFalse("blocked: no event", blocks.update("wifi", true))
        assertFalse(blocks.update("wifi", true))
        assertTrue("the block lifted", blocks.update("wifi", false))
        assertFalse("once", blocks.update("wifi", false))
    }

    @Test
    fun blocksArePerNetworkAndForgottenWithIt() {
        val blocks = NetworkBlocks()
        blocks.update("wifi", true)
        assertFalse("another network was never blocked", blocks.update("cell", false))
        blocks.forget("wifi")
        assertFalse("a lost network's block is gone", blocks.update("wifi", false))
    }

    /** A network that is up but not yet the default cannot carry the stack's sockets. */
    @Test
    fun theListenersFollowTheDefaultNetwork() {
        val monitor = File("src/main/kotlin/com/newendian/retichat/service/NetworkMonitor.kt").readText()
        assertTrue(monitor.contains("cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {"))
        assertFalse(monitor.contains("registerNetworkCallback("))
    }

    /** The wiring needs the Android runtime, so it is checked in the source. */
    @Test
    fun aLiftedBlockWakesTheInterfacesAndLinks() {
        val monitor = File("src/main/kotlin/com/newendian/retichat/service/NetworkMonitor.kt").readText()
        val blocked = monitor.substringAfter("override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {")
            .substringBefore("override fun onLost(")
        assertTrue(blocked.contains("if (blocks.update(network, blocked))"))
        assertTrue(blocked.contains("onAvailableListeners.forEach { it() }"))
        val csm = File("src/main/kotlin/com/newendian/retichat/service/ConnectionStateManager.kt").readText()
        val listener = csm.substringAfter("private val networkAvailableListener: () -> Unit = {")
            .substringBefore("/** Tear down")
        val nudge = listener.indexOf("RetichatBridge.nudgeReconnect()")
        assertTrue("the TCP interfaces are woken, before the links", nudge >= 0 && nudge < listener.indexOf("appLinkNetworkChanged"))
    }
}
