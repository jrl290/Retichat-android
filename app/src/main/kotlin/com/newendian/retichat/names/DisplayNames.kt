package com.newendian.retichat.names

import java.security.MessageDigest

/**
 * The display-name contract (LXMF-rust/DISPLAY_NAMES.md), the parts that run
 * in the app. Cleaning (§3) and the 0xD1 decode live once in Rust and are
 * reached through RetichatBridge; everything here is pure Kotlin so the rules
 * are unit-tested on the JVM.
 */

/** A decoded field 0xD1 (§3): absent, "I have no name now", or a cleaned name. */
sealed class NameField {
    data object Absent : NameField()
    data object Clear : NameField()
    data class Name(val name: String) : NameField()

    /** The JNI state byte: 0 absent, 1 clear, 2 name. */
    val state: Int
        get() = when (this) {
            Absent -> 0
            Clear -> 1
            is Name -> 2
        }

    companion object {
        /**
         * Parse the bridge trailer `name_state u8 | name_len u16 BE | name`
         * (nativeDisplayNameDecode, the channel unpack trailer). A missing or
         * short trailer, an unknown state, or a state-2 name that is empty is
         * [Absent]: it never lets a name through.
         */
        fun fromTrailer(bytes: ByteArray?, offset: Int = 0): NameField {
            if (bytes == null || offset < 0 || bytes.size < offset + 3) return Absent
            val state = bytes[offset].toInt() and 0xff
            val len = ((bytes[offset + 1].toInt() and 0xff) shl 8) or (bytes[offset + 2].toInt() and 0xff)
            if (bytes.size < offset + 3 + len) return Absent
            return when (state) {
                1 -> Clear
                2 -> {
                    val name = String(bytes, offset + 3, len, Charsets.UTF_8)
                    if (name.isEmpty()) Absent else Name(name)
                }
                else -> Absent
            }
        }

        /** From the distro unwrap JSON (`display_name_state`, `display_name`). */
        fun fromState(state: Int, name: String?): NameField = when (state) {
            1 -> Clear
            2 -> if (name.isNullOrEmpty()) Absent else Name(name)
            else -> Absent
        }
    }
}

/** Why a message's signature is not validated (the router's `unverified_reason`). */
object Signature {
    const val VALIDATED = 0
    const val SOURCE_UNKNOWN = 1
    const val INVALID = 2

    /**
     * The distro unwrap JSON carries `signature_validated` and
     * `unverified_reason`. A message that is not validated and gives no
     * reason (or one we do not know) is treated as invalid, which never lets
     * a name through.
     */
    fun reason(validated: Boolean, unverifiedReason: Int?): Int = when {
        validated -> VALIDATED
        unverifiedReason == SOURCE_UNKNOWN -> SOURCE_UNKNOWN
        else -> INVALID
    }
}

/** A change to a stored name slot: leave it, or set it (null clears). */
sealed class NameUpdate {
    data object Unchanged : NameUpdate()
    data class Set(val name: String?) : NameUpdate()
}

/** The names a contact has (§5.1). Keyed by the hash the messages come from. */
data class ContactNames(
    val localName: String? = null,
    val messageName: String? = null,
    val announceName: String? = null,
)

/** A channel post's label (§5.3): the name, and the short hash beside it when the name is the poster's own channel name. */
data class ChannelLabel(val name: String, val secondaryHash: String?)

object DisplayNames {
    /** `CHANNEL_NAME_REFRESH_SECS` (§4.2), in milliseconds. */
    const val CHANNEL_NAME_REFRESH_MS: Long = 24L * 60 * 60 * 1000

    /** §5.3: the first 8 hex characters followed by `…`, on every surface. */
    fun shortHash(hashHex: String): String = hashHex.lowercase().take(8) + "…"

    /** §5.3 contact: `localName ?? messageName ?? announceName ?? shortHash`. */
    fun contact(names: ContactNames?, hashHex: String): String =
        names?.localName.nonEmpty()
            ?: names?.messageName.nonEmpty()
            ?: names?.announceName.nonEmpty()
            ?: shortHash(hashHex)

