package com.newendian.retichat.service

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Channel membership across a distro's devices: RFed-rust SPEC.md §17.12
 * "Membership sync" (James, 2026-10-03). Pure rules, no Android and no JNI, so
 * the JVM tests pin them. Mirrors Retichat-js `lib/channel_sync.js`.
 *
 * Joining or leaving a channel on one device is applied on every other device
 * of the distro D. The change travels as one LXMF message C from D to D, the
 * way a §17.11 sent-copy does; each sibling subscribes or unsubscribes with its
 * own device key, so RFed sees only ordinary subscriptions. C's fields:
 *
 *  - 0x0C FIELD_TICKET: an empty bin. No ticket: it makes older clients take C
 *    for a delivery notification and drop it. Never read here.
 *  - 0xFB FIELD_CUSTOM_TYPE: [TYPE].
 *  - 0xFC FIELD_CUSTOM_DATA: `[op, name, at_ms]` as a native msgpack array,
 *    never a bin holding packed msgpack (CHECK_THESE_THINGS_FIRST §11).
 *  - 0xFD FIELD_CUSTOM_META: the sending device's own `lxmf.delivery` address.
 *
 * C is written by `nativeMessageSetDistroChannelSync` (rust/retichat-jni) and
 * read here from the bytes of the unwrapped fields map, which
 * `lxmf_rust::distro::unwrap_blob` hands on exactly as the sender packed them.
 * The reader takes every msgpack integer encoding for `at_ms` and judges the
 * value, never the type byte: the web writes int 64 (0xd3), rmpv and umsgpack
 * uint 64 (0xcf). A float, even an integral one, a negative integer, a str and
 * nil are no `at_ms`.
 */
object DistroChannelSync {
    /** FIELD_CUSTOM_TYPE of a membership message. */
    const val TYPE = "rfed.distro.channel"

    const val OP_JOIN = "join"
    const val OP_LEAVE = "leave"

    /**
     * `at_ms` is a msgpack integer from 0 to 2^53 - 1 (§17.12 "`at_ms` on the
     * wire"): the web holds it in a JavaScript number, exact only up to there,
     * so a larger one would be ordered differently on the clients. A writer
     * never writes one and a receiver drops it, whatever its encoding.
     */
    const val MAX_AT_MS: Long = (1L shl 53) - 1

    private const val FIELD_CUSTOM_TYPE = 0xFB
    private const val FIELD_CUSTOM_DATA = 0xFC
    private const val FIELD_CUSTOM_META = 0xFD

    /** The id C is owed under: one per channel, so a later action replaces it. */
    fun entryId(channelHashHex: String): String = "channel:$channelHashHex"

    /** What a usable C says: the user's [op] on [name] (the channel's full name) at [atMs]. */
    data class Sync(val op: String, val name: String, val atMs: Long)

    /**
     * The membership marker of a received message: [byHex] is 0xFD lowercased
     * ("" when absent; never format-checked: a malformed one is simply never
     * this device's own address). [sync] is set for a usable 0xFC; otherwise
     * [problem] says why not, and §17.12 rule 4 drops C with it.
     */
    data class Marker(val byHex: String, val sync: Sync?, val problem: String?)

