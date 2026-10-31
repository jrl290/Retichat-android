package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A channel post showed twice to everyone (production, 2026-10-30: "who else
 * is in here?" twice at 11:01). post() sent a post a second time whenever the
 * first attempt failed, for any reason, and every attempt packed the post
 * again, with a new LXMF timestamp and signature. rfed's answer often came
 * after 5 s (links took 6-18 s to come up), when rfed had already stored the
 * post and fanned it out: the app saw no answer and sent a second post. Every
 * reader dedupes channel posts by source and LXMF timestamp, so everyone
 * showed both.
 *
 * Now (James, 2026-10-30) a post is packed once and every attempt sends those
 * bytes, only the stamp computed again; it is sent again only after rfed
 * answered with a refusal; and a post rfed did not answer keeps its entry
 * awaiting the answer, so its echo, if rfed has it, makes the failed row the
 * post, SENT.
 *
 * RfedChannelClient is Android: its source is what is pinned.
 */
class ChannelPostSameBytesTest {
    private val client = File("src/main/kotlin/com/newendian/retichat/service/RfedChannelClient.kt").readText()
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")

    private fun body(name: String): String {
        val start = client.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(client, start)?.range?.first ?: client.length
        return client.substring(start, end)
    }

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("\"$from\" not found", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("\"$to\" not found after \"$from\"", end >= 0)
        return text.substring(start, end)
    }

    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (part in parts) {
            val next = text.indexOf(part, at + 1)
            assertTrue("\"$part\" after the one before it", next > at)
            at = next
        }
    }

    /** Calls of [name] in [text], its declaration not counted. */
    private fun calls(text: String, name: String) = Regex("(?<!fun )\\b" + Regex.escape(name) + "\\(").findAll(text).count()

    @Test
    fun aPostIsPackedOnceAndEveryAttemptSendsThoseBytes() {
        // The client's one pack, in packPost, called once: by post(), before its first attempt.
        assertEquals(1, calls(client, "RetichatBridge.channelLxmPack"))
        assertTrue(body("packPost").contains("RetichatBridge.channelLxmPack("))
        assertEquals(1, calls(client, "packPost"))
        val post = body("post")
        inOrder(
            post,
            "val packed = packPost(channel, content, postingHandle, postName)",
            "trySend(liveChannel, packed,",
            "trySend(liveChannel, packed,",
        )
        // Both attempts, and no other: each sends that same packed post.
        assertEquals(2, calls(client, "trySend"))
        assertEquals(2, Regex(Regex.escape("trySend(liveChannel, packed,")).findAll(post).count())
        // An attempt neither packs nor signs: its timestamp and bytes are the packed post's,
        // and only the stamp after them is computed, at the channel's stamp cost now.
        val attempt = body("trySend")
        assertFalse(attempt.contains("channelLxmPack"))
        assertFalse(attempt.contains("packPost("))
        inOrder(
            attempt,
            "val tsMs = packed.tsMs",
            "val wirePayload = packed.wirePayload",
            "val cost = channel.stampCost ?: 0",
            "RetichatBridge.channelComputeStamp(wirePayload, cost)",
            "wirePayload + stamp",
            "val canonicalId = canonicalMessageId(postingHex, tsMs)",
        )
        // A post that cannot be packed is failed at once: no attempt.
        inOrder(
            between(post, "val packed = packPost(", "trySend("),
            "?: run {",
            "ChannelMessageEntity.SEND_STATE_FAILED",
            "return false",
        )
    }

    @Test
    fun aPostIsSentAgainOnlyAfterRfedAnsweredWithARefusal() {
        val post = body("post")
        inOrder(
            post,
            "val first = trySend(liveChannel, packed,",
            "if (first == SendOutcome.SENT) return true",
            // No answer, or nothing sent: failed, never sent again.
            "if (first != SendOutcome.REFUSED) {",
            "ChannelMessageEntity.SEND_STATE_FAILED",
            "return false",
            // A refusal: the stamp cost refreshed, and the same packed post sent once more.
            "refreshStampCost(liveChannel)",
            "trySend(liveChannel, packed,",
        )
        // Unanswered when rfed's answer did not come; refused only when it came.
        val attempt = body("trySend")
        inOrder(
            attempt,
            "val answer = RfedLink.request(app, rfedNode, \"/channel/publish\", msgpackBin(finalPayload))",
            "val refusal = publishRefusal(answer)",
            "if (refusal != null) {",
            "if (answer == null) {",
            "return@withContext SendOutcome.UNANSWERED",
            "} else {",
            "return@withContext SendOutcome.REFUSED",
        )
        assertEquals(1, Regex(Regex.escape("SendOutcome.REFUSED")).findAll(attempt).count())
    }

    @Test
    fun aPostRfedDidNotAnswerKeepsItsEntrySoItsEchoMakesItSent() {
        val attempt = body("trySend")
        // Looked at, never taken: the post's echo takes it, if rfed has the post.
        val unanswered = between(attempt, "if (answer == null) {", "return@withContext SendOutcome.UNANSWERED")
        assertTrue(unanswered.contains("if (postsAwaitingAnswer.containsKey(canonicalId)) {"))
        assertFalse(unanswered.contains("postsAwaitingAnswer.remove("))
        // An echo that took it first: rfed has the post, so it is sent.
        inOrder(
            attempt,
            "return@withContext SendOutcome.UNANSWERED",
            "but the post's echo came: sent",
            "} else {",
        )
        // post() leaves the entry alone, and fails the placeholder in place, its id kept:
        // the echo finds it by that id.
        val post = body("post")
        assertFalse(post.contains("postsAwaitingAnswer"))
        assertFalse(post.contains("deleteMessageById"))
        assertFalse(post.contains("replaceMessage"))
        // The echo makes the placeholder the post, SENT, whatever state it was in.
        val arrival = client.substringAfter("val msgId = canonicalMessageId(sourceHashHex, result.timestampMs)")
        inOrder(
            arrival,
            "postsAwaitingAnswer.remove(msgId)?.let { placeholderId ->",
            "channelDao.replaceMessage(",
            "placeholderId,",
            "sendState = ChannelMessageEntity.SEND_STATE_SENT,",
        )
    }
}
