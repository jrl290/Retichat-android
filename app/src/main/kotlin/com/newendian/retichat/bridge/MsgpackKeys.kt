package com.newendian.retichat.bridge

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * A payload Python LXMF refuses whole for its msgpack, judged as Python LXMF
 * judges it (RFed SPEC §17.14 "Repeated keys", James's ruling of 2026-10-11:
 * parity; "Unhashable keys" and "Nesting", decided 2026-10-11).
 *
 * LXMF 1.1.1 reads a message with RNS's vendored umsgpack and drops it whole
 * on any of its errors. Three are about a payload's shape, in any map or
 * array at any depth:
 * - a map that repeats a key (`DuplicateKeyException`);
 * - a map whose key is a map, or an array that holds a map at any depth
 *   (`UnhashableKeyException`: umsgpack makes an array key a tuple, which
 *   Python cannot hash when it holds a dict);
 * - a value nested deeper than umsgpack's recursion reaches on the delivery
 *   path (`RecursionError`): [NESTING_MAX_DEPTH].
 *
 * The native layer refuses such a message whole, with its one log line,
 * before the app is given any of it (LXMF-rust `msgpack_keys`, 16a9902 for a
 * repeated key, b873c00 for the other two; [NativeRefusal]). This is the
 * app's own backstop for a native library built before that, and judges the
 * fields map the app is given exactly as LXMF-rust judges a payload; the
 * web's `lib/rns/msgpack_keys.js` `refusal()` is the same walk. It reads the
 * bytes in one pass, without recursion, in the order umsgpack reads them, so
 * the reason given is the first umsgpack meets: a key is read whole before it
 * is judged, a map key is refused before its value is read, and an array
 * key's value is read before the key is hashed. Two keys of one map are one
 * key when Python says so:
 * - integers, floats and booleans of one numeric value, whatever the
 *   encoding: `1`, `0xcc 0x01`, `1.0` (float 32 or 64) and `true` are one
 *   key, `0` and `-0.0` too; compared exactly (2^64 - 1 is not the float
 *   2^64); NaN is no key's equal, not even NaN's;
 * - str of the same bytes (never normalised), bin of the same bytes; a str is
 *   never a bin;
 * - nil and nil (0xc1, never used, is read as nil, as LXMF-rust's rmpv reads
 *   it);
 * - ext of the same type and data, a timestamp (ext -1) by its instant to the
 *   microsecond, as Python's `datetime` compares it.
 * An array key is never compared (umsgpack makes it a tuple and skips the
 * check), and is read when it holds no map. The `refused`, `key_equality` and
 * `nesting` vectors of distro_group_sync_vectors.json, checked against the
 * reference, pin each case (GroupEntryVectorsTest).
 *
 * Only the bytes given are judged: for the app, a message's fields map. The
 * rest of an LXMF payload (timestamp, title, content, stamp) holds no map or
 * array a sender could nest; anything after the fields map is the native
 * layer's to judge.
 */
object MsgpackKeys {
    /**
     * RFed SPEC §17.14 "Nesting" (decided 2026-10-11: one limit, Python's
     * measured delivery-path depth; LXMF-rust msgpack_keys.rs
     * NESTING_MAX_DEPTH): no value of a payload may be deeper than this, the
     * payload itself at depth 0, its fields map at 1 ([FIELDS_DEPTH]), a
     * field's value at 2, and anything in an array or a map (a key too) one
     * deeper than it. So a field holds at most 492 nested arrays or maps whose
     * innermost is empty, or 491 whose innermost holds anything.
     */
    const val NESTING_MAX_DEPTH = 493

    /** The depth of an LXMF payload's fields map: the payload is 0. */
    const val FIELDS_DEPTH = 1

    /** Why Python LXMF refuses a payload whole, and the reference's exception and the rule that follows it. */
    enum class Reason(val source: String) {
        REPEATED_KEY("umsgpack DuplicateKeyException; RFed SPEC §17.14 \"Repeated keys\""),
        UNHASHABLE_KEY("umsgpack UnhashableKeyException; RFed SPEC §17.14 \"Unhashable keys\""),
        TOO_DEEP("RecursionError on its delivery path; RFed SPEC §17.14 \"Nesting\""),
    }

    /**
     * The first reason the reference refuses a payload whole: a map at
     * [depth] (its own) repeats [key], or has [key] Python cannot hash; or a
     * value at [depth] is past [NESTING_MAX_DEPTH] ([key] null). [key] names
     * the key for the log.
     */
    data class Refusal(val reason: Reason, val depth: Int, val key: String?) {
        /** What it is, in LXMF-rust's words (msgpack_keys.rs `Refusal`). */
        val says: String get() = when (reason) {
            Reason.REPEATED_KEY -> "a map at depth $depth repeats a key ($key)"
            Reason.UNHASHABLE_KEY -> "a map at depth $depth has a key Python cannot hash ($key)"
            Reason.TOO_DEEP -> "a value at depth $depth is nested past depth $NESTING_MAX_DEPTH"
        }

