package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Optional destination hashes for the Android FCM bridge, loaded from a
 * private `PushBridgeConfig.json` asset bundled only in local/private builds.
 */
object FcmBridgeHashes {
    private const val TAG = "FcmBridgeHashes"
    private const val CONFIG_ASSET = "PushBridgeConfig.json"

    private object Key {
        const val REGISTRATION_HEX = "FCMRegistrationDestinationHash"
        const val RELAY_HEX = "FCMRelayDestinationHash"
    }

    @Volatile
    private var cachedConfig: Map<String, String>? = null

    fun registrationHex(context: Context): String? =
        validatedHex(config(context)[Key.REGISTRATION_HEX])

    fun relayHex(context: Context): String? =
        validatedHex(config(context)[Key.RELAY_HEX])

    private fun config(context: Context): Map<String, String> {
        cachedConfig?.let { return it }

        synchronized(this) {
            cachedConfig?.let { return it }
            val loaded = loadConfig(context.applicationContext)
            cachedConfig = loaded
            return loaded
        }
    }

    private fun loadConfig(context: Context): Map<String, String> {
        val assetNames = runCatching { context.assets.list("")?.toSet().orEmpty() }
            .getOrDefault(emptySet())
        if (CONFIG_ASSET !in assetNames) {
            return emptyMap()
        }

        return try {
            context.assets.open(CONFIG_ASSET).bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                mapOf(
                    Key.REGISTRATION_HEX to json.optString(Key.REGISTRATION_HEX, ""),
                    Key.RELAY_HEX to json.optString(Key.RELAY_HEX, ""),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $CONFIG_ASSET", e)
            emptyMap()
        }
    }

    private fun validatedHex(raw: String?): String? {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.length != 32) return null
        if (!value.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return value
    }
}