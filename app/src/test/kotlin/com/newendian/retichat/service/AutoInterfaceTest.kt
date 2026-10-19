package com.newendian.retichat.service

import com.newendian.retichat.service.RTNodeBluetoothDefaultOffTest.MemoryPrefs
import com.newendian.retichat.service.RTNodeBluetoothDefaultOffTest.PrefsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The built-in AutoInterface (James, 2026-10-19): Settings' Local network
 * switch, off by default; on, the config the stack starts from has an
 * AutoInterface with Reticulum's defaults, and the stack holds the Wi-Fi
 * multicast lock while it runs. The discovery itself is Reticulum-rust's
 * auto_interface.rs; the emulator and LAN runs show it.
 */
class AutoInterfaceTest {

    private fun config(autoInterface: Boolean): String {
        val dir = Files.createTempDirectory("reticulum").toFile()
        try {
            writeReticulumConfig(dir, emptyList(), autoInterface)
            return File(dir, "config").readText()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun offOnAFreshInstall() {
        val prefs = MemoryPrefs()
        val context = PrefsContext(prefs)
        assertFalse(UserPreferences.isAutoInterfaceEnabled(context))
        assertTrue("reading it writes nothing", prefs.values.isEmpty())
        UserPreferences.setAutoInterfaceEnabled(context, true)
        assertTrue(UserPreferences.isAutoInterfaceEnabled(context))
        assertEquals(true, prefs.values[UserPreferences.PREF_KEY_AUTO_INTERFACE])
        UserPreferences.setAutoInterfaceEnabled(context, false)
        assertFalse(UserPreferences.isAutoInterfaceEnabled(context))
    }

    @Test
    fun theConfigHasTheAutoInterfaceOnlyWhenOn() {
        assertFalse(config(false).contains("AutoInterface"))
        val on = config(true)
        val section = on.substringAfter("[interfaces]")
        assertTrue(section, section.contains("  [[Local network]]\n    type = AutoInterface\n    enabled = true\n"))
        // Reticulum's defaults, so it finds Sideband, MeshChat and rnsd.
        for (key in listOf("group_id", "discovery_port", "data_port", "devices")) {
            assertFalse(key, section.contains(key))
        }
    }

    @Test
    fun theStackReadsTheSwitchAndHoldsTheMulticastLockWhileItRuns() {
        val stack = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()
        val bootstrap = stack.substringAfter("private suspend fun bootstrap(").substringBefore("private fun shutdownNow(")
        val acquire = bootstrap.indexOf("if (autoInterface) LocalNetwork.acquire(app)")
        val write = bootstrap.indexOf("writeReticulumConfig(configDir, interfaces, autoInterface)")
        val init = bootstrap.indexOf("RetichatBridge.init(configDir.absolutePath)")
        assertTrue("lock, then config, then start", acquire in 0 until write && write < init)
        val stop = stack.substringAfter("private fun shutdownNow(").substringBefore("suspend fun restart(")
        assertTrue(stop.indexOf("LocalNetwork.release()") > stop.indexOf("RetichatBridge.shutdown()"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE"))
        assertTrue(manifest.contains(LocalNetwork.PERMISSION))
    }
}
