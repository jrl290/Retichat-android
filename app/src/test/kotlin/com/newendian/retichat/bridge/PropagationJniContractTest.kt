package com.newendian.retichat.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "nativeInterfaceOnlineRegister", "nativeInterfaceOnlineUnregister",
            // The distro sync proof (RFed SPEC §17.13) and app-links' UpEdgeMark.
            "nativeDistroSeal", "nativePropagationBuildSealedUpload", "nativeInterfaceUpEdges",
        )) assertTrue(name, name in externals)
        // Kotlin declares each with the parameter types the Rust symbol takes.
        assertTrue(bridge.contains("private external fun nativeDistroSeal(distroHandle: Long, packed: ByteArray): ByteArray?"))
        assertTrue(bridge.contains("distroPublicKey: ByteArray, sealed: ByteArray, sig: ByteArray, withClaim: Boolean, node: ByteArray,"))
        assertTrue(bridge.contains("private external fun nativeInterfaceUpEdges(): Long"))
        val sealedBuild = rust.substring(rust.indexOf("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationBuildSealedUpload("))
        assertTrue(sealedBuild.substring(0, sealedBuild.indexOf(") -> jbyteArray")).contains("with_claim: jboolean,"))
        assertTrue(rust.contains("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativeInterfaceUpEdges(\n    _env: JNIEnv,\n    _class: JClass,\n) -> jlong"))
    }

    @Test
    fun theCallbacksRustCallsAreTheMethodsKotlinDeclares() {
        assertTrue(bridge.contains("fun onLink(destHash: ByteArray, status: Int, linkId: ByteArray?)"))
        assertTrue(rust.contains("\"onLink\",\n                \"([BI[B)V\""))
        assertTrue(bridge.contains("fun onProved()"))
        assertTrue(bridge.contains("fun onLost()"))
        assertTrue(rust.contains("\"onProved\", \"()V\""))
        assertTrue(rust.contains("\"onLost\", \"()V\""))
        // A Resource that never left (DESIGN_PRINCIPLES §3, 2026-10-06; the Resource case 2026-10-10).
        assertTrue(bridge.contains("fun onNeverLeft()"))
        assertTrue(rust.contains("\"onNeverLeft\", \"()V\""))
        // The interface's up-edge (James, 2026-10-06): the name, as a string.
        assertTrue(bridge.contains("fun onInterfaceOnline(name: String)"))
        assertTrue(rust.contains("\"onInterfaceOnline\", \"(Ljava/lang/String;)V\""))
    }

    @Test
    fun aRecoveredStatusIsToldWithItsLinkAndEveryDocListsIt() {
        // The event is told as every other status is: the JNI relays the code unchanged, and the link's
        // id whenever AppLinks reports one (it does for ESTABLISHING, ACTIVE and RECOVERED).
        val events = rust.substring(rust.indexOf("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationLinkEventsRegister("))
        val relay = events.substring(0, events.indexOf("// RTNode over Bluetooth"))
        assertTrue(relay.contains("JValue::Int(status as jint)"))
        assertTrue(relay.contains("Some(link) => match env.byte_array_from_slice(&link.link_id())"))
        // The code is app-links' APP_LINK_RECOVERED (a Rust test of the JNI crate pins the 5 to the crate's own constant).
        assertTrue(bridge.contains("const val RECOVERED      = 5"))
        // The docs that list the statuses list it, so nobody reads 0..4 as all there is.
        assertTrue(rust.contains("4 disconnected, 5 recovered"))
        assertTrue(bridge.contains("RECOVERED (a STALE link heard from its peer again)"))
    }

    @Test
    fun r8KeepsTheNewCallbacksAndTheirImplementations() {
        for (callback in listOf("PropagationLinkCallback", "PropagationUploadCallback", "InterfaceOnlineCallback")) {
            assertTrue(callback, rules.contains("-keep interface com.newendian.retichat.bridge.$callback { *; }"))
            assertTrue(callback, rules.contains("-keep class * implements com.newendian.retichat.bridge.$callback { *; }"))
            assertTrue(callback, Regex("@Keep\\s+interface $callback").containsMatchIn(bridge))
        }
    }

    @Test
    fun anUploadOnlyLeavesOnTheLinkTheStackHoldsAndNothingIsResentByTheCrate() {
        val upload = rust.substring(rust.indexOf("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationUpload("))
        val body = upload.substring(0, upload.indexOf("nativePropagationLinkEventsRegister"))
        // The one send is AppLinks::send_on_held_link_owed (Stage 0): no race, no tiers, no second try.
        assertEquals(1, Regex("send_on_held_link_owed\\(").findAll(body).count())
        assertEquals(0, Regex("send_on_held_link\\(").findAll(body).count())
        assertTrue(body.contains("AppLinks::send_on_held_link_owed(&node, data, true, on_delivered, on_failed, Some(watch.on_progress()), on_never_left)"))
        assertTrue(!body.contains("thread::sleep"))
        assertTrue(!body.contains("thread::spawn"))
        // A loss tears down the link the upload went on, then says so.
        assertTrue(body.indexOf("tear_down_link_of_upload(&lost_node, &link)") < body.indexOf("lost_report.lost()"))
    }

    /** The closure `let <name>: Arc<dyn Fn() ...> = Arc::new(move || { ... });` in [body]. */
    private fun closure(body: String, name: String): String {
        val start = body.indexOf("let $name: Arc<dyn Fn() + Send + Sync + 'static> = Arc::new(move || {")
        assertTrue(name, start >= 0)
        return body.substring(start, body.indexOf("});", start))
    }

    @Test
    fun aResourceThatNeverLeftIsToldWithNoTeardownAndTheSilenceWatchDecidesNothing() {
        val upload = rust.substring(rust.indexOf("fn Java_com_newendian_retichat_bridge_RetichatBridge_nativePropagationUpload("))
        val body = upload.substring(0, upload.indexOf("nativePropagationLinkEventsRegister"))
        // Never left: told, and the link is kept (James, 2026-10-06 and 2026-10-10).
        val neverLeft = closure(body, "on_never_left")
        assertTrue(neverLeft.contains("never_left_report.never_left()"))
        assertFalse(neverLeft.contains("tear_down"))
        assertFalse(neverLeft.contains("teardown"))
        assertFalse(neverLeft.contains("lost"))
        // §1, bulk transfers: the watch is fed by the Resource's progress and has no say in the outcome. Each
        // outcome is reported first, and the watch's end point (`finish`, where a debug build asserts) comes last.
        assertTrue(body.contains("let watch = ResourceSilenceWatch::new("))
        val delivered = closure(body, "on_delivered")
        assertTrue(delivered.indexOf("proved_watch.delivered()") in 0 until delivered.indexOf("proved_report.proved()"))
        assertTrue(delivered.indexOf("proved_report.proved()") < delivered.indexOf("proved_watch.finish()"))
        val failed = closure(body, "on_failed")
        assertTrue(failed.indexOf("lost_report.lost()") in 0 until failed.indexOf("lost_watch.finish()"))
        assertTrue(neverLeft.indexOf("never_left_report.never_left()") < neverLeft.indexOf("never_left_watch.finish()"))
        // The send's own Err (nothing left, no callback will come): its end point too, after the error is set.
        val err = body.substring(body.lastIndexOf("Err(e) => {"))
        assertTrue(err.indexOf("rns::set_error(e)") in 0 until err.indexOf("let _ = watch.finish();"))
        // finish is each closure's last statement.
        for ((name, text, call) in listOf(
            Triple("on_delivered", delivered, "proved_watch.finish();"),
            Triple("on_failed", failed, "lost_watch.finish();"),
            Triple("on_never_left", neverLeft, "never_left_watch.finish();"),
        )) assertTrue(name, text.trimEnd().endsWith(call))
        // The watch never decides: nothing reads it back, and no outcome depends on it.
        assertEquals(4, Regex("\\.finish\\(\\)").findAll(body).count())
        assertFalse(body.contains("if watch") || body.contains("watch.violations"))
    }
}
