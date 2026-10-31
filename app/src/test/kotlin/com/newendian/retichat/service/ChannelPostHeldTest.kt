package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A post rfed did not answer was failed for good (2026-10-30, so that it was
 * never sent twice), and that took in the posts that never left: the request
 * waits 5 s for the rfed.link to come up and, when it doesn't, nothing is sent
 * and nothing is answered. On staging (2026-10-31) a post made just after the
 * network came back kept its red mark, never sent, and a channel post has no
 * re-send by hand.
 *
 * Now (James, 2026-10-31: "Yes, match iPhone") such a post is held, as iOS
 * holds it: queued again, the pending clock, and kept as it was packed; the
 * release its rfed.link's next ACTIVE edge or another post rfed takes brings
 * sends those same bytes, which every reader dedupes if rfed had the post;
 * its echo, if it comes first, makes it SENT and it is not sent again.
 *
 * RfedChannelClient is Android: its source is what is pinned.
 */
class ChannelPostHeldTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val client = src("service/RfedChannelClient.kt")
    private val link = src("service/RfedLink.kt")
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
    fun anUnansweredPostIsHeldQueuedAsItWasPackedAndItsLinkOpened() {
        val hold = body(client, "holdPost")
        inOrder(
            hold,
            // Kept before it is queued: a release that finds it queued finds its bytes.
            "heldPosts[optimisticId] = HeldPost(packed, postingHex, postName)",
            "if (channelDao.holdSendingMessage(optimisticId) == 0) {",
            // Its echo came meanwhile: it is the post, SENT, and not held.
            "heldPosts.remove(optimisticId)",
            "return true",
            "RfedLink.ensure(app, channel.rfedNodeIdentityHashHex)",
            "return true",
        )
        // Its entry awaiting the answer stays, for its echo.
        assertFalse(hold.contains("postsAwaitingAnswer"))
        assertFalse(hold.contains("SEND_STATE_FAILED"))
    }

    @Test
    fun aHeldPostGoesAsItWasPackedUnderTheIdentityThatSignedIt() {
        val post = body(client, "post")
        inOrder(
            post,
            "val held = queuedId?.let { heldPosts[it] }",
            "val postingHex = held?.postingHex ?: postingHash.toHex()",
            "if (channelDao.claimQueuedMessage(queuedId, postingHex) == 0) return false",
            // Taken from the map with the row: held again only if it is not answered again.
            "heldPosts.remove(queuedId)",
            "val postName = held?.postName ?:",
            "val packed = held?.packed ?: packPost(channel, content, postingHandle, postName)",
            "trySend(liveChannel, packed, content, optimisticId, postingHex, postName)",
        )
    }

    @Test
    fun theHeldPostsGoOnEveryActiveEdgeOfTheirLinkAndAfterAPostRfedTook() {
        // Every ACTIVE edge binds the link; the release goes first, launched, waiting for none of the binding.
        val bind = link.substringAfter("private suspend fun bind(").substringBefore("    /**")
        inOrder(
            bind,
            "app.rfedChannelClient.onRfedLinkActive()",
            "app.rfedChannelClient.sendChannelStreamConfigForNode(node)",
        )
        assertTrue(body(client, "onRfedLinkActive").contains("scope.launch(Dispatchers.IO) { releaseQueuedPosts() }"))
        // After a post rfed took: launched, never inline. post() holds sendMutex and may run
        // inside a release, whose SerialFlush lock this release waits for.
        val afterSent = body(client, "releaseAfterSent")
        inOrder(afterSent, "scope.launch(Dispatchers.IO) { releaseQueuedPosts() }", "return true")
    }

    @Test
    fun aReleaseSendsOnlyWhereTheLinkIsUpAndOpensTheOthers() {
        val release = body(client, "releaseQueuedPosts")
        inOrder(
            release,
            "queuedRelease.run(",
            "channelDao.queuedOutboundMessages()",
            "val node = linkNode(channel.rfedNodeIdentityHashHex)",
            "if (node != null && !RfedLink.isActive(node)) {",
            "RfedLink.ensure(app, node)",
            "continue",
            "sendMutex.withLock { post(channel, row.content, queuedId = row.id) }",
        )
        assertTrue(body(link, "isActive").contains(
            "destHash(nodeHex)?.let { ConnectionStateManager.appLinkStatus(it) } == RetichatBridge.AppLinkStatus.ACTIVE",
        ))
    }

    @Test
    fun noTimerSendsAHeldPost() {
        // Ordering, not a retry (DESIGN_PRINCIPLES §3, §5): each release follows a signal.
        for (name in listOf("holdPost", "releaseAfterSent", "onRfedLinkActive", "releaseQueuedPosts")) {
            val text = body(client, name)
            for (timer in listOf("delay(", "withTimeout", "Timer(", "postDelayed")) {
                assertFalse("$name has no $timer", text.contains(timer))
            }
        }
    }

    @Test
    fun anEchoOfAHeldPostMakesItSentAndItIsNotSentAgain() {
        val arrival = client.substringAfter("val msgId = canonicalMessageId(sourceHashHex, result.timestampMs)")
        inOrder(
            arrival,
            "postsAwaitingAnswer.remove(msgId)?.let { placeholderId ->",
            "channelDao.replaceMessage(",
            "sendState = ChannelMessageEntity.SEND_STATE_SENT,",
            "heldPosts.remove(placeholderId)",
            "return@withContext true",
        )
        // Only holdPost keeps a post's bytes: one put, there.
        assertEquals(1, Regex("heldPosts\\[[^\\]]+\\] = ").findAll(client).count())
        assertTrue(body(client, "holdPost").contains("heldPosts[optimisticId] = "))
    }
}
