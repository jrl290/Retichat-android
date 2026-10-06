package com.newendian.retichat.bridge

import com.newendian.retichat.data.model.hexToBytes

/**
 * What the group code (`data.repository.GroupStatusEngine`) needs of the native
 * layer, behind an interface because the JVM cannot load the library: the app
 * uses [JniGroupNative], the JVM tests a fake that checks real signatures.
 */
interface GroupNative {
    /**
     * An element of a `status`, or an entry held for its sender's key (a
     * member's own packed accept or leave, 97..=2144 bytes), unpacked and
     * checked as if it had arrived directly: what its signature is worth under
     * the key held for its source, else under the key it carries for its own
     * sender when that binds ([UnpackedGroupMessage.signature]). Nothing is
     * remembered. Null when it is not an LXMF message.
     */
    fun unpack(packed: ByteArray): UnpackedGroupMessage?

    /**
     * Remember [publicKey] as the key of the `lxmf.delivery` destination
     * [memberHex], as an invite chunk's key is remembered. False, and nothing
     * remembered, when the key does not produce that destination.
     */
    fun rememberKey(memberHex: String, publicKey: ByteArray): Boolean
}

/** The native implementation: [RetichatBridge]. */
object JniGroupNative : GroupNative {
    override fun unpack(packed: ByteArray): UnpackedGroupMessage? = RetichatBridge.groupUnpackPacked(packed)

    override fun rememberKey(memberHex: String, publicKey: ByteArray): Boolean {
        val hash = runCatching { memberHex.hexToBytes() }.getOrNull() ?: return false
        return RetichatBridge.identityRememberLxmfDelivery(hash, publicKey)
    }
}
