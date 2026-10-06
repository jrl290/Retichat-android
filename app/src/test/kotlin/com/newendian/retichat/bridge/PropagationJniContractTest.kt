package com.newendian.retichat.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin side of the JNI surface the distro outbox uses, against the Rust
 * crate and R8's rules (the .so is not loaded in a JVM test): a missing symbol
 * is an UnsatisfiedLinkError on the first call, and a callback whose method name
 * or signature drifted is a silent no-op on a Rust thread.
 */
class PropagationJniContractTest {
    private val bridge = File("src/main/kotlin/com/newendian/retichat/bridge/RetichatBridge.kt").readText()
    private val rust = File("../rust/retichat-jni/src/lib.rs").readText()
    private val rules = File("proguard-rules.pro").readText()

    @Test
    fun everyExternalFunctionHasItsRustSymbol() {
        val externals = Regex("external fun (native\\w+)\\(").findAll(bridge).map { it.groupValues[1] }.toSet()
        assertTrue(externals.size > 100)
        val symbols = Regex("fn Java_com_newendian_retichat_bridge_RetichatBridge_(native\\w+)\\(")
            .findAll(rust).map { it.groupValues[1] }.toSet()
        assertEquals("natives with no Rust symbol", emptySet<String>(), externals - symbols)
        for (name in listOf(
            "nativeMessageSetDistroChannelSync", "nativeMessagePack", "nativePropagationBuildUpload",
            "nativePropagationLink", "nativePropagationUpload", "nativePropagationLinkEventsRegister",
        )) assertTrue(name, name in externals)
    }

    @Test
    fun theCallbacksRustCallsAreTheMethodsKotlinDeclares() {
        assertTrue(bridge.contains("fun onLink(destHash: ByteArray, status: Int, linkId: ByteArray?)"))
        assertTrue(rust.contains("\"onLink\",\n                \"([BI[B)V\""))
        assertTrue(bridge.contains("fun onProved()"))
        assertTrue(bridge.contains("fun onLost()"))
        assertTrue(rust.contains("\"onProved\", \"()V\""))
        assertTrue(rust.contains("\"onLost\", \"()V\""))
    }

    @Test
    fun r8KeepsTheNewCallbacksAndTheirImplementations() {
        for (callback in listOf("PropagationLinkCallback", "PropagationUploadCallback")) {
            assertTrue(callback, rules.contains("-keep interface com.newendian.retichat.bridge.$callback { *; }"))
            assertTrue(callback, rules.contains("-keep class * implements com.newendian.retichat.bridge.$callback { *; }"))
            assertTrue(callback, Regex("@Keep\\s+interface $callback").containsMatchIn(bridge))
        }
    }

    @Test
    fun anUploadOnlyLeavesOnTheLinkTheStackHoldsAndNothingIsResentByTheCrate() {
        val upload = rust.substring(rust.indexOf("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationUpload("))
        val body = upload.substring(0, upload.indexOf("nativePropagationLinkEventsRegister"))
        // The one send is AppLinks::send_on_held_link: no race, no tiers, no second try.
        assertEquals(1, Regex("send_on_held_link\\(").findAll(body).count())
        assertTrue(body.contains("AppLinks::send_on_held_link(&node, data, true, on_delivered, on_failed, None)"))
        assertTrue(!body.contains("thread::sleep"))
        assertTrue(!body.contains("thread::spawn"))
        // A loss tears down the link the upload went on, then says so.
        assertTrue(body.indexOf("tear_down_link_of_upload(&lost_node, &link)") < body.indexOf("lost_report.lost()"))
    }
}
