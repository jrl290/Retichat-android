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
    )
}

/**
 * JNI bridge to the Rust Reticulum + LXMF libraries.
 *
 * All `native*` methods map 1:1 to C-exported JNI functions in
 * `libretichat_jni.so`.  Handle values (`Long`) are opaque references
 * to Rust objects; 0 means error — call [lastError] for details.
 */
object RetichatBridge {

    init {
        System.loadLibrary("retichat_jni")
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

    /** Announce a delivery destination. */
    fun routerAnnounce(router: Long, destHash: ByteArray): Boolean =
        nativeRouterAnnounce(router, destHash) == 0

    /** Kick the outbound processor. */
    fun routerProcessOutbound(router: Long): Boolean =
        nativeRouterProcessOutbound(router) == 0

    fun routerDestroy(router: Long): Boolean = nativeRouterDestroy(router) == 0

    private external fun nativeRouterCreate(identityHandle: Long, storagePath: String): Long
    private external fun nativeRouterRegisterDelivery(
        router: Long, identity: Long, name: String, stampCost: Int
    ): Long
    private external fun nativeRouterSetDeliveryCallback(router: Long, callback: MessageCallback): Int
    private external fun nativeRouterAnnounce(router: Long, destHash: ByteArray): Int
    private external fun nativeRouterProcessOutbound(router: Long): Int
    private external fun nativeRouterDestroy(router: Long): Int

    // ---- Message ----

    /** Create an outbound message. method: 0=opportunistic, 1=direct, 2=propagated. Returns handle. */
    fun messageCreate(
        destHash: ByteArray, srcHash: ByteArray,
        content: String, title: String = "", method: Int = 1
    ): Long = nativeMessageCreate(destHash, srcHash, content, title, method)

    fun messageAddAttachment(handle: Long, filename: String, data: ByteArray): Boolean =
        nativeMessageAddAttachment(handle, filename, data) == 0

    /** Submit the message to the router for delivery. */
    fun messageSend(router: Long, msg: Long): Boolean =
        nativeMessageSend(router, msg) == 0

    fun messageGetState(handle: Long): Int = nativeMessageGetState(handle)
    fun messageGetProgress(handle: Long): Float = nativeMessageGetProgress(handle)
    fun messageGetHash(handle: Long): ByteArray? = nativeMessageGetHash(handle)
    fun messageDestroy(handle: Long): Boolean = nativeMessageDestroy(handle) == 0

    private external fun nativeMessageCreate(
        destHash: ByteArray, srcHash: ByteArray,
        content: String, title: String, method: Int
    ): Long
    private external fun nativeMessageAddAttachment(handle: Long, filename: String, data: ByteArray): Int
    private external fun nativeMessageSend(router: Long, msg: Long): Int
    private external fun nativeMessageGetState(handle: Long): Int
    private external fun nativeMessageGetProgress(handle: Long): Float
    private external fun nativeMessageGetHash(handle: Long): ByteArray?
    private external fun nativeMessageDestroy(handle: Long): Int

    // ---- Constants (mirror Rust LXMessage state) ----

    object MessageState {
        const val GENERATING = 0
        const val OUTBOUND   = 1
        const val SENDING    = 2
        const val SENT       = 3
        const val DELIVERED  = 4
        const val REJECTED   = 5
        const val CANCELLED  = 6
        const val FAILED     = 255
    }

    object DeliveryMethod {
        const val OPPORTUNISTIC = 0
        const val DIRECT        = 1
        const val PROPAGATED    = 2
    }
}
