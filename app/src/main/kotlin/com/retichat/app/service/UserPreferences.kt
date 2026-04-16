package com.retichat.app.service

import android.content.Context

/**
 * Shared preferences helper for user-facing settings that multiple
 * components need to read (service, worker, UI).
 */
object UserPreferences {
    const val PREF_NAME = "user_prefs"
    const val PREF_KEY_DISPLAY_NAME = "display_name"
    const val PREF_KEY_DEFAULT_TCP = "default_tcp_enabled"
    const val PREF_KEY_DROP_ANNOUNCES = "drop_announces"

    private const val DEFAULT_DISPLAY_NAME = "Retichat"

    /**
     * Returns the user's chosen display name, or "Retichat" if unset/blank.
     */
    fun getDisplayName(context: Context): String {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY_DISPLAY_NAME, null)
        return if (name.isNullOrBlank()) DEFAULT_DISPLAY_NAME else name.trim()
    }

    /** Whether the built-in default TCP endpoint is enabled (defaults to true). */
    fun isDefaultTcpEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_DEFAULT_TCP, true)

    fun setDefaultTcpEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY_DEFAULT_TCP, enabled)
            .apply()
    }

    /** Whether inbound announces are dropped at the transport layer (default: true). */
    fun isDropAnnouncesEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_DROP_ANNOUNCES, true)

    fun setDropAnnouncesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY_DROP_ANNOUNCES, enabled)
            .apply()
    }
}