        /**
         * LXMF-rust's error text after [NativeRefusal.REFUSED_WHOLE]: "<says>,
         * as Python LXMF refuses it (<the reference's exception; the rule>)".
         */
        val why: String get() = "$says, as Python LXMF refuses it (${reason.source})"

        override fun toString() = why
    }

    /**
     * The first reason Python LXMF refuses the msgpack value at the start of
     * [bytes] whole, in the order umsgpack reads its bytes, or null when it
     * reads it. [depth] is the value's own depth in the payload: the payload
     * 0, its fields map [FIELDS_DEPTH]. Bytes it cannot walk (cut short) end
     * the walk: a refusal before them is still found, and what follows is left
     * to the reader, which fails on them as it did. It stops at the first
     * refusal, so a value nested far past the limit costs [NESTING_MAX_DEPTH]
     * steps.
     */
    fun refusal(bytes: ByteArray, depth: Int = 0): Refusal? {
        val open = ArrayList<Open>()
        var pos = 0
        try {
            do {
                val start = pos
                val head = head(bytes, pos)
                pos = head.end
                val at = if (open.isEmpty()) depth else open.last().depth + 1
                if (at > NESTING_MAX_DEPTH) return Refusal(Reason.TOO_DEEP, at, null)
                if (head.kind == MAP) {
                    // Every container open holds a map now; one that did already, and those
                    // under it, were marked when it was.
                    for (i in open.indices.reversed()) {
                        if (open[i].holdsMap) break
                        open[i].holdsMap = true
                    }
                }
                if (head.kind != SCALAR && head.count > 0) {
                    open.add(Open(head.kind, if (head.kind == MAP) 2 * head.count else head.count, at, start))
                    continue
                }
                // Read whole: a scalar, or an empty map or array; and with it every
                // container it was the last item of.
                var done = Done(head.kind, start, pos, holdsMap = false)
                while (open.isNotEmpty()) {
                    val p = open.last()
                    complete(bytes, p, done)?.let { return it }
                    if (p.left > 0) break
                    open.removeAt(open.size - 1)
                    done = Done(p.kind, p.start, pos, p.holdsMap)
                }
            } while (open.isNotEmpty())
        } catch (_: IllegalArgumentException) {
            return null
        }
        return null
    }

    // ---- the walk ----

    private const val SCALAR = 0
    private const val MAP = 1
    private const val ARRAY = 2

    /** A container being read, at its own [depth], from [start]; [left] the items still to read (a map's keys and values both). */
    private class Open(val kind: Int, var left: Long, val depth: Int, val start: Int) {
        /** The next item of a map is a key. */
        var keyNext = true
        /** A map's keys so far, as Python compares them. */
        val seen: HashSet<Key>? = if (kind == MAP) HashSet() else null
        /** A map was met inside it, at any depth. */
        var holdsMap = false
        /** The array key whose value is being read. */
        var arrayKey: Done? = null
    }

    /** An item read whole, `bytes[start, end)`. */
    private class Done(val kind: Int, val start: Int, val end: Int, val holdsMap: Boolean)

    /** Item [done] has been read whole in [p]: its key judged, or the array key before it hashed. */
    private fun complete(bytes: ByteArray, p: Open, done: Done): Refusal? {
        p.left--
        if (p.kind != MAP) return null
        if (p.keyNext) {
            p.keyNext = false
            return when (done.kind) {
                // A map is no key: refused before its value is read.
                MAP -> Refusal(Reason.UNHASHABLE_KEY, p.depth, label(bytes, done))
                // An array key is never compared; its value is read, then the key is hashed.
                ARRAY -> { p.arrayKey = done; null }
                else -> {
                    val key = keyOf(bytes, done.start)
                    if (key != null && !p.seen!!.add(key)) Refusal(Reason.REPEATED_KEY, p.depth, label(bytes, done)) else null
                }
            }
        }
        p.keyNext = true
        val arrayKey = p.arrayKey
        p.arrayKey = null
        return if (arrayKey != null && arrayKey.holdsMap) Refusal(Reason.UNHASHABLE_KEY, p.depth, label(bytes, arrayKey)) else null
    }

    // ---- reading one item ----

