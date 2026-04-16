package com.retichat.app.service

import android.util.Log
import com.retichat.app.data.db.entity.InterfaceConfigEntity

/**
 * Manages a shuffled list of well-known public TCP endpoints.
 *
 * When the user has **no** interfaces configured, a default
 * [TCPClientInterface] is injected into the Reticulum config.
 * If the current endpoint is unreachable, calling [rotateEndpoint]
 * advances to the next one in the shuffled list (wrapping around).
 */
object DefaultEndpointManager {

    private const val TAG = "DefaultEndpoint"

    /** Well-known public Reticulum TCP endpoints. */
    private val ENDPOINTS = listOf(
        "rns.noderage.org" to 4242,
        "rns.michmesh.net" to 7822,
        "world.reticulum.is" to 3400,
        "rmap.world" to 4242,
        "vjs.hu" to 5858,
        "202.61.243.41" to 4965,
        "45.77.109.86" to 4965,
    )

    /** Shuffled copy; created once per process lifetime. */
    private val shuffled = ENDPOINTS.shuffled().toMutableList()

    /** Index into [shuffled]. */
    @Volatile
    private var index = 0

    /** The name used for the default interface entity. */
    const val DEFAULT_NAME = "Default Public Endpoint"

    /** Current endpoint as host:port. */
    val current: Pair<String, Int>
        get() = shuffled[index % shuffled.size]

    /** Move to the next endpoint. Returns the new endpoint. */
    fun rotateEndpoint(): Pair<String, Int> {
        index = (index + 1) % shuffled.size
        val ep = shuffled[index]
        Log.i(TAG, "Rotated to endpoint: ${ep.first}:${ep.second}")
        return ep
    }

    /**
     * Build a synthetic [InterfaceConfigEntity] for the current
     * default endpoint. This is **not** persisted to Room — it only
     * exists in memory so the config generator can write it.
     */
    fun asInterfaceConfig(): InterfaceConfigEntity {
        val (host, port) = current
        return InterfaceConfigEntity(
            id = -1,
            name = DEFAULT_NAME,
            type = "TCPClientInterface",
            enabled = true,
            configJson = """{"target_host":"$host","target_port":"$port"}""",
        )
    }

    /** Re-shuffle for a fresh run. */
    fun reshuffle() {
        shuffled.clear()
        shuffled.addAll(ENDPOINTS.shuffled())
        index = 0
    }
}
