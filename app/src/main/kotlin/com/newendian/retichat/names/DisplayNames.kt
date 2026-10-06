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

/**
 * The names a contact has (§5.1). Keyed by the hash the messages come from.
 * [legacyName] is a name migrated from before the three slots whose origin
 * is unknown (§5.4): never written afterwards, and last in the resolver.
 */
data class ContactNames(
    val localName: String? = null,
    val messageName: String? = null,
    val announceName: String? = null,
    val legacyName: String? = null,
)

/**
 * A channel post's label (§5.3): the main label, and the grey secondary text
 * beside it. [secondary] is the poster's channel name when the user has a
 * local name for them, the short hash when the channel name stands as the
 * main label, and null when the poster has no channel name.
 */
data class ChannelLabel(val name: String, val secondary: String?)

object DisplayNames {
    /** `CHANNEL_NAME_REFRESH_SECS` (§4.2), in milliseconds. */
    const val CHANNEL_NAME_REFRESH_MS: Long = 24L * 60 * 60 * 1000

    /** §5.3: the first 8 hex characters followed by `…`, on every surface. */
    fun shortHash(hashHex: String): String = hashHex.lowercase().take(8) + "…"

    /** §5.3 contact: `localName ?? messageName ?? announceName ?? legacyName ?? shortHash`. */
    fun contact(names: ContactNames?, hashHex: String): String =
        names?.localName.nonEmpty()
            ?: names?.messageName.nonEmpty()
            ?: names?.announceName.nonEmpty()
            ?: names?.legacyName.nonEmpty()
            ?: shortHash(hashHex)

    /**
     * §5.1 `localName` from a rename: the typed text cleaned by §3 ([clean]
     * is the one Rust cleaner, as for the three own names), so a pasted bidi
     * override or control character never reaches a label and the 64
     * character cap applies. Text that cleans to nothing clears the local
     * name, and the contact shows its provided name again.
     */
    fun localName(raw: String, clean: (String) -> String?): String? = clean(raw).nonEmpty()