    /** An item's head: [kind], where it (a scalar) or its header (a container) ends, and a container's [count]. */
    private class Head(val kind: Int, val end: Int, val count: Long)

    /** [n] bytes from [pos] are there. */
    private fun need(b: ByteArray, pos: Int, n: Long) {
        if (n < 0 || pos.toLong() + n > b.size) throw IllegalArgumentException("msgpack cut short")
    }

    /** The unsigned big-endian integer of [size] bytes at [pos]. */
    private fun uint(b: ByteArray, pos: Int, size: Int): Long {
        need(b, pos, size.toLong())
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
        return v
    }

    private fun head(b: ByteArray, pos: Int): Head {
        need(b, pos, 1)
        val t = b[pos].toInt() and 0xFF
        fun scalar(size: Long): Head {
            need(b, pos, size)
            return Head(SCALAR, (pos + size).toInt(), 0)
        }
        fun container(kind: Int, size: Int) = Head(kind, pos + 1 + size, uint(b, pos + 1, size))
        return when {
            t <= 0x7F || t >= 0xE0 -> scalar(1)
            t <= 0x8F -> Head(MAP, pos + 1, (t and 0x0F).toLong())
            t <= 0x9F -> Head(ARRAY, pos + 1, (t and 0x0F).toLong())
            t <= 0xBF -> scalar(1L + (t and 0x1F))
            t <= 0xC3 -> scalar(1)                                   // nil, 0xc1, false, true
            t == 0xC4 || t == 0xD9 -> scalar(2 + uint(b, pos + 1, 1)) // bin 8, str 8
            t == 0xC5 || t == 0xDA -> scalar(3 + uint(b, pos + 1, 2))
            t == 0xC6 || t == 0xDB -> scalar(5 + uint(b, pos + 1, 4))
            t == 0xC7 -> scalar(3 + uint(b, pos + 1, 1))              // ext 8: length, type, data
            t == 0xC8 -> scalar(4 + uint(b, pos + 1, 2))
            t == 0xC9 -> scalar(6 + uint(b, pos + 1, 4))
            t == 0xCA || t == 0xCE || t == 0xD2 -> scalar(5)
            t == 0xCB || t == 0xCF || t == 0xD3 -> scalar(9)
            t == 0xCC || t == 0xD0 -> scalar(2)
            t == 0xCD || t == 0xD1 -> scalar(3)
            t <= 0xD8 -> scalar(2L + (1 shl (t - 0xD4)))             // fixext 1..16: type, data
            t == 0xDC -> container(ARRAY, 2)
            t == 0xDD -> container(ARRAY, 4)
            t == 0xDE -> container(MAP, 2)
            else -> container(MAP, 4)                                 // 0xdf
        }
    }

    /**
     * Where a str's, a bin's or an ext's body starts (an ext's type byte) and
     * the length of its data, for the item at [p], which [head] has checked.
     */
    private fun body(b: ByteArray, p: Int): Pair<Int, Int> {
        val t = b[p].toInt() and 0xFF
        return when {
            t in 0xA0..0xBF -> (p + 1) to (t and 0x1F)
            t == 0xC4 || t == 0xD9 || t == 0xC7 -> (p + 2) to uint(b, p + 1, 1).toInt()
            t == 0xC5 || t == 0xDA || t == 0xC8 -> (p + 3) to uint(b, p + 1, 2).toInt()
            t == 0xC6 || t == 0xDB || t == 0xC9 -> (p + 5) to uint(b, p + 1, 4).toInt()
            else -> (p + 1) to (1 shl (t - 0xD4))                     // fixext
        }
    }

    // ---- a key as Python compares it ----

    private const val NIL = 0
    private const val NUMBER = 1
    private const val FLOAT = 2
    private const val STR = 3
    private const val BIN = 4
    private const val INSTANT = 5
    private const val EXT = 6

    /** A key as Python compares it: [value] a BigInteger, a float's bits, a ByteBuffer (by its bytes) or an ext's (type, data). */
    private data class Key(val tag: Int, val value: Any?)

    private fun number(v: Long) = Key(NUMBER, BigInteger.valueOf(v))

    /** A float by its exact value when it is integral (1.0 is 1, -0.0 is 0), else by its bits as a double; null for NaN. */
    private fun floatKey(f: Double): Key? = when {
        f.isNaN() -> null
        f.isFinite() && f == Math.floor(f) -> Key(NUMBER, BigDecimal(f).toBigInteger())
        else -> Key(FLOAT, f.toBits())
    }

