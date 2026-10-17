package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A chat muted in its info sheet posts no notification, as on iOS
 * (NotificationManager.postMessageNotification). Until 2026-10-17 the switch
 * saved the mute and nothing read it: direct messages, Group posts, Group
 * invites and reactions all notified a muted chat. The check is in
 * [MessageNotificationHelper.notify], and nothing else posts a notification,
 * so every caller has it. Android (SharedPreferences, NotificationManager):
 * the source is what is pinned.
 */
class MutedChatNotificationTest {
    private val main = File("src/main/kotlin/com/newendian/retichat")
    private fun src(path: String) = File(main, path).readText()
    private val helper = src("service/MessageNotificationHelper.kt")

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
    fun aMutedChatReturnsBeforeAnythingIsStackedOrPosted() {
        val start = helper.indexOf("    fun notify(")
        assertTrue("MessageNotificationHelper.notify not found", start >= 0)
        val notify = helper.substring(start, helper.indexOf("\n    fun clearChat(", start))
        inOrder(
            notify,
            "if (UserPreferences.isChatMuted(context, chatId)) {",
            "return",
            "history.add(chatId,",
            "nm.notify(chatId, notifId, notification)",
        )
    }

    @Test
    fun theSheetsSwitchSavesTheMuteThatIsRead() {
        val sheet = src("ui/conversation/ChatInfoSheet.kt")
        assertTrue(sheet.contains("mutableStateOf(!UserPreferences.isChatMuted(context, chatId))"))
        assertTrue(sheet.contains("UserPreferences.setChatMuted(context, chatId, !enabled)"))
    }

    @Test
    fun nothingButTheHelperPostsANotification() {
        val posters = main.walk()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().replace("MessageNotificationHelper.notify(", "").contains(".notify(") }
            .map { it.relativeTo(main).path }
            .toList()
        assertEquals(listOf("service/MessageNotificationHelper.kt"), posters)
    }
}
