package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the distro outbox is wired into the app: who owes what, what starts it,
 * and what must never send a membership message (the Android classes are not
 * run in a JVM test; their source is what is pinned, as the other contract tests
 * here do).
 */
class DistroOutboxWiringTest {
    private fun read(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    private val runtime = read("service/DistroOutboxRuntime.kt")
    private val stack = read("service/StackRuntime.kt")
    private val channelClient = read("service/RfedChannelClient.kt")
    private val distroClient = read("service/RfedDistroClient.kt")
    private val repository = read("data/repository/ChatRepository.kt")

    private fun body(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue(from, start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue(to, end > start)
        return text.substring(start, end)
    }

    @Test
    fun theStackStartsTheOutboxAfterTheLinksAreWiredAndStopsItFirst() {
        val register = stack.indexOf("ConnectionStateManager.register(app, routerHandle)")
        val start = stack.indexOf("DistroOutboxRuntime.start(app)")
        assertTrue(register in 0 until start)
        // Before the propagation sync or any send can open the propagation link.
        assertTrue(start < stack.indexOf("PropagationStream.start("))
        val shutdown = body(stack, "private fun shutdownNow(", "ConnectionStateManager.unregister()")
        assertTrue(shutdown.contains("DistroOutboxRuntime.stop()"))
    }

    @Test
    fun onlyTheUsersOwnJoinLeaveAndSendOweAnything() {
        // C is owed from one place, RfedChannelClient.recordUsersAction; the sent-copy from one, ChatRepository.
        assertEquals(1, Regex("DistroOutboxRuntime\\.oweChannelSync\\(").findAll(channelClient).count())
        assertEquals(1, Regex("DistroOutboxRuntime\\.oweSentCopy\\(").findAll(repository).count())
        // Nothing else in the app owes the distro anything: not the stack, not the distro client's receive path.
        for ((name, text) in listOf("stack" to stack, "distro client" to distroClient)) {
            assertFalse(name, text.contains("oweChannelSync(") || text.contains("oweSentCopy("))
        }
        val record = body(channelClient, "private suspend fun recordUsersAction(", "suspend fun handleDistroChannelSync(")
        assertTrue(record.contains("if (!DistroManager.hasDistro) return"))
        // The record is made with or without a distro; the message only with one.
        assertTrue(record.indexOf("channelDao.upsertMembership(") < record.indexOf("DistroOutboxRuntime.oweChannelSync("))
    }

    @Test
    fun theUsersOwnActionIsTheOnlyCallerOfRecordUsersAction() {
        // Its declaration and its two callers, joinChannel and leaveChannel.
        assertEquals(3, Regex("recordUsersAction\\(").findAll(channelClient).count())
        val join = body(channelClient, "suspend fun joinChannel(", "suspend fun leaveChannel(")
        // A join of a channel already in the list is no change: no message.
        assertTrue(join.contains("if (existing == null) recordUsersAction(DistroChannelSync.OP_JOIN, entity)"))
        val leave = body(channelClient, "suspend fun leaveChannel(", "private suspend fun leaveHere(")
        assertTrue(leave.contains("recordUsersAction(DistroChannelSync.OP_LEAVE, entity)"))
        assertTrue(leave.indexOf("leaveHere(entity)") < leave.indexOf("recordUsersAction("))
    }

    @Test
    fun applyingASiblingsChangeSendsNothingAndSubscribesWithTheDevicesKey() {
        val apply = body(channelClient, "private suspend fun applyDistroChannelSync(", "private suspend fun subscribeStoredChannel(")
        // Never re-sent, never echoed: applying C calls neither the record nor the outbox.
        assertFalse(apply.contains("recordUsersAction"))
        assertFalse(apply.contains("oweChannelSync"))
        assertFalse(apply.contains("joinChannel("))
        assertFalse(apply.contains("leaveChannel("))
        // The leave is the shared clean-up, which sends nothing; the join is stored before it is subscribed.
        assertTrue(apply.contains("leaveHere(held)"))
        assertTrue(apply.indexOf("channelDao.upsert(entity)") < apply.indexOf("subscribeStoredChannel(entity)"))
        // The record is kept for every apply, including a leave (what stops an older join coming back).
        assertTrue(apply.contains("channelDao.upsertMembership(ChannelMembershipEntity(change.channelHash, change.op, change.atMs))"))
        val leaveHere = body(channelClient, "private suspend fun leaveHere(", "// ── Channels and the distro")
        assertFalse(leaveHere.contains("recordUsersAction"))
        assertFalse(leaveHere.contains("DistroOutboxRuntime"))
        // Subscribing is the device's, by the device's identity.
        val subscribe = body(channelClient, "private suspend fun subscribeStoredChannel(", "// ── Per-channel push toggle")
        assertTrue(subscribe.contains("StackRuntime.identityHandle"))
        assertFalse(subscribe.contains("DistroManager"))
    }

    @Test
    fun aMembershipMessageIsReadBeforeTheGenericDedupeAndTheDeliveryNotificationTest() {
        val handle = body(distroClient, "suspend fun handleBlob(", "private suspend fun handleChannelSync(")
        val read = handle.indexOf("DistroChannelSync.read(fieldsRaw)")
        assertTrue(read >= 0)
        assertTrue(read < handle.indexOf("DistroCodec.seenKey(srcHex, timestamp)"))
        assertTrue(read < handle.indexOf("if (isNotification) return"))
        val sync = body(distroClient, "private suspend fun handleChannelSync(", "/** Another device offered us a distro key")
        // Its own repeat key, and a dropped one is judged again.
        assertTrue(sync.contains("DistroChannelSync.repeatKey(srcHex, timestamp, marker)"))
        assertTrue(sync.contains("UserPreferences.forgetDistroSeen(context, key)"))
        // Rules 1 and 2: the source is D, and the signature is D's.
        assertTrue(sync.contains("val fromDistro = ownDistro != null && srcHex == ownDistro"))
        assertTrue(sync.contains("fromDistro && signatureValidated"))
    }

    @Test
    fun theSentCopyIsOwedNotHandedToTheRouterOnce() {
        val copy = body(repository, "private suspend fun sendDistroSentCopy(", "private suspend fun sendGroupMessage(")
        assertTrue(copy.contains("DistroOutboxRuntime.oweSentCopy(appContext, recipient, title, content)"))
        assertTrue(copy.contains("DistroCodec.shouldSendSentCopy("))
        // It no longer goes through the router, whose own re-send is paced by a timer and gives up after a count.
        assertFalse(copy.contains("messageSendViaAppLinks"))
        assertFalse(copy.contains("selectPropagationNode"))
        assertFalse(repository.contains("sentCopyHandles"))
        assertFalse(repository.contains("finishSentCopy"))
    }

    @Test
    fun anOwedMessageIsSignedByTheKeyItsSourceNames() {
        // The distro and the handle that signs as it are read together (DistroManager.signing), so a
        // generate, import or forget between two reads cannot give a source that the key does not match.
        val channel = body(runtime, "suspend fun oweChannelSync(", "suspend fun oweSentCopy(")
        val sent = body(runtime, "suspend fun oweSentCopy(", "private suspend fun pack(")
        for ((name, text) in listOf("C" to channel, "sent-copy" to sent)) {
            assertEquals(name, 1, Regex("DistroManager\\.signing\\(\\)").findAll(text).count())
            assertFalse(name, text.contains("DistroManager.identityHandle"))
            assertFalse(name, text.contains("DistroManager.state.value"))
        }
        val pack = body(runtime, "private suspend fun pack(", "private class JniPropagationPort(")
        assertFalse(pack.contains("DistroManager.identityHandle"))
        assertFalse(pack.contains("DistroManager.state"))
        assertTrue(pack.contains("identityHandle = signer"))
    }

    @Test
    fun theRuntimeStartsNoLinkAndOnlyAnEstablishmentOrTheStartUpCheckFlushes() {
        // It never opens or reopens the propagation link.
        assertFalse(runtime.contains("appLinkOpen"))
        assertFalse(runtime.contains("appLinkReopen"))
        assertFalse(runtime.contains("routerSetPropagationNode"))
        assertFalse(runtime.contains("selectPropagationNode"))
        // onLinkEstablished: the link callback's ACTIVE event and the check at start, nothing else.
        assertEquals(2, Regex("onLinkEstablished\\(").findAll(runtime).count())
        // A STALE link's recovery is not observable; it is not called from anywhere in the app.
        assertEquals(0, Regex("onLinkRecovered\\(").findAll(runtime).count())
        // Its one delay is the §1 watch, which only logs.
        assertEquals(1, Regex("\\bdelay\\(").findAll(runtime).count())
        assertTrue(body(runtime, "delay(DistroOutbox.PROOF_LIVE_MS + 1)", "return UploadLeft(").contains("Log.e("))
    }
}
