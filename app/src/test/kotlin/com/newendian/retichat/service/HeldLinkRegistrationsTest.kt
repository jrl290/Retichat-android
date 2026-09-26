package com.newendian.retichat.service

import com.newendian.retichat.bridge.RetichatBridge.AppLinkStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FCM token and rfed.notify registrations (2026-09-26): sent only on an
 * established held link, never lost to an ACTIVE edge that came while a send
 * was running, and owed until proved.
 */
class HeldLinkRegistrationsTest {

    private class FakeOps : HeldLinkOps {
        var status = AppLinkStatus.NONE
        var handler: ((Int) -> Unit)? = null
        var opens = 0
        var closes = 0
        val sent = mutableListOf<String>()
        /** Results for successive sends; a send beyond them is proved. */
        val results = ArrayDeque<suspend () -> Boolean>()

        override fun status(destHash: ByteArray) = status
        override fun openHeld(destHash: ByteArray, app: String, aspectsCsv: String) { opens++ }
        override fun close(destHash: ByteArray) { closes++ }
        override suspend fun setStatusHandler(destHash: ByteArray, handler: (Int) -> Unit) { this.handler = handler }
        override suspend fun sendData(destHash: ByteArray, app: String, aspectsCsv: String, payload: ByteArray): Boolean {
            sent.add(String(payload))
            return results.removeFirstOrNull()?.invoke() ?: true
        }
    }

    private fun registrations(ops: FakeOps) = HeldLinkRegistrations(
        ByteArray(16), "fcm", "register", CoroutineScope(SupervisorJob() + Dispatchers.Default), ops,
    ) { _, _ -> }

    @Test
    fun nothingIsSentBeforeTheHeldLinkIsEstablished() = runBlocking {
        val ops = FakeOps()
        val reg = registrations(ops)
        reg.owe("token", "p".toByteArray())
        assertEquals("the held link is opened", 1, ops.opens)
        assertTrue("no send before its ACTIVE edge", ops.sent.isEmpty())

        ops.status = AppLinkStatus.ACTIVE
        reg.onActive()
        assertEquals(listOf("p"), ops.sent)
        assertTrue(reg.owedKeys().isEmpty())
        assertEquals("closed once nothing is owed", 1, ops.closes)
    }

    /**
     * The phone, 2026-09-26 03:45 UTC: the ACTIVE edge came while the first
     * send was still running, was skipped, and the send then failed. Nothing
     * sent the registration again. The edge now waits for the running send
     * and sends what is still owed.
     */
    @Test
    fun anActiveEdgeDuringAFailingSendIsNotLost() = runBlocking {
        val ops = FakeOps()
        ops.status = AppLinkStatus.ACTIVE
        val firstSendStarted = CompletableDeferred<Unit>()
        val firstSendMayEnd = CompletableDeferred<Unit>()
        ops.results.add { firstSendStarted.complete(Unit); firstSendMayEnd.await(); false }
        val reg = registrations(ops)

        // One thread (runBlocking's): the edge is suspended on the lock,
        // behind the running send, before that send is allowed to end.
        val first = async { reg.owe("token", "p".toByteArray()) }
        firstSendStarted.await()
        val edge = async { reg.onActive() }
        yield()
        firstSendMayEnd.complete(Unit)
        first.await()
        edge.await()

        assertEquals("sent again for the edge that came mid-send", listOf("p", "p"), ops.sent)
        assertTrue(reg.owedKeys().isEmpty())
        assertEquals(1, ops.closes)
    }

    @Test
    fun anUnprovedRegistrationStaysOwedUntilTheNextEdge() = runBlocking {
        val ops = FakeOps()
        ops.status = AppLinkStatus.ACTIVE
        ops.results.add { false }
        val reg = registrations(ops)

        reg.owe("token", "p".toByteArray())
        assertEquals(setOf("token"), reg.owedKeys())
        assertEquals("the link stays for the next edge", 0, ops.closes)
        assertEquals(1, ops.sent.size)

        reg.onActive()
        assertEquals(2, ops.sent.size)
        assertTrue(reg.owedKeys().isEmpty())
    }

    @Test
    fun aDeliveredRegistrationIsNotSentAgain() = runBlocking {
        val ops = FakeOps()
        ops.status = AppLinkStatus.ACTIVE
        val reg = registrations(ops)
        reg.owe("token", "p".toByteArray())
        reg.owe("token", "p".toByteArray())
        assertEquals(1, ops.sent.size)
        assertEquals("no second link for it", 0, ops.opens)
    }

    @Test
    fun theHandlerSendsOnActiveOnly() = runBlocking {
        val ops = FakeOps()
        val reg = registrations(ops)
        reg.owe("token", "p".toByteArray())
        ops.handler!!(AppLinkStatus.ESTABLISHING)
        ops.handler!!(AppLinkStatus.DISCONNECTED)
        yield()
        assertTrue(ops.sent.isEmpty())
    }

    @Test
    fun aForgottenRegistrationIsNotSent() = runBlocking {
        val ops = FakeOps()
        val reg = registrations(ops)
        reg.owe("channel", "p".toByteArray())
        reg.forget("channel")
        ops.status = AppLinkStatus.ACTIVE
        reg.onActive()
        assertTrue(ops.sent.isEmpty())
    }

    /** The stream needs the router and APP_LINKs, which register sets up. */
    @Test
    fun thePropagationStreamStartsAfterConnectionStateRegisters() {
        val source = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()
        val register = source.indexOf("ConnectionStateManager.register(app, routerHandle)")
        val start = source.indexOf("PropagationStream.start(app, routerHandle, identityHandle, selfDestHash)")
        assertTrue(register >= 0 && start > register)
        assertTrue(source.contains("PropagationStream.stop()"))
    }

    @Test
    fun theStreamOpenIsAcceptedOnlyOnTrue() {
        assertTrue(PropagationStream.openAccepted(byteArrayOf(0x92.toByte(), 0xc3.toByte(), 0xc0.toByte())))
        assertTrue(!PropagationStream.openAccepted(byteArrayOf(0x92.toByte(), 0xc2.toByte(), 0xc0.toByte())))
        assertTrue(!PropagationStream.openAccepted(null))
    }
}
