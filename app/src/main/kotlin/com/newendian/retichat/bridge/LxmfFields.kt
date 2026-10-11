package com.newendian.retichat.bridge

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Minimal msgpack decoder for LXMF fields maps.
 *
 * LXMF fields are encoded as a msgpack Map with integer keys and
 * string/bool/binary/integer values. This class provides typed accessors
 * so that all field interpretation lives in Kotlin, not in the Rust FFI.
 */
class LxmfFields private constructor(
    private val entries: Map<Int, Any?>,
) {
    /** Get a string field, or null if absent/wrong type. */
    fun getString(key: Int): String? = when (val v = entries[key]) {
        is String -> v
        is ByteArray -> String(v, StandardCharsets.UTF_8)
        else -> null
    }

    /** Get a boolean field. Returns false if absent. */
    fun getBool(key: Int): Boolean = when (val v = entries[key]) {
        is Boolean -> v
        is Long -> v != 0L
        is Int -> v != 0
        else -> entries.containsKey(key)  // field present without value = true
    }

    /**
     * DISPLAY_NAMES.md §10: a str group entry, from the Retichat field 0xD1
     * when the map holds [entry]'s key as a msgpack str, otherwise from its
     * old top-level field when that is a str, otherwise null. The map wins
     * entry by entry (an empty str there is a value). Types are strict: bin
     * is not a str.
     */
    fun group(entry: GroupEntry): String? {
        require(entry.type == GroupEntry.Type.STR) { "$entry is not a str entry" }
        return (retichatEntry(entry.key) as? String) ?: (entries[entry.legacyField] as? String)
    }

    /**
     * The group entries as LXMF-rust's distro unwrap reads them (GROUPS-DISTRO-DESIGN.md
     * §7, RFed SPEC §17.14), pinned by the `group_entries` vectors: a message
     * whose `fields[0xFB]` is a distro marker ([DISTRO_MARKERS]) is no group
     * message and its entries are not read; otherwise a GROUP_ID entry of any
     * type in either form (key 1 of 0xD1, or 0xA0) makes it one
     * ([GroupEntryReading.hasGroupId]), and the id is kept only when it is a
     * str of 32 hex characters, lowercased. A message that arrived directly is
     * read by [directGroupEntries] instead.
     */
    fun groupEntries(): GroupEntryReading {
        val type = getString(FIELD_CUSTOM_TYPE)
        if (type != null && type in DISTRO_MARKERS) {
            return GroupEntryReading(false, null, null, null, groupSync = type == DISTRO_GROUP_TYPE)
        }
        return directGroupEntries()
    }

    /**
     * The group entries of a message that arrived directly, on the device's
     * own address (RFed SPEC §17.14: "On the direct path a distro marker
     * means nothing: the message is classified by its group id"): read as
     * [groupEntries] reads them, but `fields[0xFB]` is not looked at, so a
     * distro marker beside a GROUP_ID leaves it a group message, as iOS and
     * the web read it. An element of a `status` is such a message too (it is
     * checked as if it had arrived directly). [GroupEntryReading.groupSync] is
     * always false here. Until 2026-10-11 a marker beside a GROUP_ID made the
     * message a direct message on this path.
     */
    fun directGroupEntries(): GroupEntryReading {
        val has = (entries[FIELD_RETICHAT] as? Map<*, *>)?.containsKey(RF_GROUP_ID) == true ||
            entries.containsKey(GroupEntry.ID.legacyField)
        if (!has) return GroupEntryReading(false, null, null, null, groupSync = false)
        val id = group(GroupEntry.ID)?.takeIf { HEX32.matches(it) }?.lowercase()
        return GroupEntryReading(true, id, group(GroupEntry.ACTION), group(GroupEntry.NAME), groupSync = false)
    }

    /** As [group], for the one bool entry ([GroupEntry.RELAY_DONE]): msgpack bool only. */
    fun groupBool(entry: GroupEntry): Boolean? {
        require(entry.type == GroupEntry.Type.BOOL) { "$entry is not a bool entry" }
        return (retichatEntry(entry.key) as? Boolean) ?: (entries[entry.legacyField] as? Boolean)
    }

    /**
     * Key [key] of the Retichat field 0xD1, or null when 0xD1 is absent or
     * not a map (§2.1: then it is ignored whole). The display name (key 0) is
     * never read here: it is decoded by the one Rust decoder
     * ([RetichatBridge.displayNameDecode]).
     */
    private fun retichatEntry(key: Int): Any? = (entries[FIELD_RETICHAT] as? Map<*, *>)?.get(key)

    /**
     * Key 10 of the Retichat field 0xD1, a `status`'s GROUP_STATUSES
     * (RFed-spec Group.md, "Member statuses and keys"; DISPLAY_NAMES.md §10):
     * the array as decoded, or null when 0xD1 is absent or not a map, holds no
     * key 10, or holds one that is not an array. Its elements are whatever the
     * sender put in it (a reader checks each on its own: a packed message is a
     * [ByteArray]). Never read from a top-level field: it has no old one, so a
     * released build, which reads only the old fields, skips it.
     */
    fun groupStatuses(): List<Any?>? = retichatEntry(RF_GROUP_STATUSES) as? List<*>

    /** Get a raw binary field, or null. */
    fun getBytes(key: Int): ByteArray? = entries[key] as? ByteArray

    /** Get an integer field, or null. */
    fun getInt(key: Int): Long? = when (val v = entries[key]) {
        is Long -> v
        is Int -> v.toLong()
        else -> null
    }

    /** True if the fields map contains this key at all. */
    fun has(key: Int): Boolean = entries.containsKey(key)

    /** Number of fields in the map. */
    val size: Int get() = entries.size

    /**
     * Extract LXMF file attachments (field 0x05).
     *
     * The LXMF format stores attachments as an array of [filename, data] pairs.
     * Returns a list of (filename, data) pairs.
     */
    fun getFileAttachments(): List<Pair<String, ByteArray>> {
        val list = entries[FIELD_FILE_ATTACHMENTS] as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val pair = entry as? List<*> ?: return@mapNotNull null
            if (pair.size < 2) return@mapNotNull null
            val filename = when (val f = pair[0]) {
                is String -> f
                is ByteArray -> String(f, StandardCharsets.UTF_8)
                else -> "attachment.bin"
            }
            val data = pair[1] as? ByteArray ?: return@mapNotNull null
            filename to data
        }
    }

    /** What a receive path makes of a message's fields map ([receive]). */
    sealed class Received {
        /**
         * The fields as read: [EMPTY] when there were none, or when they could
         * not be read for any reason but a repeated key (then reported to
         * `onFailure`).
         */
        data class Read(val fields: LxmfFields) : Received()

        /**
         * A map in them repeats a key ([RepeatedKeyException], [why] says
         * where): the message is refused whole, with one log line, never shown,
         * stored or applied (RFed SPEC §17.14 "Repeated keys").
         */
        data class Refused(val why: String) : Received()
    }

    companion object {
        /** Standard LXMF field IDs (for reference). */
        const val FIELD_EMBEDDED_LXMS    = 0x01
        const val FIELD_TELEMETRY        = 0x02
        const val FIELD_FILE_ATTACHMENTS = 0x05
        const val FIELD_IMAGE            = 0x06
        const val FIELD_AUDIO            = 0x07
        const val FIELD_THREAD           = 0x08
        const val FIELD_COMMANDS         = 0x09
        const val FIELD_RESULTS          = 0x0A
        const val FIELD_GROUP            = 0x0B
        const val FIELD_TICKET           = 0x0C
        /**
         * LXMF's custom pair (LXMF/LXMF.py FIELD_CUSTOM_TYPE / FIELD_CUSTOM_DATA):
         * a format identifier and its payload. The distro identity transfer
         * (RFed SPEC §17.9) is type [DISTRO_TRANSFER_TYPE] with the key in the
         * data field. Until 2026-09-24 it used 0x0D, which LXMF 1.1.1 defines
         * as FIELD_EVENT.
         */
        const val FIELD_CUSTOM_TYPE      = 0xFB
        const val FIELD_CUSTOM_DATA      = 0xFC
        /**
         * LXMF's FIELD_CUSTOM_META. The distro sent-message copy (RFed SPEC
         * §17.11) is type [DISTRO_SENT_TYPE] with the recipient's address in
         * [FIELD_CUSTOM_DATA] and the sending device's address here, so the
         * sender can recognise its own echo (lxmf_rust::distro mirrors these).
         */
        const val FIELD_CUSTOM_META      = 0xFD
        const val DISTRO_TRANSFER_TYPE   = "rfed.distro.transfer"
        const val DISTRO_SENT_TYPE       = "rfed.distro.sent"
        /** RFed SPEC §17.12's membership message (DistroChannelSync.TYPE). */
        const val DISTRO_CHANNEL_TYPE    = "rfed.distro.channel"
        /** RFed SPEC §17.14's group sync (DistroGroupSync.TYPE). */
        const val DISTRO_GROUP_TYPE      = "rfed.distro.group"
        /**
         * RFed SPEC §17.14: exactly these `fields[0xFB]` values are distro
         * markers; a message unwrapped from the distro that carries one is
         * never a group message, whatever else it carries ([groupEntries]).
         * On the direct path a marker means nothing to the group id
         * ([directGroupEntries]).
         */
        val DISTRO_MARKERS = setOf(DISTRO_TRANSFER_TYPE, DISTRO_SENT_TYPE, DISTRO_CHANNEL_TYPE, DISTRO_GROUP_TYPE)

        private val HEX32 = Regex("^[0-9a-fA-F]{32}$")
        /**
         * FIELD_RETICHAT (LXMF-rust/DISPLAY_NAMES.md §2.1, §10): Retichat's
         * one field number. Its value is a msgpack map with small integer
         * keys: 0 the sender's Message Display Name (added by the router,
         * never by the app, and decoded by the one Rust decoder,
         * [RetichatBridge.displayNameDecode], not here), 1-9 the group
         * entries ([GroupEntry]). A 0xD1 that is not a map is ignored whole.
         */
        const val FIELD_RETICHAT         = 0xD1

        /** §10: the Retichat field's keys (lxmf_rust::retichat_field::RF_*). */
        const val RF_DISPLAY_NAME        = 0
        const val RF_GROUP_ID            = 1
        const val RF_GROUP_MEMBERS       = 2
        const val RF_GROUP_NAME          = 3
        const val RF_GROUP_ACTION        = 4
        const val RF_GROUP_SENDER        = 5
        const val RF_GROUP_RELAY_SEEN    = 6
        const val RF_GROUP_RELAY_FOR     = 7
        const val RF_GROUP_RELAY_DONE    = 8
        const val RF_GROUP_MEMBER_KEYS   = 9
        /**
         * Key 10: array of bin, a `status`'s GROUP_STATUSES (the members' own
         * packed accept and leave messages). The one group entry that is not
         * in [GroupEntry]'s table of nine: it has no old top-level field and is
         * written into the Retichat field in both forms.
         */
        const val RF_GROUP_STATUSES      = 10

        /**
         * §10 transition: false sends group entries in the old top-level
         * fields 0xA0-0xA8, which released apps read; true sends them only in
         * the Retichat field. Readers take both either way ([group]). Set to
         * true in every client together, around 2026-10-26 (with
         * DELIVERY_PACKET_PROOF = Required). All writes go through
         * [com.newendian.retichat.service.GroupFields].
         */
        const val GROUP_ENTRIES_IN_RETICHAT_FIELD = false

        /** Empty fields instance. */
        val EMPTY = LxmfFields(emptyMap())

        /**
         * Read a msgpack-encoded fields map from raw bytes, throwing on bytes
         * that cannot be read: [RepeatedKeyException] for a map that repeats a
         * key, another Exception for anything else malformed. Null or empty
         * input, or a value that is not a map, is [EMPTY].
         *
         * The bytes are the sender's (a distro message's map as packed, a
         * direct message's re-encoded by rmpv, which keeps ext values), so no
         * header is trusted: ext values are stepped over whole, and every
         * length and count is checked against the bytes left before anything
         * is read or allocated. Containers are read down to [KEPT_DEPTH], the
         * deepest any accessor looks; a container below that is stepped over
         * whole, without recursion and without a depth limit, as iOS's
         * `skipValue` steps over it, and reads as no value. Until 2026-10-11 a
         * container nested deeper than 64 anywhere, in a field nothing reads,
         * made the whole map unreadable, so a group message was filed as a
         * fieldless DM.
         *
         * A map that repeats a key is never read here (RFed SPEC §17.14
         * "Repeated keys"; James, 2026-10-11: parity with Python LXMF). The
         * native layer refuses such a message whole, with its one log line,
         * before the app is given any of its bytes: the router's unpack on the
         * direct path, the distro unwrap, the group receipt and the channel
         * codec (LXMF-rust `msgpack_keys`, with Python's key equality). This
         * reader is the backstop for a native library built before that
         * (LXMF-rust 16a9902): a map it reads (down to [KEPT_DEPTH]) that
         * repeats an integer key, the keys it compares, throws
         * [RepeatedKeyException], and a receive path ([receive]) refuses the
         * message whole for it. It never picks one entry of a repeated key.
         * From 2026-10-11 (5f9edff's copy) to 4d337d1 it kept the first entry,
         * and 0xFB-0xFD of the fields map from their last; before that the last
         * of every key. From 4d337d1 to this change a repeat gave [EMPTY], and
         * the message went on as a fieldless DM.
         */
        fun read(raw: ByteArray?): LxmfFields {
            if (raw == null || raw.isEmpty()) return EMPTY
            val map = readMap(ByteBuffer.wrap(raw), 1) ?: return EMPTY
            return LxmfFields(map)
        }

        /**
         * [read], with [EMPTY] for bytes it cannot read, a repeated key
         * included. Not for a receive path: a message's fields are read with
         * [receive], which refuses a repeated key and reports the rest.
         */
        fun decode(raw: ByteArray?): LxmfFields = try {
            read(raw)
        } catch (e: Exception) {
            EMPTY
        }

        /**
         * A message's fields as every receive path reads them (ChatRepository's
         * direct and distro paths, a `status` element): [read] behind a
         * backstop. A repeated key is [Received.Refused], and the caller drops
         * the message with one log line. Anything else the decoder throws, an
         * Error included, is reported to [onFailure] and read as no fields: the
         * message is still stored, without its attachments. Before 2026-09-29 a
         * stranger's map made the decoder throw OutOfMemoryError out of
         * RfedDistroClient.handleBlob after the copy was marked seen, so the text
         * was never stored and a /rfed/pull page stopped there. Until 2026-10-11
         * the decoder caught its own exceptions, so [onFailure] never heard of
         * them, and a repeated key read as no fields. [decoder] is [read]; it is
         * a parameter so a test can make it throw.
         */
        fun receive(
            raw: ByteArray?,
            decoder: (ByteArray?) -> LxmfFields = { read(it) },
            onFailure: (Throwable) -> Unit = {},
        ): Received = try {
            Received.Read(decoder(raw))
        } catch (e: RepeatedKeyException) {
            Received.Refused(e.message ?: "a map repeats a key")
        } catch (t: Throwable) {
            onFailure(t)
            Received.Read(EMPTY)
        }

        // ---- Minimal msgpack reader (supports the types LXMF uses) ----

        /**
         * The deepest container an accessor reads (the fields map is 1): the
         * Retichat field's map is 2 and its key 10's array 3; the attachments
         * array (0x05) is 2 and each [name, data] pair 3. A container below
         * this is stepped over whole ([skipValue]) and reads as null, so the
         * reader recurses at most this deep, whatever the bytes nest.
         */
        private const val KEPT_DEPTH = 3

        /** [n] more bytes are there. A 32-bit length read signed can be negative. */
        private fun need(buf: ByteBuffer, n: Long) {
            if (n < 0 || n > buf.remaining()) throw IllegalArgumentException("$n bytes wanted, ${buf.remaining()} left")
        }

        private fun readMap(buf: ByteBuffer, depth: Int): Map<Int, Any?>? {
            if (!buf.hasRemaining()) return null
            val b = buf.get().toInt() and 0xFF
            val count = when {
                b in 0x80..0x8F -> b and 0x0F          // fixmap
                b == 0xDE -> buf.short.toInt() and 0xFFFF  // map 16
                b == 0xDF -> buf.int                       // map 32
                else -> return null
            }
            need(buf, 2L * count)  // a key and a value of a byte at least
            val result = LinkedHashMap<Int, Any?>()
            repeat(count) {
                val key = readValue(buf, depth + 1)
                val value = readValue(buf, depth + 1)
                // Integer keys of any width holding 0..Int.MAX_VALUE. A
                // negative or larger key (a uint64 above 2^63 reads as
                // negative) matches nothing, rather than wrapping onto a
                // small one; string keys never match (§10).
                val intKey = when (key) {
                    is Long -> if (key in 0L..Int.MAX_VALUE.toLong()) key.toInt() else return@repeat
                    else -> return@repeat
                }
                // A repeated key: the message is refused whole, never read from one of its entries ([read], [receive]).
                if (intKey in result) throw RepeatedKeyException("a map at depth $depth repeats the key $intKey")
                result[intKey] = value
            }
            return result
        }

        private fun readValue(buf: ByteBuffer, depth: Int): Any? {
            if (!buf.hasRemaining()) return null
            val b = buf.get().toInt() and 0xFF
            // A map or an array below the deepest container an accessor reads:
            // stepped over whole, from its own first byte, and no value.
            if (depth > KEPT_DEPTH && isContainer(b)) {
                buf.position(buf.position() - 1)
                skipValue(buf)
                return null
            }
            return when {
                // positive fixint (0x00..0x7F)
                b in 0x00..0x7F -> b.toLong()
                // negative fixint (0xE0..0xFF)
                b in 0xE0..0xFF -> (b.toByte()).toLong()
                // fixstr (0xA0..0xBF)
                b in 0xA0..0xBF -> readStringBytes(buf, b and 0x1F)
                // fixmap (0x80..0x8F) — put byte back and recurse
                b in 0x80..0x8F -> {
                    buf.position(buf.position() - 1)
                    readMap(buf, depth)
                }
                // fixarray (0x90..0x9F)
                b in 0x90..0x9F -> readArray(buf, b and 0x0F, depth)
                // nil
                b == 0xC0 -> null
                // false
                b == 0xC2 -> false
                // true
                b == 0xC3 -> true
                // bin 8
                b == 0xC4 -> readBinBytes(buf, (buf.get().toInt() and 0xFF))
                // bin 16
                b == 0xC5 -> readBinBytes(buf, buf.short.toInt() and 0xFFFF)
                // bin 32
                b == 0xC6 -> readBinBytes(buf, buf.int)
                // ext 8, 16, 32: a length, a type byte and the data. None is
                // an LXMF field of ours; step over it whole, so the next
                // value is read from its own first byte.
                b == 0xC7 -> skipExt(buf, (buf.get().toInt() and 0xFF).toLong())
                b == 0xC8 -> skipExt(buf, (buf.short.toInt() and 0xFFFF).toLong())
                b == 0xC9 -> skipExt(buf, buf.int.toLong() and 0xFFFFFFFFL)
                // fixext 1, 2, 4, 8, 16
                b in 0xD4..0xD8 -> skipExt(buf, 1L shl (b - 0xD4))
                // float 32
                b == 0xCA -> buf.float.toDouble()
                // float 64
                b == 0xCB -> buf.double
                // uint 8
                b == 0xCC -> (buf.get().toInt() and 0xFF).toLong()
                // uint 16
                b == 0xCD -> (buf.short.toInt() and 0xFFFF).toLong()
                // uint 32
                b == 0xCE -> (buf.int.toLong() and 0xFFFFFFFFL)
                // uint 64
                b == 0xCF -> buf.long
                // int 8
                b == 0xD0 -> buf.get().toLong()
                // int 16
                b == 0xD1 -> buf.short.toLong()
                // int 32
                b == 0xD2 -> buf.int.toLong()
                // int 64
                b == 0xD3 -> buf.long
                // str 8
                b == 0xD9 -> readStringBytes(buf, buf.get().toInt() and 0xFF)
                // str 16
                b == 0xDA -> readStringBytes(buf, buf.short.toInt() and 0xFFFF)
                // str 32
                b == 0xDB -> readStringBytes(buf, buf.int)
                // array 16
                b == 0xDC -> readArray(buf, buf.short.toInt() and 0xFFFF, depth)
                // array 32
                b == 0xDD -> readArray(buf, buf.int, depth)
                // map 16
                b == 0xDE -> {
                    buf.position(buf.position() - 1)
                    readMap(buf, depth)
                }
                // map 32
                b == 0xDF -> {
                    buf.position(buf.position() - 1)
                    readMap(buf, depth)
                }
                else -> {
                    // 0xC1, never used: its one byte, as the iOS decoder steps it
                    null
                }
            }
        }

        /** Step over an ext value's type byte and [len] data bytes. */
        private fun skipExt(buf: ByteBuffer, len: Long): Any? {
            need(buf, 1 + len)
            buf.position(buf.position() + 1 + len.toInt())
            return null
        }

        private fun readStringBytes(buf: ByteBuffer, len: Int): String {
            need(buf, len.toLong())
            val bytes = ByteArray(len)
            buf.get(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun readBinBytes(buf: ByteBuffer, len: Int): ByteArray {
            need(buf, len.toLong())
            val bytes = ByteArray(len)
            buf.get(bytes)
            return bytes
        }

        private fun readArray(buf: ByteBuffer, count: Int, depth: Int): List<Any?> {
            need(buf, count.toLong())  // a byte per element at least
            val result = ArrayList<Any?>()
            repeat(count) { result.add(readValue(buf, depth + 1)) }
            return result
        }

        /** A fixmap, fixarray, array 16/32 or map 16/32 header byte. */
        private fun isContainer(b: Int): Boolean = b in 0x80..0x9F || b in 0xDC..0xDF

        /** A 32-bit length or count, unsigned. */
        private fun u32(buf: ByteBuffer): Long = buf.int.toLong() and 0xFFFFFFFFL

        /** Step over [n] bytes, checked against the bytes left. */
        private fun skipBytes(buf: ByteBuffer, n: Long) {
            need(buf, n)
            buf.position(buf.position() + n.toInt())
        }

        /**
         * Step over exactly one value of any type, containers included, without
         * recursion and without a depth limit (as iOS LxmfFields.swift's
         * `skipValue`): [pending] counts the values still to step over, and a
         * container adds its elements (a map two per entry). Each value takes a
         * byte at least, so more pending than bytes left is malformed, and
         * nothing is allocated. Maps stepped over are not read, so their keys
         * are not compared here; the native layer refuses a repeat in them.
         */
        private fun skipValue(buf: ByteBuffer) {
            var pending = 1L
            while (pending > 0) {
                pending--
                need(buf, 1)
                val b = buf.get().toInt() and 0xFF
                when {
                    b <= 0x7F || b >= 0xE0 -> {}                          // fixints
                    b in 0x80..0x8F -> pending += 2L * (b and 0x0F)       // fixmap
                    b in 0x90..0x9F -> pending += (b and 0x0F).toLong()   // fixarray
                    b in 0xA0..0xBF -> skipBytes(buf, (b and 0x1F).toLong()) // fixstr
                    b in 0xC0..0xC3 -> {}                                 // nil, 0xC1, false, true
                    b == 0xC4 || b == 0xD9 -> { need(buf, 1); skipBytes(buf, (buf.get().toInt() and 0xFF).toLong()) }
                    b == 0xC5 || b == 0xDA -> { need(buf, 2); skipBytes(buf, (buf.short.toInt() and 0xFFFF).toLong()) }
                    b == 0xC6 || b == 0xDB -> { need(buf, 4); skipBytes(buf, u32(buf)) }
                    b == 0xC7 -> { need(buf, 1); skipBytes(buf, 1L + (buf.get().toInt() and 0xFF)) }   // ext 8
                    b == 0xC8 -> { need(buf, 2); skipBytes(buf, 1L + (buf.short.toInt() and 0xFFFF)) } // ext 16
                    b == 0xC9 -> { need(buf, 4); skipBytes(buf, 1L + u32(buf)) }                       // ext 32
                    b == 0xCA || b == 0xCE || b == 0xD2 -> skipBytes(buf, 4)
                    b == 0xCB || b == 0xCF || b == 0xD3 -> skipBytes(buf, 8)
                    b == 0xCC || b == 0xD0 -> skipBytes(buf, 1)
                    b == 0xCD || b == 0xD1 -> skipBytes(buf, 2)
                    b in 0xD4..0xD8 -> skipBytes(buf, 1L + (1L shl (b - 0xD4))) // fixext
                    b == 0xDC -> { need(buf, 2); pending += (buf.short.toInt() and 0xFFFF).toLong() }
                    b == 0xDD -> { need(buf, 4); pending += u32(buf) }
                    b == 0xDE -> { need(buf, 2); pending += 2L * (buf.short.toInt() and 0xFFFF) }
                    b == 0xDF -> { need(buf, 4); pending += 2L * u32(buf) }
                }
                need(buf, pending)
            }
        }
    }
}

/**
 * A map the fields reader reads repeats a key ([LxmfFields.read]): the
 * message is refused whole (RFed SPEC §17.14 "Repeated keys").
 */
class RepeatedKeyException(message: String) : IllegalArgumentException(message)

/**
 * [LxmfFields.groupEntries]: the keys LXMF-rust's distro unwrap reports for
 * groups (`has_group_id`, `group_id`, `group_action`, `group_name`, and
 * whether the message is a group sync), read here from a fields map; and
 * [LxmfFields.directGroupEntries], the same keys as the direct path reads
 * them. [hasGroupId], not [groupId], routes a message to the group branch: a
 * malformed id is refused there.
 */
data class GroupEntryReading(
    val hasGroupId: Boolean,
    val groupId: String?,
    val action: String?,
    val name: String?,
    val groupSync: Boolean,
)

/**
 * DISPLAY_NAMES.md §10: the nine group entries, each with its key in the
 * Retichat field 0xD1, the old top-level field it had, and its type
 * (lxmf_rust::retichat_field::GROUP_ENTRIES). Group semantics: RFed-spec
 * Group.md.
 */
enum class GroupEntry(val key: Int, val legacyField: Int, val type: Type) {
    ID(LxmfFields.RF_GROUP_ID, 0xA0, Type.STR),                    // 32-hex group identifier
    MEMBERS(LxmfFields.RF_GROUP_MEMBERS, 0xA1, Type.STR),          // comma-sep member hashes (invite only)
    NAME(LxmfFields.RF_GROUP_NAME, 0xA2, Type.STR),                // human-readable group name
    ACTION(LxmfFields.RF_GROUP_ACTION, 0xA3, Type.STR),            // invite|accept|leave|relay_req|relay_done|status
    SENDER(LxmfFields.RF_GROUP_SENDER, 0xA4, Type.STR),            // original sender hex
    RELAY_SEEN(LxmfFields.RF_GROUP_RELAY_SEEN, 0xA5, Type.STR),    // comma-sep hashes already delivered
    RELAY_FOR(LxmfFields.RF_GROUP_RELAY_FOR, 0xA6, Type.STR),      // hash of member requesting relay
    RELAY_DONE(LxmfFields.RF_GROUP_RELAY_DONE, 0xA7, Type.BOOL),   // relay-complete confirmation
    MEMBER_KEYS(LxmfFields.RF_GROUP_MEMBER_KEYS, 0xA8, Type.STR);  // one hash:base64-public-key pair per invite chunk; an accept/leave carries its sender's own

    enum class Type { STR, BOOL }
}
