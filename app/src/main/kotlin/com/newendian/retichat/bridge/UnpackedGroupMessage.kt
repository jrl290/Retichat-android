package com.newendian.retichat.bridge

import java.nio.ByteBuffer

/**
 * An element of a `status`, or an entry held for its sender's key, unpacked and
 * checked as if it had arrived directly (RFed-spec Group.md, "Member statuses
 * and keys"): what `RetichatBridge.groupUnpackPacked` returns, read from the
 * buffer `nativeGroupUnpackPacked` writes (`group_status::encode_element` in
 * the JNI crate, which documents it; both pin it to one literal, see
 * `GroupStatusJniContractTest`). All numbers are big endian:
 *
 * | bytes | |
 * |---|---|
 * | 0 | [signature]: 0 validated, 1 source unknown, 2 invalid (`names.Signature`) |
 * | 1 | 1 when a bound sender key follows, else 0 |
 * | 2..18 | [destination] |
 * | 18..34 | [source] |
 * | 34..66 | [hash], the LXMF message hash |
 * | 66..74 | [timestamp], f64 |
 * | 74..138 | [senderKey], only when byte 1 is 1 |
 * | then 4 | the length of the fields, u32 |
 * | then n | [fieldsRaw], the LXMF fields map, msgpack |
 *
 * [signature] is what the signature is worth as the router reports any:
 * validated under the key held for [source], or under the key the message
 * carries for its own sender when none is held and that key binds; invalid
 * when it does not verify under either; source unknown when no key is held
 * and none that binds is carried. [senderKey] is that carried key (64 bytes),
 * bound or not held.
 */
class UnpackedGroupMessage(
    val signature: Int,
    val destination: ByteArray,
    val source: ByteArray,
    val hash: ByteArray,
    val timestamp: Double,
    val senderKey: ByteArray?,
    val fieldsRaw: ByteArray,
) {
    companion object {
        const val HASH_LENGTH = 16
        const val MESSAGE_HASH_LENGTH = 32
        const val KEY_LENGTH = 64

        /** The fixed bytes before the optional key: verdict, key flag, three hashes and the time. */
        const val FIXED_LENGTH = 2 + 2 * HASH_LENGTH + MESSAGE_HASH_LENGTH + 8

        /** The unpacked message the native buffer [bytes] holds, or null when it is not one it writes. */
        fun parse(bytes: ByteArray): UnpackedGroupMessage? {
            if (bytes.size < FIXED_LENGTH + 4) return null
            val buf = ByteBuffer.wrap(bytes)
            val signature = buf.get().toInt() and 0xFF
            if (signature !in 0..2) return null
            val hasKey = when (buf.get().toInt() and 0xFF) {
                0 -> false
                1 -> true
                else -> return null
            }
            val destination = ByteArray(HASH_LENGTH).also { buf.get(it) }
            val source = ByteArray(HASH_LENGTH).also { buf.get(it) }
            val hash = ByteArray(MESSAGE_HASH_LENGTH).also { buf.get(it) }
            val timestamp = buf.double
            val key = if (hasKey) {
                if (buf.remaining() < KEY_LENGTH + 4) return null
                ByteArray(KEY_LENGTH).also { buf.get(it) }
            } else null
            if (buf.remaining() < 4) return null
            val length = buf.int
            if (length < 0 || length != buf.remaining()) return null
            val fields = ByteArray(length).also { buf.get(it) }
            return UnpackedGroupMessage(signature, destination, source, hash, timestamp, key, fields)
        }
    }
}
