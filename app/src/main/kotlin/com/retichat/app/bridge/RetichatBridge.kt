package com.retichat.app.bridge

/**
 * Callback interface for receiving inbound LXMF messages from the Rust layer.
 */
interface MessageCallback {
    fun onMessage(
        hash: ByteArray,
        srcHash: ByteArray,
        destHash: ByteArray,
        title: String,
        content: String,
        timestamp: Double,
        signatureValid: Boolean,
        fieldsRaw: ByteArray,
    )
}

/**
 * Callback interface for receiving LXMF delivery announces from the Rust layer.
 */
interface AnnounceCallback {
    fun onAnnounce(destHash: ByteArray, displayName: String?)
}

/**
 * JNI bridge to the Rust Reticulum + LXMF libraries.
 *
 * All `native*` methods map 1:1 to C-exported JNI functions in
 * `libretichat_jni.so`.  Handle values (`Long`) are opaque references
 * to Rust objects; 0 means error — call [lastError] for details.
 */
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

    fun transportRequestPath(destHash: ByteArray): Boolean =
        nativeTransportRequestPath(destHash) == 0

    fun transportHopsTo(destHash: ByteArray): Int =
        nativeTransportHopsTo(destHash)

    /** Enable/disable early-dropping of inbound announce packets (opt-in, default off). */
    fun setDropAnnounces(enabled: Boolean) = nativeSetDropAnnounces(enabled)

    private external fun nativeSetDropAnnounces(enabled: Boolean)
    private external fun nativeTransportHasPath(destHash: ByteArray): Int
    private external fun nativeTransportRequestPath(destHash: ByteArray): Int
    private external fun nativeTransportHopsTo(destHash: ByteArray): Int

    // ---- Router ----

    /** Create an LXMRouter.  Returns handle (0 = error). */
    fun routerCreate(identityHandle: Long, storagePath: String): Long =
        nativeRouterCreate(identityHandle, storagePath)

    /** Register delivery identity.  Returns destination handle (0 = error). */
    fun routerRegisterDelivery(
        router: Long, identity: Long, name: String, stampCost: Int = -1
    ): Long = nativeRouterRegisterDelivery(router, identity, name, stampCost)

    /** Register the inbound-message callback. */
    fun routerSetDeliveryCallback(router: Long, callback: MessageCallback): Boolean =
        nativeRouterSetDeliveryCallback(router, callback) == 0

    /** Register the announce callback (fires when a delivery announce is received). */
    fun routerSetAnnounceCallback(router: Long, callback: AnnounceCallback): Boolean =
        nativeRouterSetAnnounceCallback(router, callback) == 0

    /** Announce a delivery destination. */
    fun routerAnnounce(router: Long, destHash: ByteArray): Boolean =
        nativeRouterAnnounce(router, destHash) == 0

    /** Add a destination to the announce watch list (only watched destinations trigger callbacks). */
    fun routerWatchDestination(router: Long, destHash: ByteArray): Boolean =
        nativeRouterWatchDestination(router, destHash) == 0

    /** Kick the outbound processor. */
    fun routerProcessOutbound(router: Long): Boolean =
        nativeRouterProcessOutbound(router) == 0

    fun routerDestroy(router: Long): Boolean = nativeRouterDestroy(router) == 0

    private external fun nativeRouterCreate(identityHandle: Long, storagePath: String): Long
    private external fun nativeRouterRegisterDelivery(
        router: Long, identity: Long, name: String, stampCost: Int
    ): Long
    private external fun nativeRouterSetDeliveryCallback(router: Long, callback: MessageCallback): Int
    private external fun nativeRouterSetAnnounceCallback(router: Long, callback: AnnounceCallback): Int
    private external fun nativeRouterAnnounce(router: Long, destHash: ByteArray): Int
    private external fun nativeRouterWatchDestination(router: Long, destHash: ByteArray): Int
    private external fun nativeRouterProcessOutbound(router: Long): Int
    private external fun nativeRouterDestroy(router: Long): Int

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

    /** Add a string-valued LXMF field (e.g. group metadata). */
    fun messageAddFieldString(handle: Long, key: Int, value: String): Boolean =
        nativeMessageAddFieldString(handle, key, value) == 0

    /** Add a boolean-valued LXMF field. */
    fun messageAddFieldBool(handle: Long, key: Int, value: Boolean): Boolean =
        nativeMessageAddFieldBool(handle, key, value) == 0

    /** Submit the message to the router for delivery. */
    fun messageSend(router: Long, msg: Long): Boolean =
        nativeMessageSend(router, msg) == 0

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
    private external fun nativeMessageSend(router: Long, msg: Long): Int
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
}
