package com.newendian.retichat.bridge

import androidx.annotation.Keep

/**
 * Callback interface for receiving inbound LXMF messages from the Rust layer.
 *
 * `unverifiedReason` is 0 when the signature validated, 1 when the source is
 * unknown (no key yet), 2 when the signature is invalid. A received 0xD1 name
 * is accepted by it (DISPLAY_NAMES.md §5.2), never by `signatureValid` alone.
 *
 * An accept or a leave of a group carries its sender's own key (RFed-spec
 * Group.md, "Member statuses and keys"). [senderKey] is that key (64 bytes)
 * when it binds to the message's source, and a source the router held no key
 * for is then checked under it, so `unverifiedReason` is 0 or 2 for it as for
 * any signature. [packed] is the message exactly as received (`destination |
 * source | signature | payload`) for an accept or leave of at most 2144 bytes:
 * what a creator keeps. Both are null for every other message. The native
 * side remembers nothing: the key is remembered by the group code, after the
 * group rule has passed.
 *
 * The native side calls exactly this 11-argument signature
 * (`([B[B[BLjava/lang/String;Ljava/lang/String;DZI[B[B[B)V`).
 */
@Keep
interface MessageCallback {
    fun onMessage(
        hash: ByteArray,
        srcHash: ByteArray,
        destHash: ByteArray,
        title: String,
        content: String,
        timestamp: Double,
        signatureValid: Boolean,
        unverifiedReason: Int,
        fieldsRaw: ByteArray,
        senderKey: ByteArray?,
        packed: ByteArray?,
    )
}

/**
 * Callback interface for receiving LXMF delivery announces from the Rust layer.
 * `displayName` is the announce name already cleaned by the router
 * (DISPLAY_NAMES.md §3; "Anonymous Peer" is null), or null when the announce
 * has none.
 */
@Keep
interface AnnounceCallback {
    fun onAnnounce(destHash: ByteArray, displayName: String?)
}

@Keep
interface MessageStateCallback {
    fun onState(hash: ByteArray, state: Int)
}

/**
 * Callback interface for receiving inbound RFed channel blobs (push fanout).
 *
 * Wire format: `blob = channel_hash(16) | inner_blob(*)`.
 * Implementations should look up the channel by `channel_hash` then
 * call [RetichatBridge.channelLxmUnpack] on the full blob to validate
 * the LXMF signature.
 */
@Keep
interface RfedBlobCallback {
    fun onBlob(blob: ByteArray)
}

/**
 * Callback interface for APP_LINK status transitions. Fires on a Rust
 * background thread already attached to the JVM — marshal to a coroutine
 * scope inside `onStatus` if you need to touch UI state.
 *
 * `status` is one of [RetichatBridge.AppLinkStatus] constants, which include
 * the event RECOVERED: a handler that acts on ACTIVE alone is unaffected by it.
 */
@Keep
interface AppLinkStatusCallback {
    fun onStatus(destHash: ByteArray, status: Int)
}

/** Packet callback for DATA received on a persistent APP_LINK. */
@Keep
interface AppLinkPacketCallback {
    fun onPacket(bytes: ByteArray)
}

/**
 * One-shot callback for [RetichatBridge.appLinkRequestAsync].  Fires
 * exactly once: on response, failure, or timeout.
 *
 * `status`: see [RetichatBridge.AppLinkRequestStatus] (0=ok, 1=timeout,
 * 2=failed). `bytes` is non-null only when `status == 0`.
 */
@Keep
interface AppLinkRequestCallback {
    fun onResult(status: Int, bytes: ByteArray?)
}

/** One-shot callback for [RetichatBridge.appLinkSendAsync]. */
@Keep
interface AppLinkSendCallback {
    fun onResult(status: Int)
}

/**
 * Every APP_LINK status change, with the link it is about: [RetichatBridge.propagationLinkEventsRegister].
 * `status` is a [RetichatBridge.AppLinkStatus]; `linkId` is the link's id for
 * ESTABLISHING, ACTIVE (a new link's establishment is its ACTIVE) and
 * RECOVERED (a STALE link heard from its peer again) and null for the rest.
 * Fires on a Rust thread already attached to the JVM and must not block: hand
 * the work to a coroutine and return.
 */
@Keep
interface PropagationLinkCallback {
    fun onLink(destHash: ByteArray, status: Int, linkId: ByteArray?)
}

/**
 * A Reticulum interface came online: [RetichatBridge.interfaceOnlineRegister].
 * Transport's own up-edge (an interface that was offline is online: a TCP
 * reconnect, a radio that came back), told once per edge with the interface's
 * name. It is not Android's "network available", which says only that the
 * system has a network. Fires on a Rust thread already attached to the JVM and
 * must not block: hand the work to a coroutine and return.
 */
@Keep
interface InterfaceOnlineCallback {
    fun onInterfaceOnline(name: String)
}

/**
 * The outcome of one upload on the held propagation link
 * ([RetichatBridge.propagationUpload]): [onProved] when the node proves the
 * packet (or the Resource completes), [onLost] when the packet receipt times
 * out or the Resource fails, [onNeverLeft] when no interface could carry the
 * Resource's first advertisement (it was cancelled on the device, nothing was
 * sent, and the link is kept: DESIGN_PRINCIPLES §3, James 2026-10-06, the
 * Resource case 2026-10-10). Each is called at most once, on a Rust thread; a
 * proof can follow a loss (a late proof), and a never-left comes instead of a
 * loss, never with one. Must not block.
 */
@Keep
interface PropagationUploadCallback {
    fun onProved()
    fun onLost()
    fun onNeverLeft()
}

/**
 * The Bluetooth engine (Reticulum-rust `interfaces::prns_ble`) asking the
 * radio for something: see [com.newendian.retichat.service.RTNodeBluetooth].
 * Called on Rust and Binder threads; implementations hand the work to their
 * own thread and return.
 */
@Keep
interface PrnsBleCallback {
    /** Start or stop scanning for the Prns service. */
    fun onScan(on: Boolean)
    /** Write [data] to characteristic 0 (control) or 1 (data), with response. */
    fun onWrite(link: Long, characteristic: Int, data: ByteArray)
    /** Cancel the connection or connection attempt; no linkClosed after. */
    fun onDisconnect(link: Long)
    /** 0 handshaking, 1 settled, 2 closed, 3 dialling. */
    fun onLinkState(link: Long, state: Int, peer: ByteArray?, interfaceName: String?)
}

/**
 * JNI bridge to the Rust Reticulum + LXMF libraries.
 *
 * All `native*` methods map 1:1 to C-exported JNI functions in
 * `libretichat_jni.so`.  Handle values (`Long`) are opaque references
 * to Rust objects; 0 means error — call [lastError] for details.
 */
@Keep
object RetichatBridge {

