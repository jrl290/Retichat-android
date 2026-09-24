package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.IdentityShareFormat
import com.newendian.retichat.SharedPeerIdentity
import com.newendian.retichat.bridge.RetichatBridge

/**
 * Contacts added from an `lxma://<hash>:<pubkey>` link carry a public key,
 * so the first message can be encrypted before any announce is heard. The
 * link says nothing about whether the address is a distro: that comes only
 * from the address's own announce (RFed SPEC §17.10, `SF_RFED_DISTRO`),
 * which ChatRepository.onAnnounceReceived records. Until 2026-09-24 the link
 * format itself marked the contact as a distro, which was wrong both ways.
 *
 * The screens that parse the link (QR scan, manual entry, deep link) call
 * [noteShared] with the parsed identity; the add-contact flows, which only
 * carry the hash, call [adopt] to remember the key.
 */
object DistroContacts {
    private const val TAG = "Distro"
    private val pending = mutableMapOf<String, String>()

    fun noteShared(identity: SharedPeerIdentity?) {
        val pub = identity?.publicKeyHex ?: return
        synchronized(pending) { pending[identity.destinationHashHex.lowercase()] = pub }
    }

    /** Remember the public key shared for [destHashHex], if it came from a keyed link. */
    fun adopt(context: Context, destHashHex: String) {
        val hex = destHashHex.lowercase()
        val pub = synchronized(pending) { pending.remove(hex) } ?: return
        val hash = DistroCodec.hexToBytes(hex)
        val key = DistroCodec.hexToBytes(pub)
        if (hash != null && key != null && key.size == 64) {
            RetichatBridge.identityRememberLxmfDelivery(hash, key)
            Log.i(TAG, "contact ${hex.take(8)}: public key remembered from the shared link")
        }
    }
}
