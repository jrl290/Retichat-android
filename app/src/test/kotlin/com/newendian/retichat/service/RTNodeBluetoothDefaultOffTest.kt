package com.newendian.retichat.service

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nearby RTNode (Bluetooth) is off by default (James, 2026-09-29): on a
 * fresh install, where nothing has written the preference, it reads false,
 * so bootstrap never starts Bluetooth and nothing asks for the permission.
 * Runs UserPreferences itself against in-memory preferences.
 */
class RTNodeBluetoothDefaultOffTest {

    /** SharedPreferences in a map, as a fresh install has them: empty. */
    private class MemoryPrefs : SharedPreferences {
        val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? =
            if (values.containsKey(key)) values[key] as String? else defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            if (values.containsKey(key)) values[key] as MutableSet<String>? else defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            val removed = HashSet<String>()
            var clear = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, value: MutableSet<String>?) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                removed.forEach { values.remove(it) }
                values.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    /** A Context whose only use is handing out [prefs]. */
    private class PrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        val names = mutableListOf<String?>()
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            names += name
            return prefs
        }
    }

    @Test
    fun offOnAFreshInstall() {
        val prefs = MemoryPrefs()
        val context = PrefsContext(prefs)
        assertFalse(UserPreferences.isRtnodeBluetoothEnabled(context))
        assertEquals(listOf<String?>(UserPreferences.PREF_NAME), context.names)
        assertTrue("reading it writes nothing", prefs.values.isEmpty())
    }

    @Test
    fun onOnlyWhileTurnedOn() {
        val prefs = MemoryPrefs()
        val context = PrefsContext(prefs)
        UserPreferences.setRtnodeBluetoothEnabled(context, true)
        assertTrue(UserPreferences.isRtnodeBluetoothEnabled(context))
        assertEquals(true, prefs.values[UserPreferences.PREF_KEY_RTNODE_BLUETOOTH])
        UserPreferences.setRtnodeBluetoothEnabled(context, false)
        assertFalse(UserPreferences.isRtnodeBluetoothEnabled(context))
    }
}
