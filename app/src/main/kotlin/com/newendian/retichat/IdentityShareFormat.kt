package com.newendian.retichat

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class SharedPeerIdentity(
    val destinationHashHex: String,
    val publicKeyHex: String? = null,
)

object IdentityShareFormat {
    private const val COLUMBA_SCHEME = "lxma://"
    private const val LEGACY_SCHEME = "lxmf://"

    fun encode(destinationHashHex: String, publicKeyHex: String?): String? {
        val hashHex = sanitizeHex(destinationHashHex)
        if (hashHex.length != 32) return null

        val sanitizedPublicKeyHex = sanitizeHex(publicKeyHex.orEmpty())
        if (sanitizedPublicKeyHex.isEmpty()) {
            return "$LEGACY_SCHEME$hashHex"
        }

        if (sanitizedPublicKeyHex.length != 128) return null
        return "$COLUMBA_SCHEME$hashHex:$sanitizedPublicKeyHex"
    }

    fun parse(rawValue: String): SharedPeerIdentity? {
        val trimmed = decode(rawValue)
            .trim()
            .lowercase()
        if (trimmed.isEmpty() || trimmed.startsWith("lxm://")) return null

        val payload = stripScheme(trimmed)
        val separatorIndex = payload.indexOfFirst { it == ':' || it == '.' }
        val hashPart = if (separatorIndex >= 0) payload.substring(0, separatorIndex) else payload
        val publicKeyPart = if (separatorIndex >= 0 && separatorIndex + 1 < payload.length) {
            payload.substring(separatorIndex + 1)
        } else {
            null
        }

        val hashHex = sanitizeHex(hashPart)
        if (hashHex.length != 32) return null

        val publicKeyHex = publicKeyPart
            ?.let(::sanitizeHex)
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                if (it.length != 128) return null
            }

        return SharedPeerIdentity(
            destinationHashHex = hashHex,
            publicKeyHex = publicKeyHex,
        )
    }

    fun extractDestinationHash(rawValue: String): String? = parse(rawValue)?.destinationHashHex

    fun normalizeDestinationHash(rawValue: String): String {
        val trimmed = decode(rawValue)
            .trim()
            .lowercase()
        if (trimmed.isEmpty() || trimmed.startsWith("lxm://")) return ""

        val payload = stripScheme(trimmed)
        val separatorIndex = payload.indexOfFirst { it == ':' || it == '.' }
        val hashPart = if (separatorIndex >= 0) payload.substring(0, separatorIndex) else payload
        return sanitizeHex(hashPart)
    }

    private fun stripScheme(value: String): String {
        return when {
            value.startsWith(COLUMBA_SCHEME) -> value.removePrefix(COLUMBA_SCHEME)
            value.startsWith(LEGACY_SCHEME) -> value.removePrefix(LEGACY_SCHEME)
            else -> value
        }
    }

    private fun sanitizeHex(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            if (ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F') {
                append(ch.lowercaseChar())
            }
        }
    }

    private fun decode(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            value
        }
    }
}