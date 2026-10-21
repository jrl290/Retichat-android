package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.data.db.entity.InterfaceConfigEntity
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The public TCP backbones the stack uses when the user has configured no
 * interface of their own ("Default Public Endpoints" in Settings).
 *
 * At each start [selectFallbackEndpoints] tests them all at once and takes
 * the first [FALLBACK_ENDPOINT_COUNT] to accept a TCP connection, so a
 * backbone that is down costs nothing while others answer. Until 2026-10-21
 * they were tested one at a time, 5 s for each one down: with two of the
 * seven down, a cold start waited 5.5 s on average (up to 10.5 s) before the
 * stack began.
 *
 * A backbone that fails its test while another answers (the device was
 * online) is skipped at later starts for [SKIP_MS], then tested again; one
 * that answers is taken off the list. A start where none answers (offline)
 * marks none. Skipped backbones are tested only when the others can't make
 * up three. Same on iOS (DefaultEndpointManager.swift).
 */
object DefaultEndpointManager {

    private const val TAG = "DefaultEndpoint"

    /** Well-known public Reticulum TCP endpoints. Keep in sync with iOS. */
    private val ENDPOINTS = listOf(
        "rns.noderage.org" to 4242,
        "rns.michmesh.net" to 7822,
        "world.reticulum.is" to 3400,
        "rmap.world" to 4242,
        "vjs.hu" to 5858,
        "202.61.243.41" to 4965,
        "45.77.109.86" to 4965,
    )

    /** Mirror iOS: inject three invisible fallback backbones when needed. */
    const val FALLBACK_ENDPOINT_COUNT = 3

    // Match iOS: a default backbone only counts as usable if TCP connect
    // succeeds within the same 5-second budget the stack treats as real.
    private const val PROBE_TIMEOUT_MS = 5_000L

    /**
     * How long a backbone that failed its test is skipped (James, 2026-10-21:
     * "Add a blacklist also").
     */
    const val SKIP_MS = 24 * 60 * 60 * 1000L

    /** User-facing label for the invisible fallback backbone pool. */
    const val DEFAULT_NAME = "Default Public Endpoints"

    private val _inUse = MutableStateFlow<List<Pair<String, Int>>>(emptyList())

    /**
     * The backbones in the running stack's config, for Settings. Empty when
     * the stack uses the user's own interfaces or Default TCP is off.
     */
    val inUse: StateFlow<List<Pair<String, Int>>> = _inUse.asStateFlow()

    fun setInUse(endpoints: List<Pair<String, Int>>) {
        _inUse.value = endpoints
    }

    /** Runs the tests still going after the pick, and records their outcome. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The backbones for this start: the first three to answer. */
    suspend fun selectFallbackEndpoints(context: Context): List<Pair<String, Int>> {
        val now = System.currentTimeMillis()
        val failedAt = decodeSkipList(UserPreferences.backboneSkipList(context))
        val skipped = ENDPOINTS.filter { isSkipped(failedAt, it, now) }
        val picked = pick(
            order = ENDPOINTS.shuffled(),
            failedAt = failedAt,
            now = now,
            timeoutMs = PROBE_TIMEOUT_MS,
            background = background,
            probe = { probeConnectability(it, PROBE_TIMEOUT_MS.toInt()) },
            record = { next ->
                UserPreferences.setBackboneSkipList(context, encodeSkipList(next))
                if (next.keys != failedAt.keys) {
                    Log.i(TAG, "Skipped for 24 h after a failed test: ${next.keys.sorted().ifEmpty { listOf("none") }.joinToString()}")
                }
            },
        )
        Log.i(
            TAG,
            "Picked ${picked.joinToString { key(it) }} in ${System.currentTimeMillis() - now} ms" +
                if (skipped.isEmpty()) "" else "; skipped (failed before): ${skipped.joinToString { key(it) }}",
        )
        return picked
    }

