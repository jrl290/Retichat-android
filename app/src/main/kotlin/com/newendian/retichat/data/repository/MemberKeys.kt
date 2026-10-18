package com.newendian.retichat.data.repository

/**
 * A group member's public key, as the invites, the accept and the group syncs
 * carry it: the contact row's key, else the one the stack holds for the
 * member (an announce), which is where iOS reads it (`bridge.recalledPublicKey`)
 * and what RNS `Identity.recall` answers. A contact added by hash has no key on
 * its row; before 10-18 its group's invites never left.
 */
object MemberKeys {
    /** [stored] when it is a key (128 hex), else [recalled] when that is (64 bytes); lowercase hex, or null. */
    fun pick(stored: String?, recalled: () -> ByteArray?): String? =
        stored?.takeIf { it.length == 128 }?.lowercase()
            ?: recalled()?.takeIf { it.size == 64 }?.joinToString("") { "%02x".format(it) }
}
