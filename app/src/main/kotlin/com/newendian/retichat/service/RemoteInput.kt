package com.newendian.retichat.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import java.security.MessageDigest

// What the network hands this device is shaped by whoever sent it: anyone who
// knows an address (a distro's, a channel's, this device's) can send a message
// that decrypts to anything. Handling one must cost at most that one message,
// never the rest of the batch it came in, and never the process.
//
// Until 2026-10-09 a distro blob whose unwrap JSON lacked a field, or carried
// one of the wrong type (a msgpack NaN timestamp arrives as JSON null), threw
// out of RfedDistroClient.handleBlob, out of the pull loop and out of an
// unguarded launch: a stranger could crash every Android device of a distro
// as often as they liked, and each crash lost the rest of that pull page.

/**
 * One page of an rfed `/rfed/pull` response (`[[routing hash, blob], ...]`),
 * handled blob by blob.
 *
 * RFed drains a page as it serves it (RFed-rust deferred_queue.rs
 * `drain_matching_batch` for the distro, `drain_channel_batch` for a channel):
 * the client acknowledges nothing, and no blob is served twice. So a blob that
 * cannot be handled is logged and dropped. There is no later copy to wait for,
 * and none to get stuck on, and the blobs after it in the page are still
 * handled.
 */
internal object PullPage {

    /**
     * Hand each pair of [pairs] to [handle], in order. An Exception from one is
     * passed to [onDropped] with that blob's id ([blobId]) and the page goes
     * on. Returns how many were handled without one. Cancellation is not a bad
     * blob and propagates; an Error (out of memory, say) is not caught here.
     */
    suspend fun handleEach(
        pairs: List<Pair<ByteArray, ByteArray>>,
        handle: suspend (routingHash: ByteArray, blob: ByteArray) -> Unit,
        onDropped: (blobId: String, error: Exception) -> Unit,
    ): Int {
        var handled = 0
        for ((routingHash, blob) in pairs) {
            try {
                handle(routingHash, blob)
                handled++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDropped(blobId(blob), e)
            }
        }
        return handled
    }

    /** The first 8 bytes of the blob's SHA-256, in hex: its name in the log. */
    fun blobId(blob: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(blob)
            .take(8).joinToString("") { "%02x".format(it) }
}

/**
 * For a coroutine scope whose coroutines handle what the network sent: an
 * Exception that escapes one of them is logged under [tag] and ends that
 * coroutine only. Without a handler it reaches the thread's uncaught-exception
 * handler, which on Android kills the process. An Error is rethrown and still
 * takes that path.
 */
internal fun logEscapedExceptions(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, t ->
        if (t !is Exception) throw t
        Log.e(tag, "coroutine failed; dropped, the process carries on", t)
    }
