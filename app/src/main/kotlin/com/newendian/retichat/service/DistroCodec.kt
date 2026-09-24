package com.newendian.retichat.service

import java.security.MessageDigest

/**
 * Pure helpers for the distro feature (no Android, no JNI) so they can be
 * unit-tested on the JVM. The formats mirror the web client
 * (Retichat-js/lib/distro.js, app.js) and RFed SPEC §17.
 */
object DistroCodec {
    /** Current transfer URI scheme (web client `DISTRO_URI_SCHEME`). */
    const val URI_SCHEME = "rfed-distro-private-key://"
    /** Legacy scheme still accepted on import. */
    const val LEGACY_URI_SCHEME = "rfed-distro-id://"
    /** X25519(32) || Ed25519(32) private key, as the web client and iOS export it. */
    const val PRIVATE_KEY_BYTES = 64

    private val HEX = Regex("^[0-9a-f]{128}$")

    /**
     * Accepts `rfed-distro-private-key://<128 hex>`, the legacy
     * `rfed-distro-id://<128 hex>`, or bare 128 hex (any case, surrounding
     * whitespace tolerated). Returns the 64 key bytes or null.
     */
    fun parsePrivateKey(text: String): ByteArray? {
        var t = text.trim()
        for (scheme in listOf(URI_SCHEME, LEGACY_URI_SCHEME)) {
            if (t.lowercase().startsWith(scheme)) {
                t = t.substring(scheme.length)
                break
            }
        }
        t = t.trim().trimEnd('/').lowercase()
        if (!HEX.matches(t)) return null
        return hexToBytes(t)
    }

    fun exportUri(privateKey: ByteArray): String = URI_SCHEME + privateKey.toHex()

    /** `lxma://<lxmf.delivery hash>:<64-byte pubkey>` — the contact link senders use. */
    fun contactUri(deliveryHashHex: String, publicKeyHex: String): String =
        "lxma://$deliveryHashHex:$publicKeyHex"

    /**
     * RFed answers register/unregister/announce with msgpack `true`, or an
     * array whose first element is `true` (older nodes). Anything else is a
     * refusal.
     */
    fun isAffirmative(response: ByteArray?): Boolean {
        if (response == null || response.isEmpty()) return false
        val b0 = response[0].toInt() and 0xff
        if (b0 == 0xc3) return true
        if (b0 and 0xf0 == 0x90 && response.size >= 2) return (response[1].toInt() and 0xff) == 0xc3
        return false
    }

    /** msgpack nil — the body of `/rfed/pull` on `rfed.distro.register`. */
    val MSGPACK_NIL: ByteArray = byteArrayOf(0xc0.toByte())

    /**
     * Decode `/rfed/pull`: `[[[bin(16) distro_hash, bin blob], ...], bool more]`.
     * Returns the pairs and the `more` flag, or null when malformed.
     */
    fun decodePullResponse(data: ByteArray): Pair<List<Pair<ByteArray, ByteArray>>, Boolean>? {
        val h = intArrayOf(0)
        val outer = readArrayCount(data, h) ?: return null
        if (outer != 2) return null
        val n = readArrayCount(data, h) ?: return null
        val pairs = ArrayList<Pair<ByteArray, ByteArray>>(n)
        repeat(n) {
            val inner = readArrayCount(data, h) ?: return null
            if (inner != 2) return null
            val hash = readBin(data, h) ?: return null
            val blob = readBin(data, h) ?: return null
            pairs.add(hash to blob)
        }
        if (h[0] >= data.size) return null
        val more = when (data[h[0]].toInt() and 0xff) {
            0xc2 -> false
            0xc3 -> true
            else -> return null
        }
        return pairs to more
    }

    /** Dedupe key for a fanned-out copy: the same message arrives more than once. */
    fun seenKey(sourceHashHex: String, timestamp: Double): String = "$sourceHashHex:$timestamp"

    /** Keep the newest [cap] keys, oldest first in the list. */
    fun appendSeen(existing: List<String>, key: String, cap: Int = 500): List<String> {
        if (existing.contains(key)) return existing
        val out = existing + key
        return if (out.size > cap) out.takeLast(cap) else out
    }

    /**
     * A stable id for a distro message: the fan-out never hands us the LXMF
     * hash, so derive one from the identity the web client dedupes on. This
     * is the rule the regular fan-out store site uses
     * (ChatRepository.onDistroMessageReceived), keyed on the message's source.
     */
    fun messageId(sourceHashHex: String, timestamp: Double, content: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("distro|".toByteArray())
        md.update(sourceHashHex.toByteArray())
        md.update("|".toByteArray())
        md.update(timestamp.toString().toByteArray())
        md.update("|".toByteArray())
        md.update(content.toByteArray())
        return md.digest().copyOf(16).toHex()
    }

