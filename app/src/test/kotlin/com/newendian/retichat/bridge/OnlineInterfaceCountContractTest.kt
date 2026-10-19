package com.newendian.retichat.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings' "N interface(s) active" is the interfaces online now (L83,
 * 2026-10-18). It was the enabled interface rows counted once at start, so it
 * said 0 with RTNode Bluetooth up and kept a backbone that had dropped. The .so
 * is not loaded in a JVM test, so the JNI pair is pinned to its Rust symbols and
 * the callback to the descriptor Rust calls (a drifted one is a silent no-op on
 * a Rust thread), and the stack's wiring is checked in the source; the count
 * itself is interface_count.rs's test, and the emulator run shows it.
 */
class OnlineInterfaceCountContractTest {
    private val bridge = File("src/main/kotlin/com/newendian/retichat/bridge/RetichatBridge.kt").readText()
    private val rust = File("../rust/retichat-jni/src/lib.rs").readText()
    private val stack = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()

    @Test
    fun theJniPairHasItsRustSymbolsAndTheCallbackItsDescriptor() {
        assertTrue(bridge.contains("private external fun nativeOnlineInterfaceCountRegister(callback: OnlineInterfaceCountCallback): Int"))
        assertTrue(bridge.contains("private external fun nativeOnlineInterfaceCountUnregister(): Int"))
        for (name in listOf("nativeOnlineInterfaceCountRegister", "nativeOnlineInterfaceCountUnregister")) {
            assertTrue(name, rust.contains("fn Java_com_newendian_retichat_bridge_RetichatBridge_$name("))
        }
        assertTrue(bridge.contains("fun onOnlineInterfaceCount(count: Int, autoPeers: Int)"))
        assertTrue(rust.contains("\"onOnlineInterfaceCount\", \"(II)V\""))
    }

    @Test
    fun theStackShowsTheOnlineCountFromStartToStop() {
        assertFalse("the enabled rows are not the count", stack.contains("interfaceCount = interfaces.size"))
        val ready = stack.indexOf("identityHashHex = hashHex,")
        val register = stack.indexOf("RetichatBridge.onlineInterfaceCountRegister { count, peers -> app.updateInterfaceCount(count, peers) }")
        assertTrue("registered after the state that would overwrite the count", ready in 0 until register)
        val stop = stack.substring(stack.indexOf("private fun shutdownNow("))
        val unregister = stop.indexOf("RetichatBridge.onlineInterfaceCountUnregister()")
        assertTrue("unregistered before the stop clears the state", unregister in 0 until stop.indexOf("app.updateServiceState()"))
    }
}
