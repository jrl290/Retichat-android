package com.newendian.retichat.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.MessageDigest

/**
 * Pure-Kotlin canonical channel-hash derivation.
 *
 * This mirrors **exactly** the iOS Swift implementation in
 * `Retichat-ios/Retichat/Services/RfedChannelClient.swift`:
 *
 * ```swift
 * static func channelHash(name: String) throws -> Data {
 *     let seed = Data(SHA256.hash(data: Data(name.utf8)))
 *     let x25519Pub = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: seed)
 *                         .publicKey.rawRepresentation
 *     let ed25519Pub = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
 *                         .publicKey.rawRepresentation
 *     let bundle = x25519Pub + ed25519Pub
 *     return Data(SHA256.hash(data: bundle).prefix(16))
 * }
 * ```
 *
 * It also matches `rfed-channel-cli` (`channel_hash_16(name)` in
 * `rfed-channel-cli/src/main.rs`), which is the source-of-truth reference
 * used by the rfed node itself.
 *
 * Owning this derivation in Kotlin (rather than going through the JNI/Rust
 * `Identity::hash` path) means the channel id used for routing/subscribe/DB
 * keys is independent of any FFI-side regression. We also overwrite the
 * 16-byte channel_id_hash slot in the LXMF wire payload before sending,
 * so on-wire bytes always agree with what we stored locally and what
 * iOS/CLI peers expect.
 */
object ChannelHash {

    /**
     * Derive the 16-byte canonical channel hash for a channel name (e.g.
     * "public.general"). Returns 16 bytes; never null.
     */
    fun compute(name: String): ByteArray {
        val seed = sha256(name.toByteArray(Charsets.UTF_8))

        // X25519: BouncyCastle's X25519PrivateKeyParameters.generatePublicKey()
        // performs the canonical scalarmult_base on the (clamped) seed,
        // matching CryptoKit's Curve25519.KeyAgreement.
        val x25519Pub = X25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

        // Ed25519: same — from the raw 32-byte seed, derive the verifying key
        // exactly as CryptoKit's Curve25519.Signing does.
        val ed25519Pub = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

        val bundle = ByteArray(64)
        System.arraycopy(x25519Pub, 0, bundle, 0, 32)
        System.arraycopy(ed25519Pub, 0, bundle, 32, 32)

        val full = sha256(bundle)
        return full.copyOfRange(0, 16)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
