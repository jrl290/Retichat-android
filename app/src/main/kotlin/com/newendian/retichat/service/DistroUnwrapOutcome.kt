package com.newendian.retichat.service

import com.newendian.retichat.bridge.NativeRefusal

/**
 * What the distro unwrap (`RetichatBridge.distroUnwrap`) gave for one
 * fanned-out blob, as [RfedDistroClient.handleBlob] acts on it.
 */
sealed class DistroUnwrapOutcome {
    /**
     * Refused whole: the message's payload holds a map that repeats a key
     * (RFed SPEC §17.14 "Repeated keys", [NativeRefusal]). LXMF-rust logged
     * the one line; the blob is dropped with nothing stored, marked as seen
     * or applied, and no second line.
     */
    object Refused : DistroUnwrapOutcome()

    /** No message for D could be read from it: [error] says why. */
    data class Rejected(val error: String?) : DistroUnwrapOutcome()

    /** Addressed to another distro. */
    object OtherDistro : DistroUnwrapOutcome()

    /** The unwrap's JSON (RFed SPEC §17.14 "Group messages through the distro"). */
    data class Message(val json: String) : DistroUnwrapOutcome()

    companion object {
        /**
         * [json] as the unwrap returned it: null on an error, which [error]
         * reads ([com.newendian.retichat.bridge.RetichatBridge.lastError],
         * asked only then, on the thread that made the call); "" for a blob
         * for another distro.
         */
        fun of(json: String?, error: () -> String?): DistroUnwrapOutcome = when {
            json == null -> error().let { if (NativeRefusal.isRefusal(it)) Refused else Rejected(it) }
            json.isEmpty() -> OtherDistro
            else -> Message(json)
        }
    }
}
