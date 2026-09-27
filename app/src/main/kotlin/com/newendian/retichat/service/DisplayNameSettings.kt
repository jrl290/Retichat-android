package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saving the three display names (LXMF-rust/DISPLAY_NAMES.md §6).
 *
 * A name is cleaned by the one Rust implementation when the user commits it,
 * so the field then shows exactly what goes out, and it takes effect at once
 * through the router setters: no stack restart. With the stack down the
 * saved value is what the next bootstrap registers
 * ([StackRuntime] sets the announce name before the first announce).
 */
object DisplayNameSettings {
    private const val TAG = "DisplayNames"

    enum class Kind { ANNOUNCE, MESSAGE, CHANNEL }

    /** [raw] cleaned (§3; announce names also drop "Anonymous Peer"), or "" for no name. */
    fun clean(raw: String, kind: Kind): String {
        if (!RetichatBridge.isLoaded) return raw.trim()
        return RetichatBridge.displayNameClean(raw, announce = kind == Kind.ANNOUNCE).orEmpty()
    }

    fun stored(context: Context, kind: Kind): String = when (kind) {
        Kind.ANNOUNCE -> UserPreferences.getAnnounceDisplayName(context)
        Kind.MESSAGE -> UserPreferences.getMessageDisplayName(context)
        Kind.CHANNEL -> UserPreferences.getChannelDisplayName(context)
    }

    /**
     * Clean and store [raw] as the [kind] name and apply it to the running
     * stack. Returns the stored (cleaned) value for the field to show. Runs on
     * IO: the router setters take the router's lock.
     */
    suspend fun save(context: Context, kind: Kind, raw: String): String = withContext(Dispatchers.IO) {
        val cleaned = clean(raw, kind)
        if (cleaned == stored(context, kind)) return@withContext cleaned
        when (kind) {
            Kind.ANNOUNCE -> UserPreferences.setAnnounceDisplayName(context, cleaned)
            Kind.MESSAGE -> UserPreferences.setMessageDisplayName(context, cleaned)
            Kind.CHANNEL -> UserPreferences.setChannelDisplayName(context, cleaned)
        }
        apply(context, kind, cleaned)
        cleaned
    }

    private fun apply(context: Context, kind: Kind, name: String) {
        val router = StackRuntime.routerHandle
        when (kind) {
            Kind.MESSAGE -> if (router != 0L && !RetichatBridge.routerSetMessageDisplayName(router, name)) {
                Log.e(TAG, "message display name not applied: ${RetichatBridge.lastError()}")
            }
            Kind.ANNOUNCE -> {
                if (router != 0L && !RetichatBridge.routerSetAnnounceDisplayName(router, name)) {
                    Log.e(TAG, "announce display name not applied: ${RetichatBridge.lastError()}")
                }
                // The distro's announce is pre-signed and replayed by RFed:
                // hand it the new one (§2.2, every delivery destination).
                RfedDistroClient.republishAnnounce(context)
            }
            // Read by RfedChannelClient at each post (§4.2).
            Kind.CHANNEL -> Unit
        }
    }
}
