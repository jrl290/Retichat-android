package com.newendian.retichat.service

import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.db.entity.ChannelEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ConnectionStateManagerTest {
    private fun sourceFile(relativePath: String): String =
        File(relativePath).readText()

    @Test
    fun persistentRequestAlwaysUpgradesToHeldLink() {
        assertEquals(
            ConnectionStateManager.RequestOpenMode.PERSISTENT,
            ConnectionStateManager.requestOpenMode(
                usePersistentLink = true,
                currentStatus = RetichatBridge.AppLinkStatus.ACTIVE,
            ),
        )
    }

    @Test
    fun nonPersistentActiveRequestDoesNotReopen() {
        assertEquals(
            ConnectionStateManager.RequestOpenMode.NONE,
            ConnectionStateManager.requestOpenMode(
                usePersistentLink = false,
                currentStatus = RetichatBridge.AppLinkStatus.ACTIVE,
            ),
        )
    }

    @Test
    fun nonPersistentInactiveRequestOpensEphemeralLink() {
        assertEquals(
            ConnectionStateManager.RequestOpenMode.EPHEMERAL,
            ConnectionStateManager.requestOpenMode(
                usePersistentLink = false,
                currentStatus = RetichatBridge.AppLinkStatus.PATH_REQUESTED,
            ),
        )
    }

    @Test
    fun dataSendWaitsForActiveBeforeSendAsync() {
        val connectionState = sourceFile("src/main/kotlin/com/newendian/retichat/service/ConnectionStateManager.kt")

        assertTrue(connectionState.contains("DATA sends must also wait for the AppLink readiness edge"))
        assertTrue(connectionState.contains("if (!awaitAppLinkActive(destHash, timeoutMs = 5_000L)) return@withContext false"))
    }

    @Test
    fun channelPullDoesNotPreferPersistentAppLinks() {
        assertFalse(ConnectionStateManager.prefersPersistentAppLink("rfed", "channel.pull"))
    }

    @Test
    fun liveStreamFiltersIncludeOnlyOpenedChannelsForNode() {
        val filters = RfedChannelClient.liveStreamFilterIds(
            channels = listOf(
                ChannelEntity(id = "aa", channelName = "a", rfedNodeIdentityHashHex = "node1"),
                ChannelEntity(id = "bb", channelName = "b", rfedNodeIdentityHashHex = "node1"),
                ChannelEntity(id = "cc", channelName = "c", rfedNodeIdentityHashHex = "node2"),
            ),
            nodeIdentityHashHex = "node1",
            openedChannelIds = setOf("bb", "cc"),
        )
        assertEquals(listOf("bb"), filters)
    }

    @Test
    fun pullStateIsScopedPerChannel() {
        val first = ChannelEntity(id = "aa", channelName = "a", rfedNodeIdentityHashHex = "node1")
        val second = ChannelEntity(id = "bb", channelName = "b", rfedNodeIdentityHashHex = "node1")

        assertEquals("aa", RfedChannelClient.pullStateKey(first))
        assertEquals("bb", RfedChannelClient.pullStateKey(second))
        assertEquals(listOf(first, second), RfedChannelClient.channelsForWakePull(listOf(first, second)))
    }

    @Test
    fun androidChannelPullSourceUsesSplitDestinationAndForegroundHooks() {
        val channelClient = sourceFile("src/main/kotlin/com/newendian/retichat/service/RfedChannelClient.kt")
        val conversation = sourceFile("src/main/kotlin/com/newendian/retichat/ui/conversation/ConversationScreen.kt")
        val app = sourceFile("src/main/kotlin/com/newendian/retichat/RetichatApp.kt")
        val stackRuntime = sourceFile("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt")

        assertTrue(channelClient.contains("listOf(\"channel\", \"pull\")"))
        assertTrue(channelClient.contains("\"/rfed/pull\", msgpackBin(channelHashBytes)"))
        assertTrue(channelClient.contains("rfed.channel.pull request failed after AppLinks readiness gate"))

        assertTrue(conversation.contains("retainRfedLinkMonitor()"))
        assertTrue(conversation.contains("rfedLinkGeneration.collectAsState()"))
        assertTrue(conversation.contains("LifecycleEventObserver"))
        assertTrue(conversation.contains("pullDeferred(ch)"))

        assertTrue(app.contains("triggerForegroundPropagationPull()"))
        assertTrue(app.contains("onStackReadyWhileForeground()"))
        assertTrue(app.contains("PropagationSync.runOnce(this@RetichatApp)"))
        assertTrue(stackRuntime.contains("RetichatApp)?.onStackReadyWhileForeground()"))
    }

    @Test
    fun wakePropagationPullForcesFreshPropagationLinkReadyEdge() {
        val wakeWorker = sourceFile("src/main/kotlin/com/newendian/retichat/service/WakeWorker.kt")
        val propagation = sourceFile("src/main/kotlin/com/newendian/retichat/service/PropagationSync.kt")
        val connectionState = sourceFile("src/main/kotlin/com/newendian/retichat/service/ConnectionStateManager.kt")

        assertTrue(wakeWorker.contains("PropagationSync.runOnce(applicationContext, requireFreshTransport = true)"))
        assertTrue(propagation.contains("if (requireFreshTransport)"))
        assertTrue(propagation.contains("ConnectionStateManager.reopenPersistentAppLinkAndAwaitActive("))
        assertTrue(propagation.contains("\"lxmf\",") && propagation.contains("\"propagation\","))
        assertTrue(connectionState.contains("suspend fun reopenPersistentAppLinkAndAwaitActive("))
        assertTrue(connectionState.contains("RetichatBridge.appLinkReopen(rh, destHash)"))
    }
}