    /** Whether the native library loaded successfully. */
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("retichat_jni")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("RetichatBridge", "Native library not available: ${e.message}")
        }
    }

    // ---- Error ----

    fun lastError(): String? = nativeLastError()

    private external fun nativeLastError(): String?

    // ---- Lifecycle ----

    fun init(configDir: String, logLevel: Int = 4): Boolean =
        nativeInit(configDir, logLevel) == 0

    fun shutdown(): Boolean = nativeShutdown() == 0

    private external fun nativeInit(configDir: String, logLevel: Int): Int
    private external fun nativeShutdown(): Int

    // ---- Identity ----

    /** Create a new random identity.  Returns handle (0 = error). */
    fun identityCreate(): Long = nativeIdentityCreate()

    /** Load identity from file.  Returns handle (0 = error). */
    fun identityFromFile(path: String): Long = nativeIdentityFromFile(path)

    /** Load identity from raw private-key bytes.  Returns handle (0 = error). */
    fun identityFromBytes(bytes: ByteArray): Long = nativeIdentityFromBytes(bytes)

    /** Save identity to file. */
    fun identityToFile(handle: Long, path: String): Boolean =
        nativeIdentityToFile(handle, path) == 0

    /** Get the 64-byte public key. */
    fun identityPublicKey(handle: Long): ByteArray? = nativeIdentityPublicKey(handle)

    /** Get the 16-byte truncated identity hash. */
    fun identityHash(handle: Long): ByteArray? = nativeIdentityHash(handle)

    /** Release a handle. */
    fun identityDestroy(handle: Long): Boolean = nativeIdentityDestroy(handle) == 0

    private external fun nativeIdentityCreate(): Long
    private external fun nativeIdentityFromFile(path: String): Long
    private external fun nativeIdentityFromBytes(bytes: ByteArray): Long
    private external fun nativeIdentityToFile(handle: Long, path: String): Int
    private external fun nativeIdentityPublicKey(handle: Long): ByteArray?
    private external fun nativeIdentityHash(handle: Long): ByteArray?
    private external fun nativeIdentityDestroy(handle: Long): Int

    // ---- Destination ----

    /** Compute destination hash for identity + app + aspects. */
    fun destinationHash(idHandle: Long, appName: String, aspects: String): ByteArray? =
        nativeDestinationHash(idHandle, appName, aspects)

    private external fun nativeDestinationHash(
        idHandle: Long, appName: String, aspects: String
    ): ByteArray?

    // ---- Transport ----

    fun transportHasPath(destHash: ByteArray): Boolean =
        nativeTransportHasPath(destHash) == 1

    fun transportIsPathVerifiedThisSession(destHash: ByteArray): Boolean =
        nativeTransportIsPathVerifiedThisSession(destHash) == 1

    fun transportIdentityKnown(destHash: ByteArray): Boolean =
        nativeTransportIdentityKnown(destHash) == 1

    fun identityRememberLxmfDelivery(destHash: ByteArray, publicKey: ByteArray): Boolean =
        nativeIdentityRememberLxmfDelivery(destHash, publicKey) == 0

    fun transportRequestPath(destHash: ByteArray): Boolean =
        nativeTransportRequestPath(destHash) == 0

    fun transportClonePathAndIdentity(sourceHash: ByteArray, destHash: ByteArray): Boolean =
        nativeTransportClonePathAndIdentity(sourceHash, destHash) == 1

    fun transportHopsTo(destHash: ByteArray): Int =
        nativeTransportHopsTo(destHash)

    /** Enable/disable early-dropping of inbound announce packets (opt-in, default off). */
    fun setDropAnnounces(enabled: Boolean) = nativeSetDropAnnounces(enabled)

    private external fun nativeSetDropAnnounces(enabled: Boolean)
    private external fun nativeTransportHasPath(destHash: ByteArray): Int
    private external fun nativeTransportIsPathVerifiedThisSession(destHash: ByteArray): Int
    private external fun nativeTransportIdentityKnown(destHash: ByteArray): Int
    private external fun nativeIdentityRememberLxmfDelivery(destHash: ByteArray, publicKey: ByteArray): Int
    private external fun nativeTransportRequestPath(destHash: ByteArray): Int
    private external fun nativeTransportClonePathAndIdentity(
        sourceHash: ByteArray,
        destHash: ByteArray,
    ): Int
    private external fun nativeTransportHopsTo(destHash: ByteArray): Int

    // ---- Router ----

    /** Create an LXMRouter.  Returns handle (0 = error). */
    fun routerCreate(identityHandle: Long, storagePath: String): Long =
        nativeRouterCreate(identityHandle, storagePath)

    /**
     * Register delivery identity.  Returns destination handle (0 = error).
     * [messageDisplayName] is the initial Message Display Name the router
     * sends in field 0xD1 (DISPLAY_NAMES.md §4.1); it is never announced.
     * "" = none.
     */
    fun routerRegisterDelivery(
        router: Long, identity: Long, messageDisplayName: String, stampCost: Int = -1
    ): Long = nativeRouterRegisterDelivery(router, identity, messageDisplayName, stampCost)

    /**
     * DISPLAY_NAMES.md §4.1: the Message Display Name the router adds (0xD1)
     * to outbound messages under its name-ledger rule. null or "" clears it.
     * Takes effect at once, no restart. Takes the router lock: call off the
     * main thread.
     */
    fun routerSetMessageDisplayName(router: Long, name: String?): Boolean =
        nativeRouterSetMessageDisplayName(router, name) == 0

    /**
     * DISPLAY_NAMES.md §2.2: the PUBLIC name in this device's lxmf.delivery
     * announce. null or "" announces nil. The next announce carries it; set it
     * before the first announce. Takes the router lock: call off the main thread.
     */
    fun routerSetAnnounceDisplayName(router: Long, name: String?): Boolean =
        nativeRouterSetAnnounceDisplayName(router, name) == 0

    /**
     * DISPLAY_NAMES.md §3 cleaning, the one Rust implementation: the name
     * exactly as it would be sent, or null when it cleans to no name.
     * [announce] also treats "Anonymous Peer" as no name.
     */
    fun displayNameClean(raw: String, announce: Boolean): String? =
        nativeDisplayNameClean(raw.toByteArray(Charsets.UTF_8), announce)

    /**
     * Field 0xD1 from a delivery callback's `fieldsRaw`, as
     * `name_state u8 | name_len u16 BE | name` (parse with
     * [com.newendian.retichat.names.NameField.fromTrailer]).
     */
    fun displayNameDecode(fieldsRaw: ByteArray): ByteArray? = nativeDisplayNameDecode(fieldsRaw)

    /** Register the inbound-message callback. */
    fun routerSetDeliveryCallback(router: Long, callback: MessageCallback): Boolean =
        nativeRouterSetDeliveryCallback(router, callback) == 0

    /** Register the announce callback (fires when a delivery announce is received). */
    fun routerSetAnnounceCallback(router: Long, callback: AnnounceCallback): Boolean =
        nativeRouterSetAnnounceCallback(router, callback) == 0

    /** Announce a delivery destination. */
    fun routerAnnounce(router: Long, destHash: ByteArray): Boolean =
        nativeRouterAnnounce(router, destHash) == 0

    /**
     * Opt a destination into Transport's auto-announce daemon.
     *
     * Once published, Transport re-announces the destination every
     * [refreshSecs] seconds on every online interface and once when an
     * interface comes online, both held per destination and per interface
     * to that period (30 min when `0.0`), so a flapping link cannot spam
     * the public nodes' per-destination announce limiters
     * (Reticulum-rust PARITY-AUDIT-1.5.2.md B22). The app's own announces
     * are never held.
     *
     * Idempotent: a second call updates the existing entry without
     * triggering an immediate announce. Replaces an app-side announce timer.
     *
     * The router's delivery destination is published with the router's
     * app_data (`[announce_name | nil, stamp_cost]`). False (native -1) when
     * the publish failed; [lastError] says why.
     */
    fun transportPublishDestination(destHash: ByteArray, refreshSecs: Double): Boolean =
        nativeTransportPublishDestination(destHash, refreshSecs) == 0

    /** Remove a destination from the auto-announce daemon's published set. */
    fun transportUnpublishDestination(destHash: ByteArray): Boolean =
        nativeTransportUnpublishDestination(destHash) == 0

    /** Add a destination to the announce watch list (only watched destinations trigger callbacks). */
    fun routerWatchDestination(router: Long, destHash: ByteArray): Boolean =
        nativeRouterWatchDestination(router, destHash) == 0

    /**
     * The router's own stranger filter. The app keeps it off and applies the
     * privacy filter itself ([com.newendian.retichat.data.repository.DeliveryPolicy]).
     */
    fun routerSetFilterStrangers(router: Long, enabled: Boolean): Boolean =
        nativeRouterSetFilterStrangers(router, enabled) == 0

    fun routerClearDeliveryAllowlist(router: Long): Boolean =
        nativeRouterClearDeliveryAllowlist(router) == 0

    fun routerAllowDeliveryIdentity(router: Long, identityHash: ByteArray): Boolean =
        nativeRouterAllowDeliveryIdentity(router, identityHash) == 0

    fun routerDisallowDeliveryIdentity(router: Long, identityHash: ByteArray): Boolean =
        nativeRouterDisallowDeliveryIdentity(router, identityHash) == 0

    /** Kick the outbound processor. */
    fun routerProcessOutbound(router: Long): Boolean =
        nativeRouterProcessOutbound(router) == 0

    fun routerDestroy(router: Long): Boolean = nativeRouterDestroy(router) == 0

    private external fun nativeRouterCreate(identityHandle: Long, storagePath: String): Long
    private external fun nativeRouterRegisterDelivery(
        router: Long, identity: Long, name: String, stampCost: Int
    ): Long
    private external fun nativeRouterSetMessageDisplayName(router: Long, name: String?): Int
    private external fun nativeRouterSetAnnounceDisplayName(router: Long, name: String?): Int
    private external fun nativeDisplayNameClean(raw: ByteArray, announce: Boolean): String?
    private external fun nativeDisplayNameDecode(fieldsRaw: ByteArray): ByteArray?
    private external fun nativeRouterSetDeliveryCallback(router: Long, callback: MessageCallback): Int
    private external fun nativeRouterSetAnnounceCallback(router: Long, callback: AnnounceCallback): Int
    private external fun nativeRouterAnnounce(router: Long, destHash: ByteArray): Int
    private external fun nativeRouterWatchDestination(router: Long, destHash: ByteArray): Int
	private external fun nativeRouterSetFilterStrangers(router: Long, enabled: Boolean): Int
	private external fun nativeRouterClearDeliveryAllowlist(router: Long): Int
	private external fun nativeRouterAllowDeliveryIdentity(router: Long, identityHash: ByteArray): Int
	private external fun nativeRouterDisallowDeliveryIdentity(router: Long, identityHash: ByteArray): Int
    private external fun nativeRouterProcessOutbound(router: Long): Int
    private external fun nativeRouterDestroy(router: Long): Int
    private external fun nativeTransportPublishDestination(destHash: ByteArray, refreshSecs: Double): Int
    private external fun nativeTransportUnpublishDestination(destHash: ByteArray): Int

    // ---- Propagation ----

    /** Set the outbound propagation node (16-byte destination hash). */
    fun routerSetPropagationNode(router: Long, destHash: ByteArray): Boolean =
        nativeRouterSetPropagationNode(router, destHash) == 0

    /** Request messages from the configured propagation node. */
    fun routerRequestMessages(router: Long, identity: Long): Boolean =
        nativeRouterRequestMessages(router, identity) == 0

    /** Get the current propagation transfer state (PR_* constant). */
    fun routerGetPropagationState(router: Long): Int =
        nativeRouterGetPropagationState(router)

    /** Get the current propagation transfer progress (0.0 – 1.0). */
    fun routerGetPropagationProgress(router: Long): Float =
        nativeRouterGetPropagationProgress(router)

    /** Cancel any in-progress propagation node requests. */
    fun routerCancelPropagation(router: Long): Boolean =
        nativeRouterCancelPropagation(router) == 0

    private external fun nativeRouterSetPropagationNode(router: Long, destHash: ByteArray): Int
    private external fun nativeRouterRequestMessages(router: Long, identity: Long): Int
    private external fun nativeRouterGetPropagationState(router: Long): Int
    private external fun nativeRouterGetPropagationProgress(router: Long): Float
    private external fun nativeRouterCancelPropagation(router: Long): Int

    // ---- Keepalive tuning ----

    /**
     * Adjust the keepalive interval (in seconds) for all active Reticulum
     * links and TCP backbone connections.
     *
     * Pass `0.0` to restore compiled-in defaults (360 s link, 5 s TCP probe).
     */
    fun setKeepaliveInterval(secs: Double): Boolean =
        nativeSetKeepaliveInterval(secs) == 0

    private external fun nativeSetKeepaliveInterval(secs: Double): Int

    // ---- Message ----

    /** Create an outbound message. method: 0=opportunistic, 1=direct, 2=propagated. Returns handle. */
    fun messageCreate(
        destHash: ByteArray, srcHash: ByteArray,
        content: String, title: String = "", method: Int = 1,
        identityHandle: Long = 0L,
    ): Long = nativeMessageCreate(destHash, srcHash, content, title, method, identityHandle)

    fun messageAddAttachment(handle: Long, filename: String, data: ByteArray): Boolean =
        nativeMessageAddAttachment(handle, filename, data) == 0

    /**
     * Add a string-valued top-level LXMF field. 0xD1 (the Retichat field) is
     * refused: its entries go through [messageSetRetichatString]. Group
     * entries are written only by `service.GroupFields`.
     */
    fun messageAddFieldString(handle: Long, key: Int, value: String): Boolean =
        nativeMessageAddFieldString(handle, key, value) == 0

    /** Add a boolean-valued top-level LXMF field; 0xD1 is refused. */
    fun messageAddFieldBool(handle: Long, key: Int, value: Boolean): Boolean =
        nativeMessageAddFieldBool(handle, key, value) == 0

    /**
     * DISPLAY_NAMES.md §10: set str entry [key] of the Retichat field 0xD1,
     * merged into `{0xD1: {key: value}}` beside the entries already there.
     * [key] must be 1..127 (0 is the display name, which only the router
     * writes), and keys 1-9 must take their own type (all str but 8).
     * False, with [lastError], and the message unchanged, otherwise.
     */
    fun messageSetRetichatString(handle: Long, key: Int, value: String): Boolean =
        nativeMessageSetRetichatString(handle, key, value) == 0

    /** As [messageSetRetichatString], for a bool entry (of keys 1-9 only 8). */
    fun messageSetRetichatBool(handle: Long, key: Int, value: Boolean): Boolean =
        nativeMessageSetRetichatBool(handle, key, value) == 0

    /**
     * RFed-spec Group.md, "Member statuses and keys", DISPLAY_NAMES.md §10 key
     * 10: write `GROUP_STATUSES` on an outbound `status`: a native array of
     * bin, one for each of [elements] (a member's own packed accept or leave
     * exactly as the creator received it), in the Retichat field 0xD1 whatever
     * [LxmfFields.GROUP_ENTRIES_IN_RETICHAT_FIELD] says (key 10 has no old
     * top-level field). An empty list is a creator's valid answer. False, with
     * [lastError], and the message unchanged, when an element has no payload or
     * is over 2144 bytes.
     */
    fun messageSetRetichatStatuses(handle: Long, elements: List<ByteArray>): Boolean =
        nativeMessageSetRetichatStatuses(handle, elements.toTypedArray()) == 0

    /**
     * An element of a `status`, or an entry held for its sender's key (a
     * member's own packed accept or leave, 97..=2144 bytes), unpacked and
     * checked as if it had arrived directly (see [UnpackedGroupMessage]):
     * the signature under the key held for its source, else under the key it
     * carries when that binds. Nothing is remembered. Null with [lastError]
     * when it is not an LXMF message of that size.
     */
    fun groupUnpackPacked(packed: ByteArray): UnpackedGroupMessage? =
        nativeGroupUnpackPacked(packed)?.let { UnpackedGroupMessage.parse(it) }

    /** Clone a message as a fresh PROPAGATED copy, preserving fields/attachments. */
    fun messageClonePropagated(handle: Long): Long = nativeMessageClonePropagated(handle)

    /**
     * Hand the router an LXMF message RFed pushed on `rfed.propagation.stream`
     * (the bare propagation blob). True when the router took it.
     */
    fun routerIngestPropagated(router: Long, lxmfData: ByteArray): Boolean =
        nativeRouterIngestPropagated(router, lxmfData)

    fun routerSetMessageStateCallback(router: Long, callback: MessageStateCallback): Boolean =
        nativeRouterSetMessageStateCallback(router, callback) == 0

    /** Submit the message to the router for delivery. */
    @Deprecated(
        "Use messageSendViaAppLinks(msg) so AppLinks owns path race, readiness, and propagation fallback timing.",
        ReplaceWith("messageSendViaAppLinks(msg)"),
    )
    fun messageSend(router: Long, msg: Long): Boolean =
        nativeMessageSend(router, msg) == 0

    /**
     * Submit the message via Reticulum's top-level `AppLinks::send`
     * pipeline (interface race + 2 s liveness cache; no router handle
     * needed — uses the global router). Use this for new send sites
     * where you don't want to manage iface selection or path warm-up.
     */
    fun messageSendViaAppLinks(msg: Long): Boolean =
        nativeMessageSendViaAppLinks(msg) == 0

    /**
     * Forget the cached liveness winner for [destHash]. Call from your
     * `ConnectivityManager.NetworkCallback` (`onLost` / `onAvailable`)
     * so the next [messageSendViaAppLinks] re-races interfaces instead
     * of reusing a now-dead path.
     */
    fun appLinksInvalidateLiveness(destHash: ByteArray): Boolean =
        nativeAppLinksInvalidateLiveness(destHash) == 0

    /** RFed SPEC §17.10: the destination's last announce carried the distro flag. */
    fun peerIsDistro(destHash: ByteArray): Boolean = nativePeerIsDistro(destHash)

    fun messageGetState(handle: Long): Int = nativeMessageGetState(handle)
    fun messageGetProgress(handle: Long): Float = nativeMessageGetProgress(handle)
    fun messageGetHash(handle: Long): ByteArray? = nativeMessageGetHash(handle)
    fun messageDestroy(handle: Long): Boolean = nativeMessageDestroy(handle) == 0

    private external fun nativeMessageCreate(
        destHash: ByteArray, srcHash: ByteArray,
        content: String, title: String, method: Int,
        identityHandle: Long,
    ): Long
    private external fun nativeMessageAddAttachment(handle: Long, filename: String, data: ByteArray): Int
    private external fun nativeMessageAddFieldString(handle: Long, key: Int, value: String): Int
    private external fun nativeMessageAddFieldBool(handle: Long, key: Int, value: Boolean): Int
    private external fun nativeMessageSetRetichatString(handle: Long, key: Int, value: String): Int
    private external fun nativeMessageSetRetichatBool(handle: Long, key: Int, value: Boolean): Int
    private external fun nativeMessageSetRetichatStatuses(handle: Long, elements: Array<ByteArray>): Int
    private external fun nativeGroupUnpackPacked(packed: ByteArray): ByteArray?
    private external fun nativeMessageClonePropagated(handle: Long): Long
    private external fun nativeRouterIngestPropagated(router: Long, lxmfData: ByteArray): Boolean
    private external fun nativeRouterSetMessageStateCallback(router: Long, callback: MessageStateCallback): Int
    private external fun nativeMessageSend(router: Long, msg: Long): Int
    private external fun nativeMessageSendViaAppLinks(msg: Long): Int
    private external fun nativeAppLinksInvalidateLiveness(destHash: ByteArray): Int
    private external fun nativePeerIsDistro(destHash: ByteArray): Boolean
    private external fun nativeMessageGetState(handle: Long): Int
    private external fun nativeMessageGetProgress(handle: Long): Float
    private external fun nativeMessageGetHash(handle: Long): ByteArray?
    private external fun nativeMessageDestroy(handle: Long): Int

    // ---- Constants (mirror Rust LXMessage state) ----

    object MessageState {
        const val GENERATING = 0x00
        const val OUTBOUND   = 0x01
        const val SENDING    = 0x02
        const val SENT       = 0x04
        const val DELIVERED  = 0x08
        const val PROP_FALLBACK_REQUESTED = 0x10
        const val REJECTED   = 0xFD
        const val CANCELLED  = 0xFE
        const val FAILED     = 0xFF
    }

    object DeliveryMethod {
        const val OPPORTUNISTIC = 0x01
        const val DIRECT        = 0x02
        const val PROPAGATED    = 0x03
    }

    /** Propagation transfer state constants (mirrors Rust LXMRouter PR_*). */
    object PropagationState {
        const val PR_IDLE              = 0x00
        const val PR_PATH_REQUESTED    = 0x01
        const val PR_LINK_ESTABLISHING = 0x02
        const val PR_LINK_ESTABLISHED  = 0x03
        const val PR_REQUEST_SENT      = 0x04
        const val PR_RECEIVING         = 0x05
        const val PR_RESPONSE_RECEIVED = 0x06
        const val PR_COMPLETE          = 0x07
        const val PR_NO_PATH           = 0xF0
        const val PR_NO_IDENTITY_RCVD  = 0xF3
        const val PR_NO_ACCESS         = 0xF4
        const val PR_FAILED            = 0xFE
    }

    // -------------------------------------------------------------------
    // Identity sign / Announce watchlist / Path persistence
    // -------------------------------------------------------------------

    /** Ed25519-sign `data` with the identity at [handle]. Returns 64-byte sig or null. */
    fun identitySign(handle: Long, data: ByteArray): ByteArray? =
        nativeIdentitySign(handle, data)

    /** Whitelist a destination so its announces always pass the drop filter. */
    fun watchAnnounce(destHash: ByteArray) = nativeWatchAnnounce(destHash)

    /** Remove a destination from the announce whitelist. */
    fun unwatchAnnounce(destHash: ByteArray) = nativeUnwatchAnnounce(destHash)

    /** Force-flush the in-memory path table to disk. */
    fun transportSavePaths(): Boolean = nativeTransportSavePaths() == 0

    private external fun nativeIdentitySign(handle: Long, data: ByteArray): ByteArray?

    // ── Distro (RFed SPEC §17): one LXMF identity shared by all your devices ──
    // The distro identity is an ordinary identity handle (identityFromBytes on
    // the 64-byte private key). These mirror the iOS `retichat_distro_*` FFI.

    /** A fresh 64-byte distro private key (X25519 || Ed25519). */
    fun distroGenerate(): ByteArray? = nativeDistroGenerate()
    /** The 64-byte private key behind an identity handle. */
    fun distroPrivateKey(handle: Long): ByteArray? = nativeDistroPrivateKey(handle)
    /** The distro's lxmf.delivery hash — the address senders use. */
    fun distroDeliveryHash(handle: Long): ByteArray? = nativeDistroDeliveryHash(handle)
    /** msgpack [device_pubkey, distro_pubkey, sig(device_pubkey)] for /rfed/distro/register|unregister. */
    fun distroRegisterPayload(deviceHandle: Long, distroHandle: Long): ByteArray? =
        nativeDistroRegisterPayload(deviceHandle, distroHandle)
    /** msgpack [distro_hash, distro_pubkey, sig(distro_hash)] for /rfed/distro/list. */
    fun distroListPayload(distroHandle: Long): ByteArray? = nativeDistroListPayload(distroHandle)
    /**
     * The pre-signed lxmf.delivery announce RFed replays on the distro's
     * behalf. Its app_data is `[announce_name | nil, nil, [0xD0]]`
     * (DISPLAY_NAMES.md §2.2): [announceName] is the raw Announce Display
     * Name, null or empty for none; it is cleaned natively.
     */
    fun distroAnnouncePayload(distroHandle: Long, announceName: String?): ByteArray? =
        nativeDistroAnnouncePayload(distroHandle, announceName?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))
    /**
     * Decrypt a fan-out blob with the distro identity. Returns JSON
     * (source_hash, timestamp, title, content, is_delivery_notification,
     * ticket, distro_transfer_key, sent_to, sent_by, display_name_state
     * 0/1/2, display_name, signature_validated, unverified_reason 0/1/2,
     * fields: the LXMF fields map as base64 msgpack, or null), "" when the
     * blob belongs to another distro, or null on error (see lastError()).
     * Whether to accept the name depends on the signature fields
     * (DISPLAY_NAMES.md §5.2); `fields` goes through DistroCodec.fieldsBytes
     * to LxmfFields.decode, as a direct message's fields do.
     */
    fun distroUnwrap(distroHandle: Long, blob: ByteArray): String? = nativeDistroUnwrap(distroHandle, blob)

    // ── What a device owes its distro (RFed SPEC §17.12, §17.11) ──
    // The outbox itself is service/DistroOutbox.kt; these are what it cannot
    // make from Kotlin. rust/retichat-jni/src/distro_outbox.rs has the parts
    // that do not need a JVM.

    /**
     * Write the membership message C's fields on an outbound message
     * ([messageCreate]): 0x0C an empty bin, 0xFB `rfed.distro.channel`, 0xFC
     * the native array `[op, name, at_ms]`, 0xFD [deviceHex]. False with
     * [lastError] when [op] is not join or leave, [name] is empty, [atMs] is
     * not 0..2^53-1 or [deviceHex] is not 32 hex.
     */
    fun messageSetDistroChannelSync(handle: Long, op: String, name: String, atMs: Long, deviceHex: String): Boolean =
        nativeMessageSetDistroChannelSync(handle, op, name, atMs, deviceHex) == 0

    /**
     * Pack and sign an outbound message once and return its LXMF bytes
     * (`dest | src | signature | payload`); [messageGetHash] is then its hash.
     * What the outbox keeps: never packed again, so every upload is one message.
     */
    fun messagePack(handle: Long): ByteArray? = nativeMessagePack(handle)

    /**
     * The propagation upload of the packed message [packed] for the node [node]:
     * encrypted to [distroPublicKey] and to no other key, stamped for the
     * node's cost. Blocks while the stamp is mined: off the main thread. Null
     * with [lastError] when it cannot be built; nothing has left.
     */
    fun propagationBuildUpload(distroPublicKey: ByteArray, packed: ByteArray, node: ByteArray): ByteArray? =
        nativePropagationBuildUpload(distroPublicKey, packed, node)

    /** A message sealed for the distro sync proof ([distroSeal]): the bytes every upload carries, and D's signature over them. */
    class DistroSealed(val sealed: ByteArray, val syncSig: ByteArray)

    /**
     * The distro sync proof (RFed SPEC §17.13): seal the packed message
     * [packed] ([messagePack], D's own) once, when it is owed, with the distro
     * identity [distroHandle]: `sealed = D_hash | D.encrypt(packed[16..])` and
     * D's signature over it. Null with [lastError] when it cannot be sealed
     * (no private key, not addressed to D): the entry is then owed without it.
     */
    fun distroSeal(distroHandle: Long, packed: ByteArray): DistroSealed? {
        val raw = nativeDistroSeal(distroHandle, packed) ?: return null
        if (raw.size <= SYNC_SIG_LEN + 16) return null
        return DistroSealed(raw.copyOfRange(SYNC_SIG_LEN, raw.size), raw.copyOfRange(0, SYNC_SIG_LEN))
    }

    /** The length of a distro sync signature, first in what [nativeDistroSeal] returns. */
    const val SYNC_SIG_LEN = 64

    /**
     * One upload of a sealed entry for the node [node]: the stored [sealed]
     * bytes, never encrypted again, stamped anew for the node's cost, with the
     * claim `[id, distroPublicKey, syncSig]` only when [withClaim] (the node is
     * the RFed node's own lxmf.propagation: [com.newendian.retichat.service.SyncProofGate]).
     * Blocks while the stamp is mined: off the main thread. Null with
     * [lastError] when it cannot be built; nothing has left.
     */
    fun propagationBuildSealedUpload(
        distroPublicKey: ByteArray, sealed: ByteArray, syncSig: ByteArray, withClaim: Boolean, node: ByteArray,
    ): ByteArray? = nativePropagationBuildSealedUpload(distroPublicKey, sealed, syncSig, withClaim, node)

    /**
     * How many interface up-edges Transport has told: app-links' `UpEdgeMark`,
     * which the outbox takes just before each upload and asks again once a
     * never-left upload is recorded as owed ([com.newendian.retichat.service.UpEdgeMark]).
     */
    fun interfaceUpEdges(): Long = nativeInterfaceUpEdges()

    /** Link states, `LinkHandle::status()`: [PropagationLink.state]. */
    object LinkState {
        const val PENDING   = 0
        const val HANDSHAKE = 1
        const val ACTIVE    = 2
        const val STALE     = 3
        const val CLOSED    = 4
    }

    /** The link AppLinks holds for the propagation node: its id (hex) and [LinkState]. */
    data class PropagationLink(val id: String, val state: Int) {
        val active: Boolean get() = state == LinkState.ACTIVE
    }

    /** The link held for [node] as it is now, or null when none is held. A read, never a wait. */
    fun propagationLink(node: ByteArray): PropagationLink? {
        val raw = nativePropagationLink(node) ?: return null
        if (raw.size < 2) return null
        return PropagationLink(raw.copyOfRange(1, raw.size).joinToString("") { "%02x".format(it) }, raw[0].toInt() and 0xff)
    }

    /** An upload that left: how (1 a packet, 2 a Resource) and the link (hex) it went on. */
    data class UploadStart(val resource: Boolean, val linkId: String)

    /**
     * Hand [upload] ([propagationBuildUpload], [propagationBuildSealedUpload])
     * to the ACTIVE link held for [node]. Null with [lastError] when it did not
     * leave (no active link held, or it could not be queued): [callback] is then
     * never called. Once it has left, [callback] hears the node's proof or the
     * loss, and on a loss the link the upload went on has already been torn down
     * (the receipt's timeout, as LXMF does); or, for a Resource whose first
     * advertisement no interface could carry, that it never left, with the link
     * kept. Nothing here resends or times anything.
     */
    fun propagationUpload(node: ByteArray, upload: ByteArray, callback: PropagationUploadCallback): UploadStart? {
        val raw = nativePropagationUpload(node, upload, callback) ?: return null
        if (raw.size < 2) return null
        return UploadStart(raw[0].toInt() == 2, raw.copyOfRange(1, raw.size).joinToString("") { "%02x".format(it) })
    }

    /**
     * Register [callback] for every APP_LINK status change, with the link each
     * is about. The stack's shutdown drops it: register again after each start.
     */
    fun propagationLinkEventsRegister(callback: PropagationLinkCallback): Boolean =
        nativePropagationLinkEventsRegister(callback) == 0

    /**
     * Be told whenever a Reticulum interface comes online (Transport's
     * up-edge: an interface that was offline is online, whichever interface it
     * is). One callback at a time: a later register replaces it. Register again
     * after each start; [interfaceOnlineUnregister] ends it at the stop.
     */
    fun interfaceOnlineRegister(callback: InterfaceOnlineCallback): Boolean =
        nativeInterfaceOnlineRegister(callback) == 0

    /** Stop telling the callback [interfaceOnlineRegister] set. */
    fun interfaceOnlineUnregister() { nativeInterfaceOnlineUnregister() }

    private external fun nativeInterfaceOnlineRegister(callback: InterfaceOnlineCallback): Int
    private external fun nativeInterfaceOnlineUnregister(): Int

    private external fun nativeMessageSetDistroChannelSync(
        handle: Long, op: String, name: String, atMs: Long, deviceHex: String,
    ): Int
    private external fun nativeMessagePack(handle: Long): ByteArray?
    private external fun nativePropagationBuildUpload(distroPublicKey: ByteArray, packed: ByteArray, node: ByteArray): ByteArray?
    private external fun nativeDistroSeal(distroHandle: Long, packed: ByteArray): ByteArray?
    private external fun nativePropagationBuildSealedUpload(
        distroPublicKey: ByteArray, sealed: ByteArray, sig: ByteArray, withClaim: Boolean, node: ByteArray,
    ): ByteArray?
    private external fun nativeInterfaceUpEdges(): Long
    private external fun nativePropagationLink(node: ByteArray): ByteArray?
    private external fun nativePropagationUpload(node: ByteArray, upload: ByteArray, callback: PropagationUploadCallback): ByteArray?
    private external fun nativePropagationLinkEventsRegister(callback: PropagationLinkCallback): Int

    private external fun nativeDistroGenerate(): ByteArray?
    private external fun nativeDistroPrivateKey(handle: Long): ByteArray?
    private external fun nativeDistroDeliveryHash(handle: Long): ByteArray?
    private external fun nativeDistroRegisterPayload(deviceHandle: Long, distroHandle: Long): ByteArray?
    private external fun nativeDistroListPayload(distroHandle: Long): ByteArray?
    private external fun nativeDistroAnnouncePayload(distroHandle: Long, announceName: ByteArray?): ByteArray?
    private external fun nativeDistroUnwrap(distroHandle: Long, blob: ByteArray): String?
    private external fun nativeWatchAnnounce(destHash: ByteArray)
    private external fun nativeUnwatchAnnounce(destHash: ByteArray)
    private external fun nativeTransportSavePaths(): Int

    // -------------------------------------------------------------------
    // Raw packet send / synchronous link request
    // -------------------------------------------------------------------

    /**
     * Send a single encrypted DATA packet to `destHash`. The remote identity
     * must already be in the known-destinations cache (from a prior announce).
     */
    @Deprecated(
        "Use ConnectionStateManager.appLinkSendData(...) so AppLinks owns readiness and path race.",
        ReplaceWith("ConnectionStateManager.appLinkSendData(destHash, appName, aspects, payload)"),
    )
    fun packetSendToHash(
        destHash: ByteArray,
        appName: String,
        aspects: String,
        payload: ByteArray,
    ): Boolean = nativePacketSendToHash(destHash, appName, aspects, payload) == 0

    /**
     * Open a Link to a remote destination, identify, send a request along
     * `path` with `payload`, await the response, tear down. **Blocks** —
     * call from a background thread / coroutine on Dispatchers.IO.
     *
     * Returns response bytes, or null on error (call [lastError]).
     */
    @Deprecated(
        "Use ConnectionStateManager.appLinkSend (persistent APP_LINK). " +
            "One-shot link-request violates DESIGN_PRINCIPLES.md §3 (no timeout tuning) " +
            "because every call pays full path+link setup cost.",
        ReplaceWith("ConnectionStateManager.appLinkSend(destHash, path, payload)"),
    )
    fun linkRequest(
        destHash: ByteArray,
        appName: String,
        aspects: String,
        identityHandle: Long,
        path: String,
        payload: ByteArray,
        timeoutSecs: Double = 15.0,
    ): ByteArray? = nativeLinkRequest(
        destHash, appName, aspects, identityHandle, path, payload, timeoutSecs
    )

    private external fun nativePacketSendToHash(
        destHash: ByteArray, appName: String, aspects: String, payload: ByteArray
    ): Int
    private external fun nativeLinkRequest(
        destHash: ByteArray, appName: String, aspects: String,
        identityHandle: Long, path: String, payload: ByteArray, timeoutSecs: Double
    ): ByteArray?

    // -------------------------------------------------------------------
    // APP_LINK — persistent, push-driven destination links
    //
    // Direct port of the iOS APP_LINK FFI (see
    // Retichat-ios/Frameworks/RetichatFFI.xcframework/.../CRetichatFFI.h
    // `lxmf_app_link_*` and Retichat-ios/Retichat/Services/LxmfClient.swift).
    // Higher-level orchestration (open-on-demand, status fan-out,
    // request-suspends-until-ACTIVE) lives in ConnectionStateManager.
    // -------------------------------------------------------------------

    /**
     * APP_LINK statuses — mirror of app-links' `APP_LINK_*`: the lifecycle states
     * (NONE .. DISCONNECTED, what [appLinkStatus] returns) and one event,
     * [RECOVERED].
     */
    object AppLinkStatus {
        const val NONE           = 0
        const val PATH_REQUESTED = 1
        const val ESTABLISHING   = 2
        const val ACTIVE         = 3
        const val DISCONNECTED   = 4

        /**
         * An event, never a state (app-links `APP_LINK_RECOVERED`, 0x05): the
         * persistent link held for the destination had gone STALE and heard
         * from its peer again, so it is ACTIVE once more. It is told once per
         * recovery, with the link's id, and only for the link AppLinks still
         * holds. [appLinkStatus] never returns it (it reads ACTIVE), and a
         * handler that looks only for ACTIVE ignores it.
         */
        const val RECOVERED      = 5
    }

    /** Result codes for [appLinkRequestAsync] callbacks. */
    object AppLinkRequestStatus {
        const val RESPONSE = 0
        const val TIMEOUT  = 1
        const val FAILED   = 2
    }

    /** Result codes for [appLinkSendAsync] callbacks. */
    object AppLinkSendStatus {
        const val DELIVERED = 0
        const val FAILED    = 1
    }

    /**
     * Open (or reuse) an APP_LINK to `destHash` under the given `appName`
     * and dotted `aspects` (e.g. `"channel"`, `"notify"`, `"delivery"`).
     * Idempotent — calling for an already-open link is a no-op.
     *
     * Status transitions are reported via the registered
     * [AppLinkStatusCallback]. Returns `true` on success.
     */
    fun appLinkOpen(
        routerHandle: Long,
        destHash: ByteArray,
        appName: String,
        aspectsCsv: String,
    ): Boolean = nativeAppLinkOpen(routerHandle, destHash, appName, aspectsCsv) == 0

    /**
     * Open a persistent APP_LINK to `destHash`.
     *
     * Same destination registration as [appLinkOpen], but once the path-race
     * succeeds AppLinks holds the outbound link open so request-style traffic
     * can reuse it directly.
     */
    fun appLinkOpenPersistent(
        routerHandle: Long,
        destHash: ByteArray,
        appName: String,
        aspectsCsv: String,
    ): Boolean = nativeAppLinkOpenPersistent(routerHandle, destHash, appName, aspectsCsv) == 0

    /** Tear down the APP_LINK to `destHash`. Idempotent. */
    fun appLinkClose(routerHandle: Long, destHash: ByteArray): Boolean =
        nativeAppLinkClose(routerHandle, destHash) == 0

    /** Current [AppLinkStatus] for `destHash`, or -1 on parameter error. */
    fun appLinkStatus(routerHandle: Long, destHash: ByteArray): Int =
        nativeAppLinkStatus(routerHandle, destHash)

    /** Explicit deterministic re-open trigger for an existing app link. */
    fun appLinkReopen(routerHandle: Long, destHash: ByteArray): Boolean =
        nativeAppLinkReopen(routerHandle, destHash) == 0

    /**
     * Register a non-LXMF aspect (e.g. `"rfed.channel"`) for auto-reconnect
     * on incoming announces. Call once per aspect at startup.
     */
    fun appLinkRegisterReconnect(routerHandle: Long, aspect: String): Boolean =
        nativeAppLinkRegisterReconnect(routerHandle, aspect) == 0

    /**
     * Notify the LXMF router that the local network came back online —
     * triggers exactly ONE fresh attempt for every registered app-link
     * not currently ACTIVE/ESTABLISHING.  Wire to NetworkMonitor.
     * (DESIGN_PRINCIPLES.md §3 — no timer-driven retries.)
     */
    fun appLinkNetworkChanged(routerHandle: Long): Boolean =
        nativeAppLinkNetworkChanged(routerHandle) == 0

    /**
     * Wake the TCP interfaces' reconnect loops now instead of after their
     * backoff (up to 5 min). Call when the network is usable again. iOS:
     * `RetichatBridge.nudgeReconnect` (`rns_nudge_reconnect`).
     */
    fun nudgeReconnect() = nativeNudgeReconnect()

    /**
     * Register a process-wide [AppLinkStatusCallback]. Last register wins.
     * Should be called once during stack initialization (StackRuntime
     * → ConnectionStateManager.register).
     */
    fun appLinkRegisterStatusCallback(routerHandle: Long, cb: AppLinkStatusCallback): Boolean =
        nativeAppLinkRegisterStatusCallback(routerHandle, cb) == 0

    /** Register a packet callback for a persistent APP_LINK destination. */
    fun appLinkRegisterPacketCallback(
        routerHandle: Long,
        destHash: ByteArray,
        cb: AppLinkPacketCallback,
    ): Boolean = nativeAppLinkRegisterPacketCallback(routerHandle, destHash, cb) == 0

    /**
    * Send a request on the existing APP_LINK to `destHash` (which should
    * normally be established with [appLinkOpenPersistent] and already be
    * ACTIVE) and fire `cb.onResult` exactly once when the response arrives,
    * the request fails, or `timeoutSecs` elapses.
     *
     * Returns `true` if the request was queued (callback will fire);
     * `false` on immediate error (callback will NOT fire — check
     * [lastError]). The caller must NOT retry on failure
     * (DESIGN_PRINCIPLES.md §2). Use the status callback instead.
     */
    fun appLinkRequestAsync(
        routerHandle: Long,
        destHash: ByteArray,
        path: String,
        payload: ByteArray,
        timeoutSecs: Double,
        cb: AppLinkRequestCallback,
    ): Boolean = nativeAppLinkRequestAsync(
        routerHandle, destHash, path, payload, timeoutSecs, cb
    ) == 0

    /**
     * Send a plain DATA packet via an ephemeral APP_LINK and fire
     * `cb.onResult` exactly once on delivery proof or terminal failure.
     */
    fun appLinkSendAsync(
        routerHandle: Long,
        destHash: ByteArray,
        appName: String,
        aspectsCsv: String,
        payload: ByteArray,
        cb: AppLinkSendCallback,
    ): Boolean = nativeAppLinkSendAsync(
        routerHandle, destHash, appName, aspectsCsv, payload, cb
    ) == 0

    private external fun nativeAppLinkOpen(
        router: Long, destHash: ByteArray, appName: String, aspectsCsv: String
    ): Int
    private external fun nativeAppLinkOpenPersistent(
        router: Long, destHash: ByteArray, appName: String, aspectsCsv: String
    ): Int
    private external fun nativeAppLinkClose(router: Long, destHash: ByteArray): Int
    private external fun nativeAppLinkStatus(router: Long, destHash: ByteArray): Int
    private external fun nativeAppLinkReopen(router: Long, destHash: ByteArray): Int
    private external fun nativeAppLinkRegisterReconnect(router: Long, aspect: String): Int
    private external fun nativeAppLinkNetworkChanged(router: Long): Int
    private external fun nativeNudgeReconnect()
    private external fun nativeAppLinkRegisterStatusCallback(
        router: Long, callback: AppLinkStatusCallback
    ): Int
    private external fun nativeAppLinkRegisterPacketCallback(
        router: Long, destHash: ByteArray, callback: AppLinkPacketCallback
    ): Int
    private external fun nativeAppLinkRequestAsync(
        router: Long, destHash: ByteArray, path: String, payload: ByteArray,
        timeoutSecs: Double, callback: AppLinkRequestCallback
    ): Int
    private external fun nativeAppLinkSendAsync(
        router: Long, destHash: ByteArray, appName: String, aspectsCsv: String,
        payload: ByteArray, callback: AppLinkSendCallback
    ): Int

    // -------------------------------------------------------------------
    // RFed Delivery — inbound channel blob endpoint
    // -------------------------------------------------------------------

    /**
     * Register an inbound rfed.delivery destination so the rfed server can
     * push channel blobs to this device. The callback fires on a Rust
     * background thread (already attached to the JVM) — bridge to Kotlin's
     * Main/IO dispatcher inside `onBlob` if needed.
     */
    fun rfedDeliveryStart(identityHandle: Long, callback: RfedBlobCallback): Boolean =
        nativeRfedDeliveryStart(identityHandle, callback) == 0

    /** Announce the local rfed.delivery so the server flushes deferred blobs. */
    fun rfedDeliveryAnnounce(): Boolean = nativeRfedDeliveryAnnounce() == 0

    /** Tear down the rfed.delivery endpoint. */
    fun rfedDeliveryStop(): Boolean = nativeRfedDeliveryStop() == 0

    private external fun nativeRfedDeliveryStart(identityHandle: Long, callback: RfedBlobCallback): Int
    private external fun nativeRfedDeliveryAnnounce(): Int
    private external fun nativeRfedDeliveryStop(): Int

    // -------------------------------------------------------------------
    // Channel crypto / stamp / LXM pack-unpack
    //
    // CHANNEL MESSAGES ARE LXMF PACKAGES.  See repo memory
    // /memories/repo/retichat-rfed-channel-integration.md for the wire
    // format and stamp contract.
    // -------------------------------------------------------------------

    /** EC-encrypt `plaintext` with the deterministic channel key derived from `name`. */
    fun channelEncrypt(name: String, plaintext: ByteArray): ByteArray? =
        nativeChannelEncrypt(name, plaintext)

    /** EC-decrypt `ciphertext` with the channel key. */
    fun channelDecrypt(name: String, ciphertext: ByteArray): ByteArray? =
        nativeChannelDecrypt(name, ciphertext)

    /**
     * Compute a 32-byte PoW stamp for a channel SEND payload at the given
     * `cost`. Returns null when cost <= 0 (no stamp) or when the PoW
     * search fails to meet `cost` (in which case [lastError] is set —
     * abort the send rather than ship a sub-cost stamp).
     */
    fun channelComputeStamp(payload: ByteArray, cost: Int): ByteArray? =
        nativeComputeChannelStamp(payload, cost)

    /**
     * Pack an LXMF channel message. Returns the wire buffer prefixed with an
     * 8-byte timestamp the caller strips:
     *   `[ ts_ms_be(8) | channel_id_hash(16) | EC_encrypted_tail ]`
     * The portion sent to rfed.channel is `output.copyOfRange(8, end)`.
     */
    fun channelLxmPack(
        name: String,
        senderIdentityHandle: Long,
        content: ByteArray,
        title: ByteArray = ByteArray(0),
        displayName: com.newendian.retichat.names.NameField = com.newendian.retichat.names.NameField.Absent,
    ): ByteArray? = nativeChannelLxmPack(
        name, senderIdentityHandle, content, title,
        // DISPLAY_NAMES.md §2.3: 0 no 0xD1, 1 an empty 0xD1 (clear), 2 the
        // Channel Display Name (cleaned natively).
        displayName.state,
        (displayName as? com.newendian.retichat.names.NameField.Name)?.name?.toByteArray(Charsets.UTF_8),
    )

    /**
     * Unpack a received channel message. Input is the full lxmf_data:
     *   `[ channel_id_hash(16) | EC_encrypted_tail ]`
     *
     * Returns flat parsed-message bytes (see [ChannelLxmUnpackResult.parse]):
     *   `[source_hash(16) | ts_ms_be(8) | sig_ok(1) | reason(1) | title_len_be(2) | content_len_be(4) | title | content |
     *     name_state(1) | name_len_be(2) | name]`
     * The name trailer is the post's Channel Display Name, reported only when
     * the signature validated. A post whose public key does not produce its
     * claimed source hash is rejected (null, "key binding" in [lastError]).
     */
    fun channelLxmUnpack(name: String, lxmfData: ByteArray): ByteArray? =
        nativeChannelLxmUnpack(name, lxmfData)

    private external fun nativeChannelEncrypt(name: String, plaintext: ByteArray): ByteArray?
    private external fun nativeChannelDecrypt(name: String, ciphertext: ByteArray): ByteArray?
    private external fun nativeComputeChannelStamp(payload: ByteArray, cost: Int): ByteArray?
    private external fun nativeChannelLxmPack(
        name: String, senderHandle: Long, content: ByteArray, title: ByteArray,
        displayNameState: Int, displayName: ByteArray?,
    ): ByteArray?
    private external fun nativeChannelLxmUnpack(name: String, lxmfData: ByteArray): ByteArray?

    /**
     * Derive the 16-byte channel-identity hash from the channel name.
     *
     * Computed in pure Kotlin (see [com.newendian.retichat.crypto.ChannelHash]) so
     * the routing label / DB primary key is independent of the JNI Rust
     * derivation path. Mirrors iOS Swift `RfedChannelClient.channelHash`.
     */
    fun channelHash16(name: String): ByteArray =
        com.newendian.retichat.crypto.ChannelHash.compute(name)

    // ---- RTNode over Bluetooth (Reticulum-rust interfaces::prns_ble) ----

    /**
     * Start the Bluetooth engine once the stack runs and the delivery
     * destination is published. [storageDir] keeps the phone's Bluetooth
     * identity; returns it, or null (see [lastError]).
     */
    fun prnsBleStart(storageDir: String, callback: PrnsBleCallback): ByteArray? =
        nativePrnsBleStart(storageDir, callback)

    /** Close every link and remove the RTNodes' interfaces. Blocks briefly. */
    fun prnsBleStop() {
        nativePrnsBleStop()
    }

    /** An advertisement of the Prns service; the link to dial it on, or 0. */
    fun prnsBleSighted(address: String, companyId: Int, data: ByteArray): Long =
        nativePrnsBleSighted(address, companyId, data)

    fun prnsBleLinkReady(link: Long, maxWriteLen: Int): Boolean =
        nativePrnsBleLinkReady(link, maxWriteLen) == 0

    fun prnsBleLinkReceived(link: Long, characteristic: Int, data: ByteArray) {
        nativePrnsBleLinkReceived(link, characteristic, data)
    }

    fun prnsBleLinkWriteDone(link: Long, ok: Boolean) {
        nativePrnsBleLinkWriteDone(link, ok)
    }

    fun prnsBleLinkClosed(link: Long) {
        nativePrnsBleLinkClosed(link)
    }

    private external fun nativePrnsBleStart(storageDir: String, callback: PrnsBleCallback): ByteArray?
    private external fun nativePrnsBleStop(): Int
    private external fun nativePrnsBleSighted(address: String, companyId: Int, data: ByteArray): Long
    private external fun nativePrnsBleLinkReady(link: Long, maxWriteLen: Int): Int
    private external fun nativePrnsBleLinkReceived(link: Long, characteristic: Int, data: ByteArray): Int
    private external fun nativePrnsBleLinkWriteDone(link: Long, ok: Boolean): Int
    private external fun nativePrnsBleLinkClosed(link: Long): Int
}

