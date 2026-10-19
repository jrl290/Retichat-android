package com.newendian.retichat.service

import com.newendian.retichat.bridge.MiniJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The 2026-10-09 crash: one malformed distro blob in a `/rfed/pull` page threw
 * out of RfedDistroClient.handleBlob, out of the pull loop and out of an
 * unguarded launch, killing the app, and RFed had already drained the page,
 * so the blobs after it were lost. A stranger who knows a distro's address
 * could do it to every Android device of the distro, again and again.
 *
 * The handler below makes handleBlob's two required reads, `getString
 * ("source_hash")` and `getDouble("timestamp")`, on unwrap JSON. org.json is
 * a stub in JVM tests, so the JSON is read with MiniJson; a JSON null
 * timestamp is what a msgpack NaN becomes (serde_json writes non-finite
 * floats as null), and reading it throws as getDouble throws.
 */
class PullPageTest {

    private val distroHash = ByteArray(16) { 0x11 }

    private fun unwrapJson(source: String, timestamp: String) =
        """{"source_hash":"$source","timestamp":$timestamp,"title":"","content":"hi"}"""

    private val good1 = unwrapJson("a47157bd61e038c5a7d32104377877c5", "1790000000.5").toByteArray()
    private val malformed = unwrapJson("d8939c64bc3b5589e5119035addb7486", "null").toByteArray()
    private val good2 = unwrapJson("0123456789abcdef0123456789abcdef", "1790000001.25").toByteArray()

    /** msgpack `[[[bin hash, bin blob], ...], more]`, as RFed encode_pull_response writes it. */
    private fun pullResponse(blobs: List<ByteArray>, more: Boolean): ByteArray {
        fun bin(b: ByteArray): ByteArray {
            require(b.size <= 0xff)
            return byteArrayOf(0xc4.toByte(), b.size.toByte()) + b
        }
        var out = byteArrayOf(0x92.toByte(), (0x90 + blobs.size).toByte())
        for (blob in blobs) out += byteArrayOf(0x92.toByte()) + bin(distroHash) + bin(blob)
        return out + byteArrayOf(if (more) 0xc3.toByte() else 0xc2.toByte())
    }

    /** handleBlob's required reads: a missing or wrong-typed field throws. */
    private fun readRequired(blob: ByteArray): String {
        @Suppress("UNCHECKED_CAST")
        val o = MiniJson.parse(String(blob, Charsets.UTF_8)) as Map<String, Any?>
        val source = o["source_hash"] as String
        val timestamp = (o["timestamp"] as Number).toDouble()
        return "$source@$timestamp"
    }

    @Test
    fun aMalformedBlobCostsItselfOnlyAndThePageGoesOn() = runBlocking {
        val (pairs, more) = DistroCodec.decodePullResponse(pullResponse(listOf(good1, malformed, good2), more = false))!!
        assertEquals(3, pairs.size)
        assertFalse(more)

        val handled = mutableListOf<String>()
        val dropped = mutableListOf<Pair<String, Exception>>()
        val count = PullPage.handleEach(pairs, { hash, blob ->
            assertTrue(hash.contentEquals(distroHash))
            handled += readRequired(blob)
        }) { id, e -> dropped += id to e }

        assertEquals(2, count)
        assertEquals(
            listOf("a47157bd61e038c5a7d32104377877c5@1.7900000005E9", "0123456789abcdef0123456789abcdef@1.79000000125E9"),
            handled,
        )
        assertEquals(1, dropped.size)
        assertEquals(PullPage.blobId(malformed), dropped[0].first)
        assertEquals(16, dropped[0].first.length)
    }

    @Test
    fun cancellationIsNotABadBlob() = runBlocking {
        val pairs = listOf(good1, good2).map { distroHash to it }
        val handled = mutableListOf<ByteArray>()
        try {
            PullPage.handleEach(pairs, { _, blob ->
                if (blob === good1) throw CancellationException("stack stopping")
                handled += blob
            }) { _, _ -> fail("a cancellation was reported as a dropped blob") }
            fail("the cancellation was swallowed")
        } catch (_: CancellationException) {
        }
        assertTrue(handled.isEmpty())
    }

    @Test
    fun blobIdIsStableAndDistinguishesBlobs() {
        assertEquals(PullPage.blobId(good1), PullPage.blobId(good1.copyOf()))
        assertFalse(PullPage.blobId(good1) == PullPage.blobId(good2))
    }

    // ── Wiring: checked in the source, as the other contract tests here are ──

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    private fun between(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue("$from not found", start >= 0)
        val end = source.indexOf(to, start + from.length)
        assertTrue("$to not found after $from", end > start)
        return source.substring(start, end)
    }

    @Test
    fun bothPullLoopsHandleTheirPageBlobByBlob() {
        val distroPull = between(src("service/RfedDistroClient.kt"), "suspend fun pull(", "suspend fun handleBlob(")
        assertTrue(distroPull.contains("PullPage.handleEach(pairs, { _, blob -> handleBlob(context, blob) })"))
        assertFalse(distroPull.contains("for ((_, blob) in pairs)"))
        val channelPull = between(src("service/RfedChannelClient.kt"), "suspend fun pullDeferred(", "// ── Inbound blob dispatch")
        assertTrue(channelPull.contains("PullPage.handleEach(pairs, { channelHashBytes, blob -> dispatchBlob(channelHashBytes.toHex(), blob) })"))
        assertFalse(channelPull.contains("for ((channelHashBytes, blob) in pairs)"))
    }

    @Test
    fun theScopesThatHandleReceivedContentLogWhatEscapes() {
        for ((path, decl) in listOf(
            "service/RfedDistroClient.kt" to "private val scope = CoroutineScope(",
            "service/RfedLink.kt" to "private val scope = CoroutineScope(",
            // The repository's and the channel client's scope.
            "RetichatApp.kt" to "val applicationScope = CoroutineScope(",
        )) {
            val line = src(path).substringAfter(decl, "").substringBefore("\n")
            assertTrue("$path: $decl has no handler", line.contains("logEscapedExceptions(TAG)"))
        }
        // rfed.link's ACTIVE edge: both stream opens (and the pull after the
        // propagation one) and the channel pull, each guarded, a cancellation
        // passed on.
        val bind = between(src("service/RfedLink.kt"), "private suspend fun bind(", "    /**")
        assertTrue(bind.contains("PropagationStream.onLinkActive(app, node)"))
        assertTrue(bind.contains("sendChannelStreamConfigForNode(node)"))
        assertTrue(bind.contains("pullDeferredForNode(node)"))
        assertEquals(3, Regex("""catch \(e: CancellationException\) \{\s*throw e""").findAll(bind).count())
        assertEquals(3, Regex("""catch \(e: Exception\) \{""").findAll(bind).count())
        assertTrue(src("service/PropagationStream.kt").contains("suspend fun onLinkActive(app: RetichatApp, nodeHex: String) {\n        val current = session ?: return\n        if (current.nodeHex != nodeHex.lowercase()) return\n        sendOpen(app, current)"))
    }
}
