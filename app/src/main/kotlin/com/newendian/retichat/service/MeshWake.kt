package com.newendian.retichat.service

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * This device as its own RFed Notify relay, so RFed can wake it over the
 * mesh as well as through FCM (James, 2026-10-23: "have rfed send the notify
 * ping directly to the requesting device as well. Then RTNode would be able
 * to push those notifications to wake Retichat at low power").
 *
 * Port of `Retichat-ios/Retichat/Services/MeshWake.swift`.
 *
 * RFed wakes a device through every relay it registered (RFed-spec
 * Notify.md). One the device hosts itself, `rfed.notify` under its own
 * identity, gets the wake as one encrypted packet over whatever reaches the
 * device: through a Bluetooth RTNode that is the mesh, with no internet,
 * where FCM cannot reach the phone. [StackRuntime] hosts it
 * (`RetichatBridge.notifyRelayStart`) and handles each wake: a channel wake
 * pulls that channel's waiting posts, any other is handled as an FCM wake
 * ([WakeWorker]).
 *
 * Only with a Bluetooth radio configured (a saved, enabled RNode or the
 * Nearby RTNode switch on): over Wi-Fi or mobile data FCM already wakes the
 * app, and RFed would spend a packet, or a path request, on every wake for
 * nothing. Registered for the device's LXMF wakes (direct messages and its
 * distro), and for each channel whose push toggle is on (James, 2026-11-05:
 * "There is a setting for registering that rfed send notifications for a
 * channel. Follow that."; [RfedNotifyRegistrar]).
 *
 * Pure (no JNI, no Android), so MeshWakeTest can check it.
 */
object MeshWake {
    const val TAG = "MeshWake"

    /** Whether the device hosts its own relay and registers it with RFed. */
    fun wanted(savedRNode: Boolean, rtnodeSwitchOn: Boolean): Boolean =
        savedRNode || rtnodeSwitchOn

    /**
     * What a wake says (RFed-spec Notify.md "Wake packet"): who it is for,
     * the channel for a channel wake, and the transient id of the message it
     * is for. RFed sends no sender (it cannot read one), though the spec
     * allows a server that knows it to.
     */
    class Wake(
        val receiver: ByteArray,
        val sender: ByteArray? = null,
        val channel: ByteArray? = null,
        val message: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Wake && receiver.contentEquals(other.receiver) && sender.contentEquals(other.sender) &&
                channel.contentEquals(other.channel) && message.contentEquals(other.message)

        override fun hashCode(): Int =
            listOf(receiver, sender, channel, message).fold(0) { h, b -> 31 * h + b.contentHashCode() }
    }

    /**
     * The wake in [payload]: a msgpack map with string keys and bin values
     * (16 bytes, 32 for `message`), `receiver` required. Keys it does not
     * know are skipped, so a field RFed adds later does not stop the wake.
     * Null for anything else, as a bridge drops it.
     */
    fun parse(payload: ByteArray): Wake? {
        val reader = Reader(payload)
        val count = reader.mapCount() ?: return null
        val fields = HashMap<String, ByteArray>()
        repeat(count) {
            val key = reader.string() ?: return null
            val value = reader.bin()
            if (value != null) fields[key] = value
            else if (!reader.skip()) return null
        }
        if (!reader.atEnd) return null
        val receiver = fields["receiver"]?.takeIf { it.size == 16 } ?: return null
        fun field(key: String, size: Int): ByteArray? = fields[key]?.takeIf { it.size == size }
        return Wake(receiver, field("sender", 16), field("channel", 16), field("message", 32))
    }

    /**
     * Enough msgpack for a wake: map, str and bin headers, and skipping the
     * scalars a later field might hold.
     */
    private class Reader(private val bytes: ByteArray) {
        private var at = 0

        val atEnd: Boolean get() = at == bytes.size

        private fun byte(): Int? = if (at < bytes.size) bytes[at++].toInt() and 0xff else null

        private fun length(size: Int): Int? {
            if (at + size > bytes.size) return null
            var n = 0
            for (i in 0 until size) n = (n shl 8) or (bytes[at + i].toInt() and 0xff)
            at += size
            return n
        }

        private fun take(n: Int): ByteArray? {
            if (n < 0 || at + n > bytes.size) return null
            return bytes.copyOfRange(at, at + n).also { at += n }
        }

        fun mapCount(): Int? {
            val b = byte() ?: return null
            return when (b) {
                in 0x80..0x8f -> b and 0x0f
                0xde -> length(2)
                else -> null
            }
        }

        /** A str value, or null with nothing read when the next value is not one (or not UTF-8). */
        fun string(): String? {
            val start = at
            val b = byte() ?: return null
            val n = when (b) {
                in 0xa0..0xbf -> b and 0x1f
                0xd9 -> length(1)
                0xda -> length(2)
                else -> null
            }
            val text = n?.let { take(it) }?.let { utf8(it) }
            if (text == null) at = start
            return text
        }

        /** A bin value, or null with nothing read when the next value is not one. */
        fun bin(): ByteArray? {
            val start = at
            val b = byte() ?: return null
            val n = when (b) {
                0xc4 -> length(1)
                0xc5 -> length(2)
                else -> null
            }
            val data = n?.let { take(it) }
            if (data == null) at = start
            return data
        }

        /** Skip one nil, bool, int, float or str; false for anything else. */
        fun skip(): Boolean {
            if (string() != null) return true
            val b = byte() ?: return false
            return when (b) {
                in 0x00..0x7f, in 0xe0..0xff, 0xc0, 0xc2, 0xc3 -> true
                0xcc, 0xd0 -> take(1) != null
                0xcd, 0xd1 -> take(2) != null
                0xce, 0xd2, 0xca -> take(4) != null
                0xcf, 0xd3, 0xcb -> take(8) != null
                else -> false
            }
        }

        /** Strict UTF-8, as Swift's `String(data:encoding: .utf8)`: null for a malformed key. */
        private fun utf8(data: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
