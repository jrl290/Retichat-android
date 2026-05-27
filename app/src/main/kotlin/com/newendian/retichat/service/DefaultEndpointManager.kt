package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.data.db.entity.InterfaceConfigEntity
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages a shuffled list of well-known public TCP endpoints.
 *
 * When the user has **no** interfaces configured, three invisible fallback
 * [TCPClientInterface] backbones are injected into the Reticulum config.
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

    /** Mirror iOS: inject three invisible fallback backbones when needed. */
    const val FALLBACK_ENDPOINT_COUNT = 3

    // Match iOS: a default backbone only counts as usable if TCP connect
    // succeeds within the same 5-second budget the stack treats as real.
    private const val PROBE_TIMEOUT_MS = 5_000

    /** Index into [shuffled]. */
    @Volatile
    private var index = 0

    /** User-facing label for the invisible fallback backbone pool. */
    const val DEFAULT_NAME = "Default Public Endpoints"

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

    /** Current shuffled fallback endpoints for invisible startup backbones. */
    fun fallbackEndpoints(): List<Pair<String, Int>> =
        shuffled.take(FALLBACK_ENDPOINT_COUNT)

    /**
     * Probe the shuffled public endpoint list in serial order and return up to
     * [FALLBACK_ENDPOINT_COUNT] endpoints that actually accept TCP.
     *
     * If fewer than three probes succeed after the full list is exhausted,
     * pad from the same shuffled order so reconnect has additional candidates
     * once the network comes back.
     */
    suspend fun selectFallbackEndpoints(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val shuffledEndpoints = shuffled.toList()
        val selected = mutableListOf<Pair<String, Int>>()

        for (endpoint in shuffledEndpoints) {
            if (probeConnectability(endpoint, PROBE_TIMEOUT_MS)) {
                selected += endpoint
                if (selected.size == FALLBACK_ENDPOINT_COUNT) {
                    break
                }
            }
        }

        if (selected.size < FALLBACK_ENDPOINT_COUNT) {
            for (endpoint in shuffledEndpoints) {
                if (selected.none { it == endpoint }) {
                    selected += endpoint
                    if (selected.size == FALLBACK_ENDPOINT_COUNT) {
                        break
                    }
                }
            }
        }

        selected
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

    /** Build synthetic fallback interfaces. Never persisted to Room. */
    fun fallbackInterfaceConfigs(endpoints: List<Pair<String, Int>> = fallbackEndpoints()): List<InterfaceConfigEntity> =
        endpoints.mapIndexed { idx, (host, port) ->
            InterfaceConfigEntity(
                id = -(idx + 1L),
                name = "DefaultBackbone${idx + 1}",
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

    private fun probeConnectability(endpoint: Pair<String, Int>, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.first, endpoint.second), timeoutMs)
                true
            }
        }.getOrDefault(false)
}