/**
 * Decoded form of the byte buffer returned by [RetichatBridge.channelLxmUnpack].
 */
data class ChannelLxmUnpackResult(
    val sourceHash: ByteArray,
    val timestampMs: Long,
    val signatureValidated: Boolean,
    /** 0 = ok, 1 = SOURCE_UNKNOWN, 2 = SIGNATURE_INVALID. */
    val unverifiedReason: Int,
    val title: ByteArray,
    val content: ByteArray,
    /** The post's field 0xD1 (the poster's Channel Display Name in this channel). */
    val displayName: com.newendian.retichat.names.NameField = com.newendian.retichat.names.NameField.Absent,
) {
    companion object {
        fun parse(raw: ByteArray): ChannelLxmUnpackResult? {
            if (raw.size < 32) return null
            val source = raw.copyOfRange(0, 16)
            val ts = ((raw[16].toLong() and 0xff) shl 56) or
                     ((raw[17].toLong() and 0xff) shl 48) or
                     ((raw[18].toLong() and 0xff) shl 40) or
                     ((raw[19].toLong() and 0xff) shl 32) or
                     ((raw[20].toLong() and 0xff) shl 24) or
                     ((raw[21].toLong() and 0xff) shl 16) or
                     ((raw[22].toLong() and 0xff) shl 8) or
                     (raw[23].toLong() and 0xff)
            val sigOk = raw[24].toInt() and 0xff
            val reason = raw[25].toInt() and 0xff
            val titleLen = ((raw[26].toInt() and 0xff) shl 8) or (raw[27].toInt() and 0xff)
            val contentLen = ((raw[28].toInt() and 0xff) shl 24) or
                             ((raw[29].toInt() and 0xff) shl 16) or
                             ((raw[30].toInt() and 0xff) shl 8) or
                             (raw[31].toInt() and 0xff)
            if (raw.size < 32 + titleLen + contentLen) return null
            val title = raw.copyOfRange(32, 32 + titleLen)
            val content = raw.copyOfRange(32 + titleLen, 32 + titleLen + contentLen)
            val validated = sigOk == 1
            return ChannelLxmUnpackResult(
                sourceHash = source,
                timestampMs = ts,
                signatureValidated = validated,
                unverifiedReason = reason,
                title = title,
                content = content,
                // The trailer follows the content; the native side reports a
                // name only for a validated post, and so does this.
                displayName = if (validated) {
                    com.newendian.retichat.names.NameField.fromTrailer(raw, 32 + titleLen + contentLen)
                } else {
                    com.newendian.retichat.names.NameField.Absent
                },
            )
        }
    }
}
