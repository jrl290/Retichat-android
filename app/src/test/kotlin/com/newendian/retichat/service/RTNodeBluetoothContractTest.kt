package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bluetooth to any RTNode in range (2026-09-28). The radio code is
 * RTNodeBluetooth; the protocol and every dial decision are Reticulum-rust's
 * (interfaces::prns_ble). These pin what the source must keep.
 */
class RTNodeBluetoothContractTest {

    private val stackRuntime = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()
    private val radio = File("src/main/kotlin/com/newendian/retichat/service/RTNodeBluetooth.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val next = Regex("\n    (private |internal |fun |suspend fun )").find(source, start + 1)
        return source.substring(start, next?.range?.first ?: source.length)
    }

    /** DESIGN_PRINCIPLES §5: the RTNode interface's up-edge is when Transport
     *  announces the published destination on it and app-links re-attempts
     *  its held links, so both must be in place before Bluetooth starts. */
    @Test
    fun bluetoothStartsAfterThePublishAndTheLinkRegistrations() {
        val publish = stackRuntime.indexOf("RetichatBridge.transportPublishDestination(selfDestHash")
        val links = stackRuntime.indexOf("ConnectionStateManager.register(app, routerHandle)")
        val bluetooth = stackRuntime.indexOf("RTNodeBluetooth.start(app, configDir.absolutePath)")
        assertTrue(publish >= 0 && links >= 0 && bluetooth >= 0)
        assertTrue("after the publish", publish < bluetooth)
        assertTrue("after the link registrations", links < bluetooth)
    }

    @Test
    fun bluetoothStopsBeforeTheStackShutsDown() {
        val shutdown = body(stackRuntime, "shutdownNow")
        val stop = shutdown.indexOf("RTNodeBluetooth.stop()")
        val unpublish = shutdown.indexOf("RetichatBridge.transportUnpublishDestination")
        val stack = shutdown.indexOf("RetichatBridge.shutdown()")
        assertTrue(stop >= 0 && unpublish >= 0 && stack >= 0)
        assertTrue("before the unpublish", stop < unpublish)
        assertTrue("before the stack", stop < stack)
    }

    /** James: "Retichat should not make itself available to another Retichat
     *  instance." The app only dials: no advertising, no GATT server. */
    @Test
    fun theAppIsACentralOnly() {
        for (peripheral in listOf("BluetoothLeAdvertiser", "startAdvertising", "openGattServer", "BluetoothGattServer")) {
            assertFalse("no $peripheral", radio.contains(peripheral))
        }
        assertFalse("no advertise permission", manifest.contains("BLUETOOTH_ADVERTISE"))
    }

    /** DESIGN_PRINCIPLES §3/§4: the radio keeps no timers and never re-dials
     *  on its own; it connects only on a link the engine returned for an
     *  advertisement it reported. */
    @Test
    fun theRadioConnectsOnlyWhereTheEngineSays() {
        for (timer in listOf("postDelayed", "Timer(", "delay(", "schedule", "sleep(")) {
            assertFalse("no $timer", radio.contains(timer))
        }
        assertEquals("one connect", 1, Regex("connectGatt\\(").findAll(radio).count())
        val sighted = body(radio, "sighted")
        val decision = sighted.indexOf("RetichatBridge.prnsBleSighted(")
        val zero = sighted.indexOf("if (link == 0L) return")
        val connect = sighted.indexOf("connectGatt(")
        assertTrue(decision >= 0 && zero >= 0 && connect >= 0)
        assertTrue("the engine decides, then the radio connects", decision < zero && zero < connect)
    }
}
