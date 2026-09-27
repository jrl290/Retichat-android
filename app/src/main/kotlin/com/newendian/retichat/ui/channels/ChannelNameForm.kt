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

    /** The name part: lowercase letters, digits, "." and "-". */
    fun filterName(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '-' }

    /** The root: the name rule without "." (a root is one segment). */
    fun filterRoot(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() || it == '-' }

    data class Fields(val root: String, val name: String)

    /**
     * A new value typed or pasted into the NAME field.
     *
     * Private: when the edit enters a "." into a name that had none (or
     * replaces the start of the name with text containing one — a paste over
     * the old value), everything before the first "." becomes the root and the
     * rest stays as the name. So a shared "root.name" pastes in one go, while a
     * name that already holds dots (from pasting "root.team.ops") can still be
     * edited without its first segment being pulled into the root.
     *
     * Public: a leading "public." is dropped (the root is already "public");
     * any other "x.y" stays in the name as typed.
     */
    fun onNameInput(previousName: String, input: String, isPrivate: Boolean, root: String): Fields {
        val name = filterName(input)
        if (!isPrivate) {
            return Fields(root, name.removePrefix("$PUBLIC_ROOT."))
        }
        val dot = name.indexOf('.')
        if (dot < 0) return Fields(root, name)

        val prefix = name.commonPrefixWith(previousName).length
        val suffix = name.substring(prefix).commonSuffixWith(previousName.substring(prefix)).length
        val inserted = name.substring(prefix, name.length - suffix)
        val split = inserted.contains('.') && (!previousName.contains('.') || prefix == 0)
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
