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
         * Decode a msgpack-encoded fields map from raw bytes.
         * Returns [EMPTY] on null/empty input or parse failure.
         */
        fun decode(raw: ByteArray?): LxmfFields {
            if (raw == null || raw.isEmpty()) return EMPTY
            return try {
                val buf = ByteBuffer.wrap(raw)
                val map = readMap(buf) ?: return EMPTY
                LxmfFields(map)
            } catch (e: Exception) {
                EMPTY
            }
        }

        // ---- Minimal msgpack reader (supports the types LXMF uses) ----

        private fun readMap(buf: ByteBuffer): Map<Int, Any?>? {
            if (!buf.hasRemaining()) return null
            val b = buf.get().toInt() and 0xFF
            val count = when {
                b in 0x80..0x8F -> b and 0x0F          // fixmap
                b == 0xDE -> buf.short.toInt() and 0xFFFF  // map 16
                b == 0xDF -> buf.int                       // map 32
                else -> return null
            }
            val result = LinkedHashMap<Int, Any?>(count)
            repeat(count) {
                val key = readValue(buf)
                val value = readValue(buf)
                // Integer keys of any width holding 0..Int.MAX_VALUE. A
                // negative or larger key (a uint64 above 2^63 reads as
                // negative) matches nothing, rather than wrapping onto a
                // small one; string keys never match (§10).
                val intKey = when (key) {
                    is Long -> if (key in 0L..Int.MAX_VALUE.toLong()) key.toInt() else return@repeat
                    else -> return@repeat
                }
                result[intKey] = value
            }
            return result
        }

        private fun readValue(buf: ByteBuffer): Any? {
            if (!buf.hasRemaining()) return null
            val b = buf.get().toInt() and 0xFF
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
                    readMap(buf)
                }
                // fixarray (0x90..0x9F)
                b in 0x90..0x9F -> readArray(buf, b and 0x0F)
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
                b == 0xDC -> readArray(buf, buf.short.toInt() and 0xFFFF)
                // array 32
                b == 0xDD -> readArray(buf, buf.int)
                // map 16
                b == 0xDE -> {
                    buf.position(buf.position() - 1)
                    readMap(buf)
                }
                // map 32
                b == 0xDF -> {
                    buf.position(buf.position() - 1)
                    readMap(buf)
                }
                else -> {
                    // Unknown type — skip
                    null
                }
            }
        }

        private fun readStringBytes(buf: ByteBuffer, len: Int): String {
            val bytes = ByteArray(len)
            buf.get(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun readBinBytes(buf: ByteBuffer, len: Int): ByteArray {
            val bytes = ByteArray(len)
            buf.get(bytes)
            return bytes
        }

        private fun readArray(buf: ByteBuffer, count: Int): List<Any?> {
            val result = ArrayList<Any?>(count)
            repeat(count) { result.add(readValue(buf)) }
            return result
        }
    }
}

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
    ACTION(LxmfFields.RF_GROUP_ACTION, 0xA3, Type.STR),            // invite|accept|leave|relay_req|relay_done
    SENDER(LxmfFields.RF_GROUP_SENDER, 0xA4, Type.STR),            // original sender hex
    RELAY_SEEN(LxmfFields.RF_GROUP_RELAY_SEEN, 0xA5, Type.STR),    // comma-sep hashes already delivered
    RELAY_FOR(LxmfFields.RF_GROUP_RELAY_FOR, 0xA6, Type.STR),      // hash of member requesting relay
    RELAY_DONE(LxmfFields.RF_GROUP_RELAY_DONE, 0xA7, Type.BOOL),   // relay-complete confirmation
    MEMBER_KEYS(LxmfFields.RF_GROUP_MEMBER_KEYS, 0xA8, Type.STR);  // one hash:base64-public-key pair per invite chunk

    enum class Type { STR, BOOL }
}
