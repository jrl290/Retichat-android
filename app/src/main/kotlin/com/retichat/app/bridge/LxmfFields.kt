package com.retichat.app.bridge

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
        /** Application-level group field IDs (mirrors iOS LxmfFieldKey). */
        const val GROUP_ID             = 0xA0  // str: 32-hex group identifier
        const val GROUP_MEMBERS        = 0xA1  // str: comma-sep member hashes (invite only)
        const val GROUP_NAME           = 0xA2  // str: human-readable group name
        const val GROUP_ACTION         = 0xA3  // str: "invite"|"accept"|"leave"|"relay_req"|"relay_done"
        const val GROUP_SENDER         = 0xA4  // str: original sender hex
        const val GROUP_RELAY_SEEN     = 0xA5  // str: comma-sep hashes already delivered
        const val GROUP_RELAY_FOR      = 0xA6  // str: hash of member requesting relay
        const val GROUP_RELAY_DONE     = 0xA7  // bool: relay-complete confirmation

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
                val intKey = when (key) {
                    is Long -> key.toInt()
                    is Int -> key
                    else -> return@repeat  // skip non-integer keys
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
