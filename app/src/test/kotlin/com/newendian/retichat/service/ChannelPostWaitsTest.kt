package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A channel post typed while the app is starting waits as a DM does (James's UI
 * rulings, 2026-10-11; GROUPS-DISTRO-DESIGN.md §8.7): stored at once with the
 * DM's pending clock, and sent when the start finishes, through the same wait
 * for the start the DM queue uses (StackRuntime.waitForStart, the ready signal,
 * the network's return), as the posting identity held then. Until 2026-10-11
 * the composer cleared the draft and RfedChannelClient.sendMessage returned
 * false with nothing stored or said: the post was lost.
 *
 * RfedChannelClient is Android: its source is what is pinned; the queue's SQL is
 * QueuedPostsSqlTest's.
 */
class ChannelPostWaitsTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val client = src("service/RfedChannelClient.kt")
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (part in parts) {
            val next = text.indexOf(part, at + 1)
            assertTrue("\"$part\" after the one before it", next > at)
            at = next
        }
    }

    @Test
    fun aPostTypedBeforeTheStartFinishesIsQueuedNeverDropped() {
        val send = body(client, "sendMessage")
        assertFalse("never dropped", send.contains("if (identityHandle == 0L) return@withLock false"))
        inOrder(
            send,
            "if (started()) post(channel, content, queuedId = null) else {",
            "queuePost(channel, content)",
            "StackRuntime.waitForStart(appContext, \"SEND\") { releaseQueuedPosts() }",
        )
        // The start's test is the DM queue's (ChatRepository.stackReady).
        assertTrue(body(client, "started").contains("StackRuntime.isReady && StackRuntime.identityHandle != 0L"))
        // Stored now, queued, so the conversation shows it with the pending clock.
        val queue = body(client, "queuePost")
        inOrder(queue, "sendState = ChannelMessageEntity.SEND_STATE_QUEUED,", "channelDao.upsertMessage(row)")
        val entities = src("data/db/entity/Entities.kt")
        assertTrue(entities.contains("const val SEND_STATE_QUEUED = 3"))
        val screen = src("ui/conversation/ConversationScreen.kt")
        assertTrue(screen.contains("ChannelMessageEntity.SEND_STATE_QUEUED -> RetichatBridge.MessageState.OUTBOUND"))
    }

    @Test
    fun theQueuedPostsGoWhenTheStartFinishesOrTheNetworkComesBackAsQueuedDmsDo() {
        val runtime = src("service/StackRuntime.kt")
        inOrder(runtime, "if (ok) {", "app.repository.onStackReady()", "app.rfedChannelClient.onStackReady()")
        assertTrue(body(client, "onStackReady").contains("releaseQueuedPosts()"))
        assertTrue(client.contains("NetworkMonitor.addOnAvailableListener(networkListener)"))
        val release = body(client, "releaseQueuedPosts")
        // One release at a time (SerialFlush, as the DM queue), each reading the queue afresh.
        assertTrue(release.contains("queuedRelease.run("))
        assertTrue(release.contains("if (!started() || !NetworkMonitor.isOnline.value) emptyList() else channelDao.queuedOutboundMessages()"))
        assertTrue(release.contains("sendMutex.withLock { post(channel, row.content, queuedId = row.id) }"))
    }

    @Test
    fun aQueuedPostIsTakenFromTheQueueOnceAndGoesAsThePostingIdentityHeldThen() {
        val post = body(client, "post")
        inOrder(
            post,
            "DistroManager.sendingIdentity(StackRuntime.selfDestHash, identityHandle)",
            "if (channelDao.claimQueuedMessage(queuedId, postingHex) == 0) return false",
            "trySend(",
        )
        val dao = src("data/db/dao/ChannelDao.kt")
        assertTrue(dao.contains("UPDATE channel_messages SET sendState = 0, sourceHashHex = :postingHex WHERE id = :id AND isOutbound = 1 AND sendState = 3"))
        assertTrue(dao.contains("SELECT * FROM channel_messages WHERE isOutbound = 1 AND sendState = 3 ORDER BY timestamp ASC"))
        // Only what was sending when the process died is failed at the next start; a queued post waits.
        assertTrue(dao.contains("UPDATE channel_messages SET sendState = 2 WHERE isOutbound = 1 AND sendState = 0"))
        assertEquals(1, Regex(Regex.escape("queuePost(channel, content)")).findAll(client).count())
    }
}
