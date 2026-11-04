package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A channel goes to the top of the chat list only when it is active (James,
 * 2026-11-03: "I want the Channel chat to be open and a message be sent or
 * received before moving it to the top of the chat list"; the posts a channel
 * loads on opening count). Until then a channel moved up on every post stored,
 * open or not. Now ChannelDao.markChannelActive is called on a post sent from
 * the channel and on a post stored while the channel is the chat open in the
 * app, and nowhere else; the chat list orders a channel by lastActiveAt and
 * still shows its newest post's time. The migration and the DAO's SQL run in
 * ChannelActivityMigrationTest; the sources are what is pinned here.
 */
class ChannelListOrderTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val client = src("service/RfedChannelClient.kt")
    private val viewModel = src("ui/chatlist/ChatListViewModel.kt")
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")
    private val mark = Regex("markChannelActive\\(")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun aPostSentFromTheChannelMovesItToTheTop() {
        assertTrue(body(client, "queuePost").contains("channelDao.markChannelActive(channel.id, row.timestamp)"))
        // A new post; one released from the queue moved it when it was typed.
        val post = body(client, "post")
        val newPost = post.indexOf("} else {").let { from -> post.substring(from, post.indexOf("_activity.value", from)) }
        assertTrue(newPost.contains("channelDao.markChannelActive(channel.id, optimistic.timestamp)"))
        assertEquals(1, mark.findAll(post).count())
    }

    @Test
    fun aPostStoredMovesItOnlyWhileTheChannelIsOpen() {
        val dispatch = body(client, "dispatchBlob")
        assertTrue(dispatch.contains(
            "if (RetichatApp.activeChatId.equals(channelHashHex, ignoreCase = true)) {\n" +
            "                channelDao.markChannelActive(channelHashHex, System.currentTimeMillis())\n" +
            "            }"))
        assertEquals("the echo of the user's own post, moved up by its send, does not move it", 1,
            mark.findAll(dispatch).count())
    }

    @Test
    fun openingAChannelMovesNothing() {
        assertEquals("queuePost, post and dispatchBlob only", 3, mark.findAll(client).count())
        assertFalse(body(client, "markChannelOpenedForStreaming").contains("markChannelActive"))
        val screen = src("ui/conversation/ConversationScreen.kt")
        assertFalse(screen.contains("markChannelActive"))
        assertFalse(screen.contains("lastActiveAt"))
    }

    @Test
    fun theListOrdersAChannelByWhenItWasActiveAndShowsItsNewestPostsTime() {
        assertTrue(viewModel.contains("lastTimestamp = ch.lastMessageTime.takeIf { it > 0L } ?: ch.joinedAt,"))
        assertTrue(viewModel.contains("orderTimestamp = ch.lastActiveAt.takeIf { it > 0L } ?: ch.joinedAt,"))
        assertTrue(viewModel.contains("mapped.sortedByDescending { it.orderTimestamp ?: it.lastTimestamp ?: 0L }"))
    }
}
