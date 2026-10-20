package com.newendian.retichat.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A channel post sent from this device showed twice for a moment (James,
 * 2026-10-20: "when sending, two bubbles for the same message appear, then
 * one is removed"). rfed sends a post to the channel's readers, this device
 * among them, before it answers the publish, and the echo was stored as a
 * second row beside the placeholder until the answer removed the
 * placeholder. And on the answer, the post was stored and the placeholder
 * deleted in two writes, so the list could show both between them.
 */
class ChannelEchoBeforeAnswerTest {

    private val dao = File("src/main/kotlin/com/newendian/retichat/data/db/dao/ChannelDao.kt").readText()
    private val client = File("src/main/kotlin/com/newendian/retichat/service/RfedChannelClient.kt").readText()

    private fun assertInOrder(source: String, vararg parts: String) {
        var from = 0
        for (part in parts) {
            val at = source.indexOf(part, from)
            assertTrue("\"$part\" after the previous part", at >= 0)
            from = at + part.length
        }
    }

    /** The placeholder goes and the post comes in one transaction. */
    @Test
    fun thePlaceholderBecomesThePostInOneTransaction() {
        assertInOrder(
            dao,
            "@Transaction",
            "suspend fun replaceMessage(placeholderId: String, post: ChannelMessageEntity) {",
            "if (placeholderId != post.id) deleteMessageById(placeholderId)",
            "upsertMessage(post)",
        )
    }

    @Test
    fun aPostAwaitsItsAnswerByItsEchosId() {
        val send = client.substringAfter("val canonicalId = canonicalMessageId(postingHex, tsMs)")
            .substringBefore("// ── Subscribe (server)")
        assertInOrder(
            send,
            "postsAwaitingAnswer[canonicalId] = optimisticId",
            "val answer = RfedLink.request(app, rfedNode, \"/channel/publish\", msgpackBin(finalPayload))",
            "if (refusal != null) {",
            // Still waiting: no echo, so the refusal stands.
            "if (postsAwaitingAnswer.remove(canonicalId) != null) {",
            "return@withContext false",
            "recordPostName(channel.id, postingHex, postName)",
            "channelDao.replaceMessage(",
            "optimisticId,",
            "sendState = ChannelMessageEntity.SEND_STATE_SENT,",
            // Taken after the row is replaced: an echo meanwhile replaces it
            // too, and one after finds it stored.
            "postsAwaitingAnswer.remove(canonicalId)",
        )
        assertFalse(send.contains("channelDao.deleteMessageById(optimisticId)"))
    }

    @Test
    fun anEchoBeforeTheAnswerBecomesThePostsRow() {
        val arrival = client.substringAfter("val msgId = canonicalMessageId(sourceHashHex, result.timestampMs)")
        assertInOrder(
            arrival,
            "postsAwaitingAnswer.remove(msgId)?.let { placeholderId ->",
            "channelDao.replaceMessage(",
            "placeholderId,",
            "id = msgId,",
            "isOutbound = true,",
            "sendState = ChannelMessageEntity.SEND_STATE_SENT,",
            "return@withContext true",
            // Before the duplicate check and the ordinary store.
            "if (channelDao.messageExists(channelHashHex, msgId) > 0) return@withContext true",
            "channelDao.upsertMessage(",
        )
    }
}