    /**
     * Tests [order]'s backbones at once and returns the first
     * [FALLBACK_ENDPOINT_COUNT] to answer, in the order they answered. Those
     * [failedAt] marks within [SKIP_MS] are tested only once the rest can't
     * make up three. Fewer answering are padded in [order], skipped ones
     * last, so the stack has backbones to reconnect to once the network is
     * back. Tests still running carry on in [background]; when all are done,
     * [record] gets the updated skip list.
     */
    internal suspend fun pick(
        order: List<Pair<String, Int>>,
        failedAt: Map<String, Long>,
        now: Long,
        timeoutMs: Long,
        background: CoroutineScope,
        probe: suspend (Pair<String, Int>) -> Boolean,
        record: (Map<String, Long>) -> Unit,
    ): List<Pair<String, Int>> {
        val skipped = order.filter { isSkipped(failedAt, it, now) }
        val fresh = order.filterNot { it in skipped }
        val outcomes = Channel<Pair<Pair<String, Int>, Boolean>>(Channel.UNLIMITED)
        val tests = mutableListOf<Deferred<Pair<Pair<String, Int>, Boolean>>>()
        fun test(endpoints: List<Pair<String, Int>>) {
            for (endpoint in endpoints) {
                val answer = CompletableDeferred<Boolean>()
                background.launch { answer.complete(runCatching { probe(endpoint) }.getOrDefault(false)) }
                tests += background.async {
                    // The connect has its own timeout; a name lookup has none.
                    val ok = withTimeoutOrNull(timeoutMs) { answer.await() } ?: false
                    outcomes.send(endpoint to ok)
                    endpoint to ok
                }
            }
        }

        test(fresh)
        var pending = fresh.size
        var skippedTested = false
        val answered = mutableListOf<Pair<String, Int>>()
        while (answered.size < FALLBACK_ENDPOINT_COUNT) {
            if (!skippedTested && answered.size + pending < FALLBACK_ENDPOINT_COUNT) {
                skippedTested = true
                test(skipped)
                pending += skipped.size
            }
            if (pending == 0) break
            val (endpoint, ok) = outcomes.receive()
            pending--
            if (ok) answered += endpoint
        }

        val all = tests.toList()
        background.launch { record(updatedSkipList(failedAt, all.awaitAll(), now)) }
        return (answered + fresh + skipped).distinct().take(FALLBACK_ENDPOINT_COUNT)
    }

    /**
     * The skip list after a start's tests: expired entries dropped, and, if
     * any backbone answered, each that failed marked [now] and each that
     * answered cleared. With none answering the device was offline, which
     * says nothing about the backbones.
     */
    internal fun updatedSkipList(
        failedAt: Map<String, Long>,
        results: List<Pair<Pair<String, Int>, Boolean>>,
        now: Long,
    ): Map<String, Long> {
        val next = failedAt.filterValues { now - it in 0 until SKIP_MS }.toMutableMap()
        if (results.none { it.second }) return next
        for ((endpoint, ok) in results) {
            if (ok) next.remove(key(endpoint)) else next[key(endpoint)] = now
        }
        return next
    }

    internal fun isSkipped(failedAt: Map<String, Long>, endpoint: Pair<String, Int>, now: Long): Boolean =
        failedAt[key(endpoint)]?.let { now - it in 0 until SKIP_MS } ?: false

    internal fun key(endpoint: Pair<String, Int>): String = "${endpoint.first}:${endpoint.second}"

    /** "host:port=epochMs" entries, comma-separated, as stored in the preferences. */
    internal fun encodeSkipList(failedAt: Map<String, Long>): String =
        failedAt.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    internal fun decodeSkipList(text: String): Map<String, Long> =
        text.split(',').mapNotNull { entry ->
            val at = entry.substringAfterLast('=', "").toLongOrNull() ?: return@mapNotNull null
            entry.substringBeforeLast('=') to at
        }.toMap()

    /** Build synthetic fallback interfaces. Never persisted to Room. */
    fun fallbackInterfaceConfigs(endpoints: List<Pair<String, Int>>): List<InterfaceConfigEntity> =
        endpoints.mapIndexed { idx, (host, port) ->
            InterfaceConfigEntity(
                id = -(idx + 1L),
                name = "DefaultBackbone${idx + 1}",
                type = "TCPClientInterface",
                enabled = true,
                configJson = """{"target_host":"$host","target_port":"$port"}""",
            )
        }

    private fun probeConnectability(endpoint: Pair<String, Int>, timeoutMs: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.first, endpoint.second), timeoutMs)
                true
            }
        }.getOrDefault(false)
}
