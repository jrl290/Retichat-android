package com.newendian.retichat.bridge

/**
 * RFed SPEC §17.14 "Repeated keys" (James, 2026-10-11: parity with Python
 * LXMF, whose umsgpack raises DuplicateKeyException), "Unhashable keys" and
 * "Nesting" (decided 2026-10-11): a message whose payload holds a map, at any
 * depth, that repeats a key or has a key Python cannot hash (a map, or an array
 * holding one), or that nests past depth 493, is refused whole by the native
 * layer before the app is given any of it. LXMF-rust (`msgpack_keys`) logs the
 * one line that says so, in logcat through the core's log callback, and the
 * call reports the refusal as its error ([RetichatBridge.lastError]).
 *
 * The paths that can report one: the distro unwrap
 * ([RetichatBridge.distroUnwrap]), the group receipt of a `status` element or
 * a held entry ([RetichatBridge.groupUnpackPacked]) and the channel codec
 * ([RetichatBridge.channelLxmUnpack]). On the direct path the router refuses
 * the message itself and the delivery callback is never called for it.
 *
 * The app takes such an error as a refusal: it writes no second line for it,
 * and stores, marks as seen and applies nothing.
 */
object NativeRefusal {
    /**
     * The words every refusal's error text holds: LXMF-rust
     * `msgpack_keys::REFUSED_WHOLE`, pinned by the JNI crate's test
     * (`the_unwrap_and_the_receipt_refuse_a_message_that_repeats_a_key`). The
     * channel codec puts its own prefix before them ("LXMessage::unpack: "),
     * so they are looked for anywhere in the text, not only at its start.
     */
    const val REFUSED_WHOLE = "refused whole:"

    /**
     * Whether [error], a native call's [RetichatBridge.lastError], is such a
     * refusal (logged already, by the native layer). A native library built
     * before LXMF-rust b873c00 refuses only a repeated key; the app's own
     * reader judges the rest ([MsgpackKeys], [LxmfFields.receive]).
     */
    fun isRefusal(error: String?): Boolean = error != null && error.contains(REFUSED_WHOLE)
}
