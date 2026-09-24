package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.IdentityShareFormat
import com.newendian.retichat.SharedPeerIdentity
import com.newendian.retichat.bridge.RetichatBridge

/**
 * Contacts added from an `lxma://<hash>:<pubkey>` link are distro addresses,
 * as the web client treats them (app.js Add Contact paths set `isDistro`).
 * They have no device behind them to answer a direct link, so messages to
 * them go straight to the propagation node, and the key from the link is
 * remembered so the first message can be encrypted before any announce.
 *
 * The screens that parse the link (QR scan, manual entry, deep link) call
 * [noteShared] with the parsed identity; the add-contact flows, which only
 * carry the hash, call [adopt] to persist the mark.
 */
object DistroContacts {
    private const val TAG = "Distro"
    private val pending = mutableMapOf<String, String>()

    fun noteShared(identity: SharedPeerIdentity?) {
        val pub = identity?.publicKeyHex ?: return
        synchronized(pending) { pending[identity.destinationHashHex.lowercase()] = pub }
    }

    /** Persist the distro mark for [destHashHex] if it came from a keyed link. */
    fun adopt(context: Context, destHashHex: String) {
        val hex = destHashHex.lowercase()
        val pub = synchronized(pending) { pending.remove(hex) } ?: return
        UserPreferences.setDistroContact(context, hex, true)
        val hash = DistroCodec.hexToBytes(hex)
        val key = DistroCodec.hexToBytes(pub)
        if (hash != null && key != null && key.size == 64) {
            RetichatBridge.identityRememberLxmfDelivery(hash, key)
        }
        Log.i(TAG, "contact ${hex.take(8)} marked as a distro address")
    }
}
