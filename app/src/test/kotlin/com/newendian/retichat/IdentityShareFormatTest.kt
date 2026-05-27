package com.newendian.retichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityShareFormatTest {
    private val hash = "0123456789abcdef0123456789abcdef"
    private val publicKey = (0 until 64).joinToString(separator = "") { "%02x".format(it) }

    @Test
    fun encodeUsesColumbaFormatWhenPublicKeyIsPresent() {
        assertEquals(
            "lxma://$hash:$publicKey",
            IdentityShareFormat.encode(hash, publicKey),
        )
    }

    @Test
    fun encodeUsesLegacyHashFormatWithoutPublicKey() {
        assertEquals(
            "lxmf://$hash",
            IdentityShareFormat.encode(hash, null),
        )
    }

    @Test
    fun parseAcceptsColumbaLegacyAndRawForms() {
        assertEquals(hash, IdentityShareFormat.parse("lxma://$hash:$publicKey")?.destinationHashHex)
        assertEquals(publicKey, IdentityShareFormat.parse("lxma://$hash:$publicKey")?.publicKeyHex)
        assertEquals(hash, IdentityShareFormat.parse("lxma://$hash.$publicKey")?.destinationHashHex)
        assertEquals(hash, IdentityShareFormat.parse("lxmf://$hash")?.destinationHashHex)
        assertEquals(hash, IdentityShareFormat.parse(hash)?.destinationHashHex)
    }

    @Test
    fun normalizeDestinationHashUsesOnlyHashPart() {
        assertEquals(hash, IdentityShareFormat.normalizeDestinationHash("lxma://$hash:$publicKey"))
        assertEquals(hash, IdentityShareFormat.normalizeDestinationHash("lxma://$hash.$publicKey"))
        assertEquals(hash, IdentityShareFormat.normalizeDestinationHash("lxmf://$hash"))
    }

    @Test
    fun parseRejectsPaperFormat() {
        assertNull(IdentityShareFormat.parse("lxm://paper-format"))
    }
}