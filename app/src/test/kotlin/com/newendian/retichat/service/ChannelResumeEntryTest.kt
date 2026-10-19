package com.newendian.retichat.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A channel screen brought back from the background runs its entry again, as
 * though opened from the chat list (James, 2026-10-19): stream armed, "Load
 * earlier" shown, and the pull sent once the rfed link is ACTIVE, now or at
 * its next edge. Compose isn't run in a JVM test, so the wiring is checked in
 * the source; the emulator run shows the pull.
 */
class ChannelResumeEntryTest {
    private val screen = File("src/main/kotlin/com/newendian/retichat/ui/conversation/ConversationScreen.kt").readText()
    private val channel = screen.substringAfter("private fun ChannelConversationContent(")

    @Test
    fun aResumeIsAnotherEntry() {
        val observer = channel.substringAfter("DisposableEffect(lifecycleOwner, channelId) {").substringBefore("onDispose")
        assertTrue("the first ON_RESUME is the entry itself", observer.indexOf("entered = true") in 0 until observer.indexOf("entries++"))
        assertTrue("a resume pulls whatever generation the link is on", observer.contains("lastAutoPullGeneration = null"))
        assertFalse("a resume no longer pulls at once, link up or not", observer.contains("pullDeferred"))
    }

    @Test
    fun theEntryEffectsRunAgainOnEachEntry() {
        val arm = channel.substringAfter("LaunchedEffect(channel?.id, entries) {").substringBefore("}")
        assertTrue(arm.contains("markChannelOpenedForStreaming(ch.id)") && arm.contains("resetCanPullMore(ch.id)"))
        val pull = channel.substringAfter("LaunchedEffect(channel?.id, rfedLinkGeneration, entries) {").substringBefore("DisposableEffect(lifecycleOwner")
        assertTrue("only on an ACTIVE link", pull.indexOf("AppLinkStatus.ACTIVE") in 0 until pull.indexOf("pullDeferred(ch)"))
        assertTrue("on the screen's scope, never cancelled by a key change", pull.contains("scope.launch { runCatching { app.rfedChannelClient.pullDeferred(ch) } }"))
    }
}
