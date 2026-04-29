package com.retichat.app.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared preferences helper for user-facing settings that multiple
 * components need to read (service, worker, UI).
 *
 * Mirrors the iOS `UserPreferences.swift` keys 1:1 so behaviour is
 * identical across platforms.
 */
object UserPreferences {
    const val PREF_NAME = "user_prefs"

    // ── Existing keys ──────────────────────────────────────────────────
    const val PREF_KEY_DISPLAY_NAME = "display_name"
    const val PREF_KEY_DEFAULT_TCP = "default_tcp_enabled"
    const val PREF_KEY_DROP_ANNOUNCES = "drop_announces"

    // ── New keys (iOS parity) ──────────────────────────────────────────
    const val PREF_KEY_CHANNEL_DISPLAY_NAME = "channel_display_name"
    /** rfed.notify destination hash (32-char hex), empty when push disabled. */
    const val PREF_KEY_RFED_NOTIFY_HASH = "rfed_notify_hash"
    /** Last-seen FCM registration token (variable length). */
    const val PREF_KEY_FCM_DEVICE_TOKEN = "fcm_device_token"
    /** Optional override of the LXMF propagation node hash (32-char hex). */
    const val PREF_KEY_LXMF_PROPAGATION_HASH = "lxmf_propagation_hash"
    /** RFed node identity hash (32-char hex) used to derive rfed.{channel,notify,delivery}. */
    const val PREF_KEY_RFED_NODE_IDENTITY_HASH = "rfed_node_identity_hash"
    /** Optional explicit lxmf.propagation override derived from RFed node. */
    const val PREF_KEY_RFED_LXMF_PROP_OVERRIDE = "rfed_lxmf_prop_override"
    /** Reject inbound messages from non-allowlisted contacts (default true). */
    const val PREF_KEY_FILTER_STRANGERS = "filter_strangers"
    /** Comma-separated chat IDs with notifications muted. */
    const val PREF_KEY_MUTED_CHAT_IDS = "muted_chat_ids"
    /** Comma-separated channel hashes opted-in to notifications (default off). */
    const val PREF_KEY_CHANNEL_NOTIFICATIONS_ON = "channel_notifications_on"
    const val PREF_KEY_CHANNEL_PUSH_ENABLED     = "channel_push_enabled"

    private const val DEFAULT_DISPLAY_NAME = "Retichat"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Display name ───────────────────────────────────────────────────

    /** Returns the user's chosen display name, or "Retichat" if unset/blank. */
    fun getDisplayName(context: Context): String {
        val name = prefs(context).getString(PREF_KEY_DISPLAY_NAME, null)
        return if (name.isNullOrBlank()) DEFAULT_DISPLAY_NAME else name.trim()
    }

    fun setDisplayName(context: Context, name: String) {
        prefs(context).edit().putString(PREF_KEY_DISPLAY_NAME, name.trim()).apply()
    }

    /** Optional override used only on outgoing channel messages. Empty = fall back to displayName. */
    fun getChannelDisplayName(context: Context): String =
        prefs(context).getString(PREF_KEY_CHANNEL_DISPLAY_NAME, "").orEmpty().trim()

    fun setChannelDisplayName(context: Context, name: String) {
        prefs(context).edit().putString(PREF_KEY_CHANNEL_DISPLAY_NAME, name.trim()).apply()
    }

    // ── Default TCP endpoint ──────────────────────────────────────────