    /**
     * §5.3 channel post. Channel names are public and anyone can pick any
     * name, so a channel name never stands alone:
     *
     * | The poster has | Main label | Secondary |
     * |---|---|---|
     * | a channelName and a localName | localName | channelName |
     * | a channelName, no localName | channelName | shortHash |
     * | no channelName | [contact] | none |
     */
    fun channelPost(channelName: String?, names: ContactNames?, hashHex: String): ChannelLabel {
        val fromChannel = channelName.nonEmpty() ?: return ChannelLabel(contact(names, hashHex), null)
        val local = names?.localName.nonEmpty()
        return if (local != null) {
            ChannelLabel(local, fromChannel)
        } else {
            ChannelLabel(fromChannel, shortHash(hashHex))
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
     *
     * Order: [currentAt] is the LXMF timestamp (seconds) of the message that
     * last set or cleared the name (`messageNameAt`, null before any), and
     * [messageAt] this message's. Only a newer message is accepted: a
     * propagated copy landing after a later direct one must not bring an old
     * name back, since the sender's ledger would then never resend the new
     * one. [NameUpdate.Set] means accepted, even when it repeats the current
     * name or clear: the caller drops `legacyName` and, for a validated
     * message only, records [messageAt] as `messageNameAt`
     * ([recordsMessageTime]).
     */
    fun acceptMessageName(
        current: String?,
        currentAt: Double?,
        field: NameField,
        unverifiedReason: Int,
        messageAt: Double,
    ): NameUpdate {
        if (field == NameField.Absent || !messageAt.isFinite()) return NameUpdate.Unchanged
        if (currentAt != null && messageAt <= currentAt) return NameUpdate.Unchanged
        return when (field) {
            NameField.Absent -> NameUpdate.Unchanged
            NameField.Clear ->
                if (unverifiedReason == Signature.VALIDATED) NameUpdate.Set(null) else NameUpdate.Unchanged
            is NameField.Name -> when (unverifiedReason) {
                Signature.VALIDATED -> NameUpdate.Set(field.name)
                Signature.SOURCE_UNKNOWN ->
                    if (current == null) NameUpdate.Set(field.name) else NameUpdate.Unchanged
                else -> NameUpdate.Unchanged
            }
        }
    }

    /**
     * Whether accepting a 0xD1 with [unverifiedReason] records the message's
     * time as `messageNameAt`: only a validated one. A source-unknown name
     * only fills an empty slot, and nobody vouches for its timestamp; if it
     * recorded one far in the future, every later validated name from the
     * real sender would count as older and be ignored for good. Left
     * unrecorded, the next validated name newer than the last validated one
     * replaces it, as the §5.2 table says.
     */
    fun recordsMessageTime(unverifiedReason: Int): Boolean = unverifiedReason == Signature.VALIDATED

    /**
     * §5.2 channel posts: the unpack reports a name only after the key
     * binding and the signature checked out, so the post's 0xD1 sets or
     * clears the poster's `channelName` in that channel. The same order as
     * [acceptMessageName], per (channel, sender): [postAt] is the post's
     * timestamp and [currentAt] that of the post that last named or cleared
     * it (0 before any); only a newer post is accepted, so an older post
     * (history pulled late) does not undo a newer name. A newer post that
     * repeats the name (or the clear) is still a [NameUpdate.Set], so the
     * caller records its timestamp; otherwise an older clear or name pulled
     * later would win over it.
     */
    fun acceptChannelName(current: String?, currentAt: Long, field: NameField, postAt: Long): NameUpdate {
        if (postAt <= currentAt) return NameUpdate.Unchanged
        return when (field) {
            NameField.Absent -> NameUpdate.Unchanged
            NameField.Clear -> NameUpdate.Set(null)
            is NameField.Name -> NameUpdate.Set(field.name)
        }
    }

    /**
     * §5.1 announces: the announce name replaces `announceName` every time,
     * and an announce with no name clears it. The router has already cleaned
     * it and turned "Anonymous Peer" into none. An announce carrying a name
     * also drops a migrated [legacy] name, so a [NameUpdate.Set] with a name
     * tells the caller to drop it (it is a Set even when the announce name
     * is unchanged but a legacy name is still held).
     */
    fun acceptAnnounceName(current: String?, announced: String?, legacy: String? = null): NameUpdate {
        val next = announced.nonEmpty()
        return if (next == current && (next == null || legacy == null)) NameUpdate.Unchanged
        else NameUpdate.Set(next)
    }

    /**
     * §5.4 white space is §3's: Unicode White_Space and nothing else.
     * Kotlin's `trim()` is not that set (it also removes U+001C-U+001F and
     * keeps U+0085), so it is never used on a stored name.
     */
    fun isWhiteSpace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c == '\u0085' || c == '\u00A0' || c == '\u1680' ||
            c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' || c == '\u202F' ||
            c == '\u205F' || c == '\u3000'

    /** [name] with §3 white space trimmed from both ends. */
    fun trimWhiteSpace(name: String): String = name.trim { isWhiteSpace(it) }

    /**
     * §5.4 placeholders, dropped wherever the name was not typed by the user:
     * hash forms (8 to 32 hex, with or without a leading `?` or a trailing
     * `…`), "Retichat", "Retichat Web" and "Anonymous Peer", all
     * case-insensitive, and the old web node defaults ([isWebNodeDefault]),
     * surrounding white space (§3's) ignored. The migrations run the same
     * rule in SQL ([com.newendian.retichat.data.db.NamesMigration.placeholderSql]);
     * the JVM tests check both against the shared vectors.
     */
    fun isPlaceholder(name: String): Boolean {
        val t = trimWhiteSpace(name)
        val v = t.lowercase()
        return v in APP_PLACEHOLDERS || HASH_PLACEHOLDER.matches(v) || isWebNodeDefault(t)
    }

    /** "Retichat", "Retichat Web" and "Anonymous Peer", lowercased. */
    val APP_PLACEHOLDERS: List<String> = listOf("retichat", "retichat web", "anonymous peer")

    private val HASH_PLACEHOLDER = Regex("\\??[0-9a-f]{8,32}\u2026?")

    /**
     * §5.4: an old web node default, "Retichat Web (" + one or more
     * characters + ")", ASCII case-insensitive, surrounding white space
     * trimmed ("Retichat Web (retichat)", and as announced until 2026-09-23
     * "Retichat Web (retichat) (0123456789ab)"). Nobody types one, so it is a
     * placeholder even where a name may have been typed.
     */
    fun isWebNodeDefault(name: String): Boolean = WEB_NODE_DEFAULT.matches(asciiLower(trimWhiteSpace(name)))

    private val WEB_NODE_DEFAULT = Regex("retichat web \\(.+\\)", RegexOption.DOT_MATCHES_ALL)

    /**
     * ASCII case folding only, as §5.4 asks: Kotlin's IGNORE_CASE is Unicode
     * case folding (it would match a dotless ı to an i).
     */
    private fun asciiLower(s: String): String =
        buildString(s.length) { s.forEach { append(if (it in 'A'..'Z') it + ('a' - 'A') else it) } }

    /**
     * §5.4: the old web announce suffix. Until 2026-09-23 the web announced
     * every name as "<name> (<first 12 hex of the lxmf.delivery hash>)". A
     * trailing " (" + 12 hex + ")" is stripped when, and only when, the 12 hex
     * are the first 12 of [ownHash], the contact's own hash (ASCII
     * case-insensitive), surrounding white space trimmed, with something
     * before it; what is left is returned trimmed. One suffix, the last. Any
     * other [name] is returned as it is. What is left meets the placeholder
     * rules like any name ("Retichat (0123456789ab)" leaves a placeholder).
     */
    fun stripOwnHashSuffix(name: String, ownHash: String): String {
        val prefix = ownHash.take(12)
        if (!OWN_HASH_PREFIX.matches(prefix)) return name
        val m = OWN_HASH_SUFFIX.matchEntire(trimWhiteSpace(name)) ?: return name
        if (asciiLower(m.groupValues[2]) != asciiLower(prefix)) return name
        return trimWhiteSpace(m.groupValues[1])
    }

    private val OWN_HASH_PREFIX = Regex("[0-9a-fA-F]{12}")
    private val OWN_HASH_SUFFIX = Regex("(.+) \\(([0-9a-fA-F]{12})\\)", RegexOption.DOT_MATCHES_ALL)

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

    /** What a received post teaches the send rule: the digest to record as the last one included, and its time. */
    data class LearnedPostName(val digestHex: String, val at: Long)

    /**
     * §4.2 "Learning from the channel" (narrowed 2026-10-03). A post this
     * device received in a channel from its current posting identity (the
     * echo of its own post, or one a sibling device signed with the same
     * distro), after the key binding and the signature passed, carries
     * [field], key 0 of its 0xD1, and was made at [postAtMs]. It is recorded,
     * as what readers last got and when, only when it is later than
     * [lastIncludedAt] (null when nothing is recorded) and its value is the
     * one this device would send itself now:
     *  - `Name(s)` where `s` is this device's Channel Display Name [ownName]
     *    (the same digest), or
     *  - `Clear` while [ownName] is unset.
     * Any other post teaches nothing: one with no key 0, a name other than
     * this device's, a name while this device has none, a clear while it has
     * one. A device never records, and so never clears, a name it does not
     * hold: with no name it would otherwise learn a sibling's and clear it on
     * its next post, after each of the sibling's posts; two devices with
     * different names would each find the other's recorded and include their
     * own on every post. Returns null when nothing is learned.
     */
    fun learnedPostName(ownName: String?, field: NameField, postAtMs: Long, lastIncludedAt: Long?): LearnedPostName? {
        if (postAtMs <= (lastIncludedAt ?: 0L)) return null
        val own = ownName.nonEmpty()
        val digest = if (own != null) {
            if (field is NameField.Name && digestHex(field.name) == digestHex(own)) digestHex(own) else null
        } else {
            if (field is NameField.Clear) EMPTY_DIGEST_HEX else null
        }
        return digest?.let { LearnedPostName(it, postAtMs) }
    }

    private fun String?.nonEmpty(): String? = this?.takeIf { it.isNotEmpty() }
}
