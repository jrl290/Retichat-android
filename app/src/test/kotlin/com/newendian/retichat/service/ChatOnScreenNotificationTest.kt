package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat on screen posts no notification: its conversation open and the app
 * in the foreground (James, 2026-10-19: "make sure notifications are defeated
 * for messages on a Channel that is on screen"). Until then a channel never
 * marked itself open, so each post in the channel being read notified over
 * it; and the open-chat check sat in each DM and Group caller without the
 * foreground, so an open DM behind the home screen stayed silent. Now the
 * check is [MessageNotificationHelper.notify]'s, beside the mute, and every
 * chat screen marks itself open.
 */
class ChatOnScreenNotificationTest {
    private val main = File("src/main/kotlin/com/newendian/retichat")
    private fun src(path: String) = File(main, path).readText()
    private val helper = src("service/MessageNotificationHelper.kt")
    private val screen = src("ui/conversation/ConversationScreen.kt")

    /** Each of [parts] in [text], in this order. */
    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (part in parts) {
            val next = text.indexOf(part, at + 1)
            assertTrue("\"$part\" after the one before it", next > at)
            at = next
        }
    }

    @Test
    fun onScreenIsOpenAndInTheForeground() {
        assertTrue(chatOnScreen(true, "a704b2dcdb2f96faef2a08bfb90f84db", "a704b2dcdb2f96faef2a08bfb90f84db"))
        assertTrue("a channel's hash in either case", chatOnScreen(true, "A704B2DCDB2F96FAEF2A08BFB90F84DB", "a704b2dcdb2f96faef2a08bfb90f84db"))
        assertFalse("open behind the home screen", chatOnScreen(false, "a704b2dcdb2f96faef2a08bfb90f84db", "a704b2dcdb2f96faef2a08bfb90f84db"))
        assertFalse("no chat open", chatOnScreen(true, null, "a704b2dcdb2f96faef2a08bfb90f84db"))
        assertFalse("another chat open", chatOnScreen(true, "051bb0c8b0784aaa7e90f79d3a25ce8a", "a704b2dcdb2f96faef2a08bfb90f84db"))
    }

    @Test
    fun theHelperReturnsBeforeAnythingIsStackedOrPosted() {
        val start = helper.indexOf("    fun notify(")
        val notify = helper.substring(start, helper.indexOf("\n    fun clearChat(", start))
        inOrder(
            notify,
            "if (chatOnScreen(RetichatApp.isInForeground, RetichatApp.activeChatId, chatId)) {",
            "return",
            "history.add(chatId,",
            "nm.notify(chatId, notifId, notification)",
        )
    }

    @Test
    fun aChannelScreenMarksItselfOpenAsADmScreenDoes() {
        val channel = screen.substring(screen.indexOf("private fun ChannelConversationContent("))
        assertTrue(channel.contains("DisposableEffect(channelId) {\n        RetichatApp.activeChatId = channelId\n" +
            "        onDispose { if (RetichatApp.activeChatId == channelId) RetichatApp.activeChatId = null }"))
        val dm = screen.substring(screen.indexOf("private fun DmConversationContent("), screen.indexOf("private fun ChannelConversationContent("))
        assertTrue(dm.contains("RetichatApp.activeChatId = chatId"))
        assertTrue("a closing screen leaves a newer screen's mark", dm.contains("if (RetichatApp.activeChatId == chatId) RetichatApp.activeChatId = null"))
    }

    @Test
    fun onlyTheScreensAndTheHelperReadTheOpenChat() {
        val readers = main.walk()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("activeChatId") }
            .map { it.relativeTo(main).path }
            .sorted()
            .toList()
        assertEquals(listOf("RetichatApp.kt", "service/MessageNotificationHelper.kt", "ui/conversation/ConversationScreen.kt"), readers)
    }

    @Test
    fun aChannelPostGoesThroughTheHelperUnderTheChannelsHash() {
        val channels = src("service/RfedChannelClient.kt")
        assertTrue(Regex("MessageNotificationHelper\\.notify\\([\\s\\S]{0,300}?chatId = channelHashHex,").containsMatchIn(channels))
    }
}