    /**
     * The instant of a timestamp ext's [data], in microseconds since the
     * epoch, as umsgpack makes it (nanoseconds floored to microseconds); null
     * for a length it refuses (another refusal than these).
     */
    private fun timestampMicros(b: ByteArray, off: Int, len: Int): BigInteger? {
        val (seconds, nanoseconds) = when (len) {
            4 -> BigInteger.valueOf(uint(b, off, 4)) to BigInteger.ZERO
            8 -> {
                val v = BigInteger(1, b.copyOfRange(off, off + 8))
                v.and(BigInteger.valueOf(0x3_FFFF_FFFFL)) to v.shiftRight(34)
            }
            12 -> BigInteger(b.copyOfRange(off + 4, off + 12)) to BigInteger.valueOf(uint(b, off, 4))
            else -> return null
        }
        return seconds.multiply(BigInteger.valueOf(1_000_000)).add(nanoseconds.divide(BigInteger.valueOf(1000)))
    }

    /** The scalar at [p] as Python compares it as a key, or null when Python never finds it in the dict (NaN). */
    private fun keyOf(b: ByteArray, p: Int): Key? {
        val t = b[p].toInt() and 0xFF
        return when {
            t <= 0x7F -> number(t.toLong())
            t >= 0xE0 -> number((t - 0x100).toLong())
            t in 0xA0..0xBF || t in 0xD9..0xDB -> body(b, p).let { (off, len) -> Key(STR, ByteBuffer.wrap(b, off, len).slice()) }
            t == 0xC0 || t == 0xC1 -> Key(NIL, null)
            t == 0xC2 -> number(0)
            t == 0xC3 -> number(1)
            t in 0xC4..0xC6 -> body(b, p).let { (off, len) -> Key(BIN, ByteBuffer.wrap(b, off, len).slice()) }
            t in 0xC7..0xC9 || t in 0xD4..0xD8 -> body(b, p).let { (off, len) ->
                val type = b[off].toInt()
                val micros = if (type == -1) timestampMicros(b, off + 1, len) else null
                micros?.let { Key(INSTANT, it) } ?: Key(EXT, type to ByteBuffer.wrap(b, off + 1, len).slice())
            }
            t == 0xCA -> floatKey(java.lang.Float.intBitsToFloat(uint(b, p + 1, 4).toInt()).toDouble())
            t == 0xCB -> floatKey(java.lang.Double.longBitsToDouble(uint(b, p + 1, 8)))
            t == 0xCC -> number(uint(b, p + 1, 1))
            t == 0xCD -> number(uint(b, p + 1, 2))
            t == 0xCE -> number(uint(b, p + 1, 4))
            t == 0xCF -> Key(NUMBER, BigInteger(1, b.copyOfRange(p + 1, p + 9)))
            t == 0xD0 -> number(b[p + 1].toLong())
            t == 0xD1 -> number(uint(b, p + 1, 2).toShort().toLong())
            t == 0xD2 -> number(uint(b, p + 1, 4).toInt().toLong())
            else -> number(uint(b, p + 1, 8))                         // 0xd3, int 64
        }
    }

    // ---- a key's name in the log ----

    private fun hexCut(b: ByteArray, off: Int, len: Int): String {
        val shown = minOf(len, 24)
        val hex = (off until off + shown).joinToString("") { "%02x".format(b[it]) }
        return if (len > shown) "$hex..." else hex
    }

    /** How the log names [done]: a container by its kind and bytes, a scalar by its value. */
    private fun label(b: ByteArray, done: Done): String {
        if (done.kind == MAP) return "map ${hexCut(b, done.start, done.end - done.start)}"
        if (done.kind == ARRAY) return "array ${hexCut(b, done.start, done.end - done.start)}"
        val p = done.start
        val t = b[p].toInt() and 0xFF
        return when {
            t == 0xC0 || t == 0xC1 -> "nil"
            t == 0xC2 -> "false"
            t == 0xC3 -> "true"
            t == 0xCA -> java.lang.Float.intBitsToFloat(uint(b, p + 1, 4).toInt()).toString()
            t == 0xCB -> java.lang.Double.longBitsToDouble(uint(b, p + 1, 8)).toString()
            t in 0xA0..0xBF || t in 0xD9..0xDB -> body(b, p).let { (off, len) ->
                val text = String(b, off, len, Charsets.UTF_8)
                "\"${text.take(48).replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
            t in 0xC4..0xC6 -> body(b, p).let { (off, len) -> "bin ${hexCut(b, off, len)}" }
            t in 0xC7..0xC9 || t in 0xD4..0xD8 -> body(b, p).let { (off, len) -> "ext ${b[off].toInt()} ${hexCut(b, off + 1, len)}" }
            else -> (keyOf(b, p)!!.value as BigInteger).toString()       // an integer
        }
    }
}