    /**
     * §5.3 channel post: `channelName ?? localName ?? messageName ??
     * announceName ?? shortHash`. A label taken from the channel name carries
     * the short hash as secondary text: channel names are public and anyone
     * can pick any name.
     */
    fun channelPost(channelName: String?, names: ContactNames?, hashHex: String): ChannelLabel {
        val fromChannel = channelName.nonEmpty()
        return if (fromChannel != null) {
            ChannelLabel(fromChannel, shortHash(hashHex))
        } else {
            ChannelLabel(contact(names, hashHex), null)
        }
    }

    /**
     * §5.2: what a received 0xD1 does to the sender's `messageName`.
     *
     * | signature | Name(s) | Clear |
     * |---|---|---|
     * | validated | set s | clear |
     * | source unknown | set only if none | ignore |
     * | invalid | ignore | ignore |
     */
    fun acceptMessageName(current: String?, field: NameField, unverifiedReason: Int): NameUpdate =
        when (field) {
            NameField.Absent -> NameUpdate.Unchanged
            NameField.Clear ->
                if (unverifiedReason == Signature.VALIDATED && current != null) NameUpdate.Set(null)
                else NameUpdate.Unchanged
            is NameField.Name -> when (unverifiedReason) {
                Signature.VALIDATED ->
                    if (current == field.name) NameUpdate.Unchanged else NameUpdate.Set(field.name)
                Signature.SOURCE_UNKNOWN ->
                    if (current == null) NameUpdate.Set(field.name) else NameUpdate.Unchanged
                else -> NameUpdate.Unchanged
            }
        }

    /**
     * §5.2 channel posts: the unpack reports a name only after the key
     * binding and the signature checked out, so the post's 0xD1 sets or
     * clears the poster's `channelName` in that channel. [postAt] is the
     * post's timestamp and [currentAt] that of the post that set [current]:
     * an older post (history pulled late) does not undo a newer name.
     */
    fun acceptChannelName(current: String?, currentAt: Long, field: NameField, postAt: Long): NameUpdate {
        if (postAt < currentAt) return NameUpdate.Unchanged
        return when (field) {
            NameField.Absent -> NameUpdate.Unchanged
            NameField.Clear -> if (current == null) NameUpdate.Unchanged else NameUpdate.Set(null)
            is NameField.Name -> if (current == field.name) NameUpdate.Unchanged else NameUpdate.Set(field.name)
        }
    }

    /**
     * §5.1 announces: the announce name replaces `announceName` every time,
     * and an announce with no name clears it. The router has already cleaned
     * it and turned "Anonymous Peer" into none.
     */
    fun acceptAnnounceName(current: String?, announced: String?): NameUpdate {
        val next = announced.nonEmpty()
        return if (next == current) NameUpdate.Unchanged else NameUpdate.Set(next)
    }

    /** §4.1: the first 16 bytes of SHA-256 of the cleaned name (none hashes ""). */
    fun digest(name: String?): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((name ?: "").toByteArray(Charsets.UTF_8)).copyOf(16)

    fun digestHex(name: String?): String = digest(name).joinToString("") { "%02x".format(it) }

    /** The digest of the empty name: what a recipient holds after a clear. */
    val EMPTY_DIGEST_HEX: String = digestHex(null)

    /**
     * §4.2 channel send rule. [name] is the cleaned Channel Display Name or
     * null. [lastDigestHex] / [lastIncludedAt] are the channel's persisted
     * state (null when nothing was ever included); [newSenderSinceInclude] is
     * true when a sender not seen before in the channel has posted since
     * [lastIncludedAt].
     */
    fun channelPostName(
        name: String?,
        lastDigestHex: String?,
        lastIncludedAt: Long?,
        newSenderSinceInclude: Boolean,
        nowMs: Long,
    ): NameField {
        val current = name.nonEmpty()
        if (current == null) {
            // Unset: clear once if the readers last got a real name.
            return if (lastDigestHex != null && lastDigestHex != EMPTY_DIGEST_HEX) NameField.Clear
            else NameField.Absent
        }
        val include = lastDigestHex != digestHex(current) ||
            newSenderSinceInclude ||
            lastIncludedAt == null ||
            nowMs - lastIncludedAt > CHANNEL_NAME_REFRESH_MS
        return if (include) NameField.Name(current) else NameField.Absent
    }

    private fun String?.nonEmpty(): String? = this?.takeIf { it.isNotEmpty() }
}