    fun isDefaultTcpEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_KEY_DEFAULT_TCP, true)

    fun setDefaultTcpEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEY_DEFAULT_TCP, enabled).apply()
    }

    // ── Drop announces ────────────────────────────────────────────────

    fun isDropAnnouncesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_KEY_DROP_ANNOUNCES, true)

    fun setDropAnnouncesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEY_DROP_ANNOUNCES, enabled).apply()
    }

    // ── Filter strangers ──────────────────────────────────────────────

    fun isFilterStrangersEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_KEY_FILTER_STRANGERS, true)

    fun setFilterStrangersEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEY_FILTER_STRANGERS, enabled).apply()
    }

    // ── RFed node config ──────────────────────────────────────────────

    fun getRfedNodeIdentityHash(context: Context): String =
        prefs(context).getString(PREF_KEY_RFED_NODE_IDENTITY_HASH, "").orEmpty().trim().lowercase()

    fun setRfedNodeIdentityHash(context: Context, hex: String) {
        prefs(context).edit()
            .putString(PREF_KEY_RFED_NODE_IDENTITY_HASH, hex.trim().lowercase())
            .apply()
    }

    fun getRfedNotifyHash(context: Context): String =
        prefs(context).getString(PREF_KEY_RFED_NOTIFY_HASH, "").orEmpty().trim().lowercase()

    fun setRfedNotifyHash(context: Context, hex: String) {
        prefs(context).edit()
            .putString(PREF_KEY_RFED_NOTIFY_HASH, hex.trim().lowercase())
            .apply()
    }

    fun getRfedLxmfPropOverride(context: Context): String =
        prefs(context).getString(PREF_KEY_RFED_LXMF_PROP_OVERRIDE, "").orEmpty().trim().lowercase()

    fun setRfedLxmfPropOverride(context: Context, hex: String) {
        prefs(context).edit()
            .putString(PREF_KEY_RFED_LXMF_PROP_OVERRIDE, hex.trim().lowercase())
            .apply()
    }

    // ── LXMF propagation node ─────────────────────────────────────────

    fun getLxmfPropagationHash(context: Context): String =
        prefs(context).getString(PREF_KEY_LXMF_PROPAGATION_HASH, "").orEmpty().trim().lowercase()

    fun setLxmfPropagationHash(context: Context, hex: String) {
        prefs(context).edit()
            .putString(PREF_KEY_LXMF_PROPAGATION_HASH, hex.trim().lowercase())
            .apply()
    }

    // ── FCM token ─────────────────────────────────────────────────────

    fun getFcmDeviceToken(context: Context): String =
        prefs(context).getString(PREF_KEY_FCM_DEVICE_TOKEN, "").orEmpty()

    fun setFcmDeviceToken(context: Context, token: String) {
        prefs(context).edit().putString(PREF_KEY_FCM_DEVICE_TOKEN, token).apply()
    }

    // ── Muted chats / channel notification opt-ins ────────────────────

    private fun getCsvSet(context: Context, key: String): Set<String> =
        prefs(context).getString(key, "").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun putCsvSet(context: Context, key: String, values: Set<String>) {
        prefs(context).edit()
            .putString(key, values.joinToString(",") { it.trim() })
            .apply()
    }

    fun getMutedChatIds(context: Context): Set<String> =
        getCsvSet(context, PREF_KEY_MUTED_CHAT_IDS)

    fun isChatMuted(context: Context, chatId: String): Boolean =
        getMutedChatIds(context).contains(chatId)

    fun setChatMuted(context: Context, chatId: String, muted: Boolean) {
        val current = getMutedChatIds(context).toMutableSet()
        if (muted) current.add(chatId) else current.remove(chatId)
        putCsvSet(context, PREF_KEY_MUTED_CHAT_IDS, current)
    }

    fun getChannelNotificationsOn(context: Context): Set<String> =
        getCsvSet(context, PREF_KEY_CHANNEL_NOTIFICATIONS_ON)

    fun isChannelNotificationsEnabled(context: Context, channelHashHex: String): Boolean =
        getChannelNotificationsOn(context).contains(channelHashHex.lowercase())

    fun setChannelNotificationsEnabled(context: Context, channelHashHex: String, enabled: Boolean) {
        val current = getChannelNotificationsOn(context).toMutableSet()
        val key = channelHashHex.lowercase()
        if (enabled) current.add(key) else current.remove(key)
        putCsvSet(context, PREF_KEY_CHANNEL_NOTIFICATIONS_ON, current)
    }

    // ---- Per-channel push wakeup (rfed.notify registration) ----
    //
    // When push is enabled the device registers with the rfed.notify destination so a
    // silent push is fired for every new channel message (waking the app to pull it).
    // Push is opt-in: a freshly joined channel has no entry until the user toggles it on.

    fun getChannelPushEnabled(context: Context): Set<String> =
        getCsvSet(context, PREF_KEY_CHANNEL_PUSH_ENABLED)

    fun isChannelPushEnabled(context: Context, channelHashHex: String): Boolean =
        getChannelPushEnabled(context).contains(channelHashHex.lowercase())

    fun setChannelPushEnabled(context: Context, channelHashHex: String, enabled: Boolean) {
        val current = getChannelPushEnabled(context).toMutableSet()
        val key = channelHashHex.lowercase()
        if (enabled) current.add(key) else current.remove(key)
        putCsvSet(context, PREF_KEY_CHANNEL_PUSH_ENABLED, current)
    }
}
