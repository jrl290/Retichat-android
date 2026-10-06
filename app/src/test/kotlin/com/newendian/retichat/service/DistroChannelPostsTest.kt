package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RFed-spec/Channel.md "Distro holders: who posts, who subscribes" and "Own
 * posts": the posting identity, and which received posts are the user's own.
 */
class DistroChannelPostsTest {
    private val device = "a".repeat(32)
    private val distro = "d".repeat(32)
    private val stranger = "5".repeat(32)

    @Test
    fun aDeviceWithADistroPostsAsTheDistroAndOtherwiseAsItself() {
        assertEquals(distro, DistroCodec.postingHex(device, distro))
        assertEquals(device, DistroCodec.postingHex(device, null))
        assertEquals(device, DistroCodec.postingHex(device, ""))
    }

    @Test
    fun aPostFromTheDeviceOrFromTheHeldDistroIsTheUsersOwn() {
        assertTrue(DistroCodec.isOwnChannelPost(device, device, distro))
        assertTrue(DistroCodec.isOwnChannelPost(distro, device, distro))
        assertTrue(DistroCodec.isOwnChannelPost(distro.uppercase(), device, distro))
        assertTrue(DistroCodec.isOwnChannelPost(device, device, null))
        assertFalse(DistroCodec.isOwnChannelPost(stranger, device, distro))
    }

    @Test
    fun aDistroGivenUpIsNotOwnAnyMore() {
        assertFalse(DistroCodec.isOwnChannelPost(distro, device, null))
    }

    @Test
    fun anEmptyHashMatchesNothing() {
        // The stack is down: no device hash. No distro: no distro hash.
        assertFalse(DistroCodec.isOwnChannelPost("", "", null))
        assertFalse(DistroCodec.isOwnChannelPost("", "", ""))
        assertFalse(DistroCodec.isOwnChannelPost(device, "", null))
    }

    // ---- the wiring (RfedChannelClient is Android; its source is what is pinned) ----

    private val client = File("src/main/kotlin/com/newendian/retichat/service/RfedChannelClient.kt").readText()

    private fun body(from: String, to: String): String {
        val start = client.indexOf(from)
        assertTrue(from, start >= 0)
        val end = client.indexOf(to, start + from.length)
        assertTrue(to, end > start)
        return client.substring(start, end)
    }

    @Test
    fun aPostIsPackedWithThePostingIdentityAndRecordedUnderItsHash() {
        val send = body("suspend fun sendMessage(", "private suspend fun channelPostName(")
        assertTrue(send.contains("DistroManager.sendingIdentity(StackRuntime.selfDestHash, identityHandle)"))
        val trySend = body("private suspend fun trySend(", "// ── Subscribe (server)")
        assertTrue(trySend.contains("channel.channelName, postingHandle,"))
        assertTrue(trySend.contains("canonicalMessageId(postingHex, tsMs)"))
        assertTrue(trySend.contains("sourceHashHex = postingHex"))
        assertTrue(trySend.contains("recordPostName(channel.id, postingHex, postName)"))
        // The device's own hash is no longer what a post is filed under.
        assertFalse(send.contains("selfDestHash.toHex()"))
        assertFalse(trySend.contains("selfDestHash"))
    }

    @Test
    fun subscribingTheStreamPullsAndPushStayTheDevices() {
        // RFed keys a subscription, a bound link, a deferred bucket and a wake by
        // subscriber hash: under the distro the devices would steal each other's posts.
        for ((from, to) in listOf(
            "private suspend fun subscribeOnServer(" to "/**\n     * Parse `/rfed/subscribe` response",
            "suspend fun pullDeferred(" to "// ── Inbound blob dispatch",
            "private suspend fun sendChannelStreamConfig(" to "// ── Send ──",
            "suspend fun leaveChannel(" to "// ── Per-channel push toggle",
        )) {
            val text = body(from, to)
            assertFalse(from, text.contains("DistroManager"))
            assertFalse(from, text.contains("postingHandle"))
        }
        assertTrue(body("suspend fun leaveChannel(", "// ── Per-channel push toggle").contains("StackRuntime.identityHandle"))
    }

    @Test
    fun aReceivedPostIsOwnByTheSharedRuleAndOwnPostsAreNeverNotifiedOrCountedAsSenders() {
        val dispatch = body("private suspend fun dispatchBlob(", "private suspend fun recordChannelSender(")
        assertTrue(dispatch.contains("DistroCodec.isOwnChannelPost(sourceHashHex, deviceHex, distroHex)"))
        assertTrue(dispatch.contains("val channelName = if (isOutbound) null else {"))
        assertTrue(dispatch.contains("if (!isOutbound &&"))
        assertTrue(dispatch.contains("sendState = ChannelMessageEntity.SEND_STATE_SENT"))
        // What the distro's posts teach the name state: from the posting identity only.
        assertTrue(dispatch.contains("if (isOutbound && sourceHashHex.equals(postingHex, ignoreCase = true))"))
        // Dedupe by (source, timestamp), persisted: the id is both, checked before anything is stored.
        assertTrue(dispatch.indexOf("channelDao.messageExists(") < dispatch.indexOf("channelDao.upsertMessage("))
    }
}
