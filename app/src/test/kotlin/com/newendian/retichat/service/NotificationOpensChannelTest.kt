package com.newendian.retichat.service

import com.newendian.retichat.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tapped channel-post notification opens the channel's screen. Until
 * 2026-10-19 every tap opened the DM/Group route with the notification's chat
 * id, and a channel's hash there is no chat: a blank screen with no title
 * (James, 2026-10-19: "The notification action opens to a blank chat screen
 * with no title"). The notification now says it is a channel's
 * ([MessageNotificationHelper.EXTRA_CHANNEL]) and the tap picks the route.
 */
class NotificationOpensChannelTest {
    private val main = File("src/main/kotlin/com/newendian/retichat")
    private fun src(path: String) = File(main, path).readText()

    @Test
    fun aChannelsTapOpensTheChannelScreenAnyOtherTheConversation() {
        val hash = "a704b2dcdb2f96faef2a08bfb90f84db"
        assertEquals("channel/$hash", Routes.forNotification(hash, channel = true))
        assertEquals(Routes.channel(hash), Routes.forNotification(hash, channel = true))
        assertEquals(Routes.conversation(hash), Routes.forNotification(hash, channel = false))
    }

    @Test
    fun theNotificationCarriesWhetherItIsAChannels() {
        val helper = src("service/MessageNotificationHelper.kt")
        assertTrue(helper.contains("putExtra(EXTRA_CHAT_ID, chatId)\n            putExtra(EXTRA_CHANNEL, channel)"))
        val channels = src("service/RfedChannelClient.kt")
        assertTrue("a channel post's notification says so",
            Regex("MessageNotificationHelper\\.notify\\([\\s\\S]{0,400}?chatId = channelHashHex,[\\s\\S]{0,120}?channel = true,").containsMatchIn(channels))
    }

    @Test
    fun theTapReadsItAndPicksTheRoute() {
        val activity = src("MainActivity.kt")
        assertTrue(activity.contains("val channel = intent.getBooleanExtra(MessageNotificationHelper.EXTRA_CHANNEL, false)"))
        assertTrue(activity.contains("navigateToChat(chatId, channel)"))
        assertTrue(activity.contains("navController?.navigate(Routes.forNotification(chatId, channel))"))
    }
}
