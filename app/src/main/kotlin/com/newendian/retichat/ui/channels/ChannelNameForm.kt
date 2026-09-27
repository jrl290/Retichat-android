package com.newendian.retichat.ui.channels

import java.security.SecureRandom

/**
 * The root/name rules behind the New Channel form, kept free of Compose so
 * JVM tests can pin them.
 *
 * A channel name is `<root>.<name>`. Public channels use the root
 * [PUBLIC_ROOT]. A private channel's root is whatever the user types; it
 * defaults to [PRIVATE_ROOT_HEX_CHARS] random lowercase hex characters so the
 * name cannot be guessed, and can be replaced with the root of a channel
 * someone else shared (older channels have 8-hex roots; those still work).
 */
object ChannelNameForm {
    const val PUBLIC_ROOT = "public"

    /** 16 hex characters = 64 bits from a CSPRNG. */
    const val PRIVATE_ROOT_HEX_CHARS = 16

    /**
     * The name part: lowercased, normalized to NFC, then only Unicode letters
     * (\p{L}), numbers (\p{N}), "." and "-", per code point. Byte-for-byte the
     * web client's filterChannelChars (Retichat-js lib/channel_name.js) and iOS
     * ChannelNameRules: the channel key derives from these bytes, so a name
     * typed on any client must name the same channel.
     */
    fun filterName(raw: String): String =
        java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFC)
            .replace(NAME_REJECT, "")

    private val NAME_REJECT = Regex("[^\\p{L}\\p{N}.-]")
    private val ROOT_REJECT = Regex("[^\\p{L}\\p{N}-]")

    /** The root: the name rule without "." (a root is one segment). */
    fun filterRoot(raw: String): String =
        java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFC)
            .replace(ROOT_REJECT, "")

    data class Fields(val root: String, val name: String)

    /**
     * A new value typed or pasted into the NAME field.
     *
     * [selectionStart]/[selectionEnd] are the name field's selection just
     * before the edit (the range a paste replaces). The screen passes them so
     * a select-all paste is always seen as a paste over the whole name, even
     * when the pasted text happens to start or end like the old name. Without
     * them the edited range is inferred from the text alone.
     *
     * Private: when the edit enters a "." into a name that had none, or
     * replaces the start of the name with text containing one (a paste over
     * the old value), everything before the first "." becomes the root and the
     * rest stays as the name. So a shared "root.name" pastes in one go, while a
     * name that already holds dots (from pasting "root.team.ops") can still be
     * edited without its first segment being pulled into the root. A "." at
     * the very start of the name has no root before it and never splits, so
     * it cannot wipe the root.
     *
     * Public: a leading "public." is dropped (the root is already "public");
     * any other "x.y" stays in the name as typed.
     */
    fun onNameInput(
        previousName: String,
        input: String,
        isPrivate: Boolean,
        root: String,
        selectionStart: Int? = null,
        selectionEnd: Int? = null,
    ): Fields {
        val name = filterName(input)
        if (!isPrivate) {
            return Fields(root, name.removePrefix("$PUBLIC_ROOT."))
        }
        val dot = name.indexOf('.')
        if (dot <= 0) return Fields(root, name)

        val selStart = selectionStart?.coerceIn(0, previousName.length)
        val selEnd = selectionEnd?.coerceIn(selStart ?: 0, previousName.length)
        // Where the edit starts: never after the old selection's start.
        val start = minOf(name.commonPrefixWith(previousName).length, selStart ?: Int.MAX_VALUE)
        // What the edit kept of the old tail: never any of the old selection.
        val keptTailMax = previousName.length - maxOf(start, selEnd ?: start)
        val suffix = minOf(
            name.substring(start).commonSuffixWith(previousName.substring(start)).length,
            keptTailMax,
        )
        val inserted = name.substring(start, name.length - suffix)
        val split = inserted.contains('.') && (!previousName.contains('.') || start == 0)
        if (!split) return Fields(root, name)
        return Fields(filterRoot(name.substring(0, dot)), name.substring(dot + 1))
    }

    enum class RootProblem { Empty, PublicReserved }

    /** Why a private root cannot be used, or null when it can. */
    fun privateRootProblem(root: String): RootProblem? = when (root) {
        "" -> RootProblem.Empty
        PUBLIC_ROOT -> RootProblem.PublicReserved
        else -> null
    }

    /**
     * The channel name to join, or null when Start/Join must stay disabled
     * (no name yet, or an empty / "public" root in Private mode).
     */
    fun fullChannelName(isPrivate: Boolean, root: String, name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        if (!isPrivate) return "$PUBLIC_ROOT.$n"
        if (privateRootProblem(root) != null) return null
        return "$root.$n"
    }

    /** A fresh private root: [PRIVATE_ROOT_HEX_CHARS] lowercase hex from [random]. */
    fun randomPrivateRoot(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(PRIVATE_ROOT_HEX_CHARS / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