    /**
     * RFed SPEC §17.11: the id a sibling's sent-copy C is stored under
     * (ChatRepository.onDistroSentCopy). It is C's fan-out id: C's source is
     * the distro, and [lxmfTimestamp] is C's own LXMF timestamp, so every
     * arrival of C (live stream and /rfed/pull) gets the same id and is stored
     * once. Not the local receive clock, which gives each arrival its own id,
     * and not the recipient (the chat C is filed in), which is not C's source
     * and so not the fan-out rule.
     */
    fun sentCopyMessageId(distroHex: String, lxmfTimestamp: Double, content: String): String =
        messageId(distroHex, lxmfTimestamp, content)

    private val HEX32 = Regex("^[0-9a-f]{32}$")

    /** A 16-byte address as 32 lowercase hex, the only form §17.11's fields carry. */
    fun isHex32(s: String?): Boolean = s != null && HEX32.matches(s)

    /**
     * RFed SPEC §17.11: a message sent AS the distro to anyone but the distro
     * itself gets one sent-copy to the distro, so sibling devices see it.
     * [sentAsHex] is the source the message actually went out with; a device
     * without a distro (or one that sent as the device) sends no copy.
     */
    fun shouldSendSentCopy(sentAsHex: String, distroHex: String?, recipientHex: String): Boolean =
        isHex32(distroHex) && sentAsHex == distroHex && recipientHex != distroHex

    /** What to do with an unwrapped fan-out message, per RFed SPEC §17.11. */
    sealed class SentCopy {
        /** No `rfed.distro.sent` marker: today's inbound handling. */
        object NotACopy : SentCopy()
        /** This device sent it; drop silently (it is already dedupe-recorded). */
        object OwnEcho : SentCopy()
        /** Marker on a message not from our own distro: nobody else can make a genuine copy. */
        object Foreign : SentCopy()
        /** Marker with a 0xFC that is not a usable 32-hex recipient. */
        data class Malformed(val sentTo: String?) : SentCopy()
        /** A sibling's sent message: file it as outgoing in the chat with [recipientHex]. */
        data class Store(val recipientHex: String) : SentCopy()
    }

    /**
     * Classify one unwrapped fan-out message by its §17.11 marker. [sentTo] /
     * [sentBy] are the unwrap JSON's `sent_to` / `sent_by`: lxmf_rust sets
     * `sent_by` (possibly empty) whenever 0xFB is `rfed.distro.sent`, and
     * `sent_to` only when 0xFC is also 32 hex, so a `sent_by` without a
     * `sent_to` is a copy with a bad recipient. The own-echo check comes
     * before the recipient check, as the spec orders them; an own-device
     * address that is not 32 hex (stack not up) never matches, so an empty
     * `sent_by` can not be mistaken for our echo.
     */
    fun classifySentCopy(
        sourceHex: String,
        ownDistroHex: String?,
        ownDeviceHex: String,
        sentTo: String?,
        sentBy: String?,
    ): SentCopy {
        if (sentBy == null && sentTo == null) return SentCopy.NotACopy
        if (!isHex32(ownDistroHex) || sourceHex != ownDistroHex) return SentCopy.Foreign
        if (isHex32(ownDeviceHex) && sentBy == ownDeviceHex) return SentCopy.OwnEcho
        // No device sends a copy of a message to the distro itself (§17.11),
        // so a copy naming the distro as recipient is as unusable as a bad one.
        if (!isHex32(sentTo) || sentTo == ownDistroHex) return SentCopy.Malformed(sentTo)
        return SentCopy.Store(sentTo!!)
    }

    private fun readArrayCount(data: ByteArray, h: IntArray): Int? {
        if (h[0] >= data.size) return null
        val tag = data[h[0]].toInt() and 0xff
        h[0]++
        return when {
            tag and 0xf0 == 0x90 -> tag and 0x0f
            tag == 0xdc -> { if (h[0] + 2 > data.size) return null; val n = u16(data, h[0]); h[0] += 2; n }
            tag == 0xdd -> { if (h[0] + 4 > data.size) return null; val n = u32(data, h[0]); h[0] += 4; n }
            else -> null
        }
    }

    private fun readBin(data: ByteArray, h: IntArray): ByteArray? {
        if (h[0] >= data.size) return null
        val tag = data[h[0]].toInt() and 0xff
        h[0]++
        val len = when (tag) {
            0xc4 -> { if (h[0] + 1 > data.size) return null; val n = data[h[0]].toInt() and 0xff; h[0] += 1; n }
            0xc5 -> { if (h[0] + 2 > data.size) return null; val n = u16(data, h[0]); h[0] += 2; n }
            0xc6 -> { if (h[0] + 4 > data.size) return null; val n = u32(data, h[0]); h[0] += 4; n }
            else -> return null
        }
        if (h[0] + len > data.size) return null
        val out = data.copyOfRange(h[0], h[0] + len)
        h[0] += len
        return out
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)
    private fun u32(d: ByteArray, i: Int) =
        ((d[i].toInt() and 0xff) shl 24) or ((d[i + 1].toInt() and 0xff) shl 16) or
            ((d[i + 2].toInt() and 0xff) shl 8) or (d[i + 3].toInt() and 0xff)

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