    /**
     * The membership marker of a message whose unwrapped LXMF fields map is
     * [fieldsMap] (msgpack, as the sender packed it): null when the message is
     * no membership message (no fields map, or 0xFB is not [TYPE], as str or
     * bin); a [Marker] with a [Sync] for a usable one; a [Marker] with a
     * problem once the type matches but 0xFC is unusable (rule 4: dropped and
     * logged, never taken for an ordinary message). `op` must be "join" or
     * "leave"; `name` a non-empty str or bin of valid UTF-8; `at_ms` an integer
     * from 0 to [MAX_AT_MS], in any integer encoding. Elements after the third
     * are ignored. The name rules of a join are the disposition's, not this
     * reader's: a leave is not held to them. Never throws.
     *
     * A fields map that repeats a key is never read (RFed SPEC §17.14
     * "Repeated keys", which settles it for §17.12 too: James, 2026-10-11).
     * The distro unwrap refuses such a message whole, with its one log line,
     * before the app is given its fields; one whose 0xFB names [TYPE] in any
     * entry and whose map repeats an integer key would get here only if it did
     * not, and is then a C with that problem (rule 4), never one read from one
     * of its entries. Until 2026-10-11 the first entry of a key was read.
     */
    fun read(fieldsMap: ByteArray?): Marker? {
        if (fieldsMap == null || fieldsMap.isEmpty()) return null
        val b = fieldsMap
        val typePositions = ArrayList<Int>()
        var dataPos = -1
        var metaPos = -1
        val keys = HashSet<Long>()
        var repeated: Long? = null
        try {
            val map = readHead(b, 0)
            if (map.kind != Kind.MAP) return null
            var pos = map.end
            for (i in 0 until map.count) {
                val key = readHead(b, pos)
                pos = skip(b, pos, 0)
                if (key.kind == Kind.INT && !key.overflow) {
                    if (!keys.add(key.int) && repeated == null) repeated = key.int
                    when (key.int) {
                        FIELD_CUSTOM_TYPE.toLong() -> typePositions += pos
                        FIELD_CUSTOM_DATA.toLong() -> dataPos = pos
                        FIELD_CUSTOM_META.toLong() -> metaPos = pos
                    }
                }
                pos = skip(b, pos, 0)
            }
            if (typePositions.none { textOf(b, it) == TYPE }) return null
        } catch (_: IllegalArgumentException) {
            // Too broken to name its type: no membership message.
            return null
        }
        if (repeated != null) {
            return Marker("", null, "its fields map repeats the key 0x${repeated.toString(16)}: refused whole (RFed SPEC §17.14 \"Repeated keys\")")
        }
        var byHex = ""
        fun bad(problem: String) = Marker(byHex, null, problem)
        try {
            if (metaPos >= 0) byHex = (textOf(b, metaPos) ?: "").lowercase()
            if (dataPos < 0) return bad("it carries no 0xFC")
            val data = readHead(b, dataPos)
            if (data.kind != Kind.ARRAY) return bad("its 0xFC is a ${data.kind.label}, not an array")
            if (data.count < 3) return bad("its 0xFC holds ${data.count} element(s), not [op, name, at_ms]")
            val opPos = data.end
            val namePos = skip(b, opPos, 0)
            val atPos = skip(b, namePos, 0)
            val op = textOf(b, opPos)
            if (op != OP_JOIN && op != OP_LEAVE) {
                return bad("its op is ${if (op == null) "not text" else "\"$op\""}, not \"join\" or \"leave\"")
            }
            val name = textOf(b, namePos)
            if (name.isNullOrEmpty()) return bad("its name is empty, not text, or not UTF-8")
            val at = readHead(b, atPos)
            if (at.kind != Kind.INT || at.overflow || at.int < 0 || at.int > MAX_AT_MS) {
                return bad("its at_ms is not an integer from 0 to 2^53 − 1")
            }
            return Marker(byHex, Sync(op, name, at.int), null)
        } catch (e: IllegalArgumentException) {
            return bad("its fields cannot be read (${e.message})")
        }
    }

    // ---- §17.12 rule 5 ----

    /** The last membership action this device made or applied for a channel. */
    data class Record(val op: String, val atMs: Long)

    /**
     * Rule 5: whether the action ([op] at [atMs]) replaces the one recorded for
     * its channel ([recorded], null when none). A later time wins; at an equal
     * time a leave replaces a join and a join never replaces a leave, so every
     * device ends in the same state whatever order the messages arrive in.
     */
    fun supersedes(op: String, atMs: Long, recorded: Record?): Boolean {
        if (recorded == null) return true
        if (atMs > recorded.atMs) return true
        if (atMs < recorded.atMs) return false
        return op == OP_LEAVE && recorded.op == OP_JOIN
    }

    /**
     * The `at_ms` of the user's own join or leave on this device: the current
     * time, or one more than the time already recorded for the channel when
     * that is later. So an action made after this device took a sibling's
     * record is newer than that record on every device, even with this
     * device's clock behind the sibling's. (The guarantee needs the record to
     * have arrived: actions two devices make before either has the other's are
     * ordered by their clocks alone.)
     */
    fun stampLocal(recorded: Record?, nowMs: Long): Long {
        val at = if (recorded != null && recorded.atMs + 1 > nowMs) recorded.atMs + 1 else nowMs
        require(at in 0..MAX_AT_MS) { "at_ms $at is outside 0..2^53 - 1" }
        return at
    }

    // ---- §17.12 receive rules 1-5 ----

    /** What rules 1-5 decide for a received C. */
    sealed class Disposition {
        /** Dropped with a log line ([rule] says which rule). Never recorded as seen. */
        data class Drop(val rule: Int, val reason: String) : Disposition()

        /** This device's own message (rule 3): dropped silently, recorded as seen. */
        data object Echo : Disposition()

        /** Older than the action recorded for its channel (rule 5): dropped, recorded as seen. */
        data class Stale(
            val op: String, val name: String, val atMs: Long, val channelHash: String, val recorded: Record,
        ) : Disposition()

        /** Rule 6 next: apply it on this device. */
        data class Apply(val op: String, val name: String, val atMs: Long, val channelHash: String) : Disposition()
    }

    /**
     * Rules 1-5 in order; the first that drops a message ends the check.
     * [marker] is [read]'s result (not null). The facts the rules need are
     * given, computed by the caller:
     *  - [fromDistro]: the unwrapped source is D (rule 1);
     *  - [signedByDistro]: the LXMF signature is D's (rule 2: the source hash
     *    is plaintext in the encrypted body and D's key is announced, so anyone
     *    can claim source D; without the check a stranger could make the user's
     *    devices join a channel of the stranger's choosing, or leave one and
     *    delete its history);
     *  - [ownDeviceHex]: this device's own `lxmf.delivery` address (rule 3);
     *  - [joinNameAccepted]: whether a join's name is one this client's own
     *    join accepts (rule 4; a leave's name is not held to it, so a channel
     *    joined before the rules, such as `public.Test`, can still be left);
     *  - [channelHashOf]: name -> the channel hash it gives, the key every
     *    client stores its channels and the record under;
     *  - [recordOf]: channel hash -> the action recorded for it, or null.
     */
    fun disposition(
        marker: Marker,
        fromDistro: Boolean,
        signedByDistro: Boolean,
        ownDeviceHex: String?,
        joinNameAccepted: (String) -> Boolean,
        channelHashOf: (String) -> String,
        recordOf: (String) -> Record?,
    ): Disposition {
        if (!fromDistro) return Disposition.Drop(1, "its source is not this device's distro")
        if (!signedByDistro) return Disposition.Drop(2, "it fails the distro signature")
        if (!ownDeviceHex.isNullOrEmpty() && marker.byHex == ownDeviceHex.lowercase()) return Disposition.Echo
        val sync = marker.sync ?: return Disposition.Drop(4, marker.problem ?: "its 0xFC is unusable")
        if (sync.op == OP_JOIN && !joinNameAccepted(sync.name)) {
            return Disposition.Drop(4, "its name is one this client's own join refuses")
        }
        val channelHash = channelHashOf(sync.name)
        val recorded = recordOf(channelHash)
        if (!supersedes(sync.op, sync.atMs, recorded)) {
            return Disposition.Stale(sync.op, sync.name, sync.atMs, channelHash, recorded!!)
        }
        return Disposition.Apply(sync.op, sync.name, sync.atMs, channelHash)
    }

    /**
     * The key a received C is deduplicated under (RFed SPEC §17.12
     * "Receiving"): the live fan-out and a pull, or two uploads of one C, are
     * one message. The generic fan-out key (source and timestamp) is not
     * enough: every C has source D and empty content, so two siblings' Cs made
     * in the same millisecond would share it and one of the two actions would
     * be lost, the devices ending in different states. The spec keys C by its
     * LXMF message hash; the unwrap does not report it, so this key takes what
     * tells two Cs apart, which is all the hash does: C's timestamp, the device
     * that acted (0xFD), and the action, channel and time it carries. Two
     * uploads of one C are one key, whatever bytes carried them.
     */
    fun repeatKey(sourceHex: String, timestamp: Double, marker: Marker): String {
        val sync = marker.sync
        return "$sourceHex:$timestamp:chan:${marker.byHex}:${sync?.op ?: "?"}:${sync?.name ?: "?"}:${sync?.atMs ?: -1}"
    }

    // ---- msgpack, read from the bytes ----

    private enum class Kind(val label: String) {
        NIL("nil"), BOOL("bool"), INT("int"), FLOAT("float"), STR("str"), BIN("bin"),
        ARRAY("array"), MAP("map"), EXT("ext"),
    }

    /**
     * A msgpack head: [end] is the offset after the head. [count] is the
     * element count of an array or map, and the payload length in bytes of a
     * str, bin or ext (an ext's type byte included). [int] is an integer's
     * value; [overflow] marks a uint 64 of 2^63 or more, which no `at_ms` is.
     */
    private class Head(
        val kind: Kind,
        val end: Int,
        val count: Int = 0,
        val int: Long = 0,
        val overflow: Boolean = false,
    )

    private const val MAX_DEPTH = 64

    private fun need(b: ByteArray, pos: Int, n: Long) {
        require(n >= 0 && pos >= 0 && pos + n <= b.size) { "$n bytes wanted at $pos, ${b.size - pos} left" }
    }

    private fun u8(b: ByteArray, pos: Int): Int { need(b, pos, 1); return b[pos].toInt() and 0xff }
    private fun u16(b: ByteArray, pos: Int): Int { need(b, pos, 2); return (u8(b, pos) shl 8) or u8(b, pos + 1) }
    private fun u32(b: ByteArray, pos: Int): Long {
        need(b, pos, 4)
        return (u16(b, pos).toLong() shl 16) or u16(b, pos + 2).toLong()
    }
    private fun i64(b: ByteArray, pos: Int): Long { need(b, pos, 8); return ByteBuffer.wrap(b, pos, 8).long }

    /** A length or count that must fit an Int to be usable (no input is that large). */
    private fun length(n: Long): Int {
        require(n in 0..Int.MAX_VALUE) { "length $n is not usable" }
        return n.toInt()
    }

    private fun readHead(b: ByteArray, pos: Int): Head {
        val t = u8(b, pos)
        val p = pos + 1
        return when {
            t <= 0x7f -> Head(Kind.INT, p, int = t.toLong())
            t in 0x80..0x8f -> Head(Kind.MAP, p, count = t and 0x0f)
            t in 0x90..0x9f -> Head(Kind.ARRAY, p, count = t and 0x0f)
            t in 0xa0..0xbf -> Head(Kind.STR, p, count = t and 0x1f)
            t >= 0xe0 -> Head(Kind.INT, p, int = (t - 256).toLong())
            else -> when (t) {
                0xc0 -> Head(Kind.NIL, p)
                0xc2, 0xc3 -> Head(Kind.BOOL, p)
                0xc4 -> Head(Kind.BIN, p + 1, count = u8(b, p))
                0xc5 -> Head(Kind.BIN, p + 2, count = u16(b, p))
                0xc6 -> Head(Kind.BIN, p + 4, count = length(u32(b, p)))
                0xc7 -> Head(Kind.EXT, p + 1, count = u8(b, p) + 1)
                0xc8 -> Head(Kind.EXT, p + 2, count = u16(b, p) + 1)
                0xc9 -> Head(Kind.EXT, p + 4, count = length(u32(b, p) + 1))
                0xca -> { need(b, p, 4); Head(Kind.FLOAT, p + 4) }
                0xcb -> { need(b, p, 8); Head(Kind.FLOAT, p + 8) }
                0xcc -> Head(Kind.INT, p + 1, int = u8(b, p).toLong())
                0xcd -> Head(Kind.INT, p + 2, int = u16(b, p).toLong())
                0xce -> Head(Kind.INT, p + 4, int = u32(b, p))
                0xcf -> { val v = i64(b, p); Head(Kind.INT, p + 8, int = v, overflow = v < 0) }
                0xd0 -> Head(Kind.INT, p + 1, int = u8(b, p).toByte().toLong())
                0xd1 -> Head(Kind.INT, p + 2, int = u16(b, p).toShort().toLong())
                0xd2 -> Head(Kind.INT, p + 4, int = u32(b, p).toInt().toLong())
                0xd3 -> Head(Kind.INT, p + 8, int = i64(b, p))
                in 0xd4..0xd8 -> Head(Kind.EXT, p, count = (1 shl (t - 0xd4)) + 1)
                0xd9 -> Head(Kind.STR, p + 1, count = u8(b, p))
                0xda -> Head(Kind.STR, p + 2, count = u16(b, p))
                0xdb -> Head(Kind.STR, p + 4, count = length(u32(b, p)))
                0xdc -> Head(Kind.ARRAY, p + 2, count = u16(b, p))
                0xdd -> Head(Kind.ARRAY, p + 4, count = length(u32(b, p)))
                0xde -> Head(Kind.MAP, p + 2, count = u16(b, p))
                0xdf -> Head(Kind.MAP, p + 4, count = length(u32(b, p)))
                else -> throw IllegalArgumentException("0x%02x is no msgpack type".format(t))
            }
        }
    }

    /** The offset after the value at [pos]. Every length is checked against the bytes left. */
    private fun skip(b: ByteArray, pos: Int, depth: Int): Int {
        require(depth <= MAX_DEPTH) { "nested deeper than $MAX_DEPTH" }
        val h = readHead(b, pos)
        return when (h.kind) {
            Kind.NIL, Kind.BOOL, Kind.INT, Kind.FLOAT -> h.end
            Kind.STR, Kind.BIN, Kind.EXT -> { need(b, h.end, h.count.toLong()); h.end + h.count }
            Kind.ARRAY -> { var p = h.end; for (i in 0 until h.count) p = skip(b, p, depth + 1); p }
            Kind.MAP -> { var p = h.end; for (i in 0 until h.count) { p = skip(b, p, depth + 1); p = skip(b, p, depth + 1) }; p }
        }
    }

    /** A str or bin value as text, strict UTF-8; null for any other kind or invalid UTF-8. */
    private fun textOf(b: ByteArray, pos: Int): String? {
        val h = readHead(b, pos)
        if (h.kind != Kind.STR && h.kind != Kind.BIN) return null
        need(b, h.end, h.count.toLong())
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, h.end, h.count))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
