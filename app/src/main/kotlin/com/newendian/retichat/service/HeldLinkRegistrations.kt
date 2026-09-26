package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What [HeldLinkRegistrations] needs from AppLinks. */
internal interface HeldLinkOps {
    fun status(destHash: ByteArray): Int
    fun openHeld(destHash: ByteArray, app: String, aspectsCsv: String)
    fun close(destHash: ByteArray)
    suspend fun setStatusHandler(destHash: ByteArray, handler: (Int) -> Unit)
    suspend fun sendData(destHash: ByteArray, app: String, aspectsCsv: String, payload: ByteArray): Boolean
}

/** [HeldLinkOps] on the live stack, through [ConnectionStateManager]. */
internal object LiveHeldLinkOps : HeldLinkOps {
    override fun status(destHash: ByteArray): Int = ConnectionStateManager.appLinkStatus(destHash)

    override fun openHeld(destHash: ByteArray, app: String, aspectsCsv: String) =
        ConnectionStateManager.openHeldAppLink(destHash, app, aspectsCsv)

    override fun close(destHash: ByteArray) = ConnectionStateManager.closeAppLink(destHash)

    override suspend fun setStatusHandler(destHash: ByteArray, handler: (Int) -> Unit) =
        ConnectionStateManager.setAppLinkStatusHandler(destHash, handler)

    override suspend fun sendData(destHash: ByteArray, app: String, aspectsCsv: String, payload: ByteArray): Boolean =
        ConnectionStateManager.sendDataOnActiveLink(destHash, app, aspectsCsv, payload)
}

/**
 * Registrations owed to one destination (`fcm.register`,
 * `rfed.notify.register`), each sent once, on an established link, with its
 * delivery proof.
 *
 * Until 2026-09-26 each registrar sent on a one-shot APP_LINK, whose ACTIVE
 * means only that a path is known: the send then built its link inside the
 * 5 s budget. On the phone on 2026-09-26 03:45 UTC the path came up (ACTIVE),
 * the link took 5.24 s, the send gave up at 5 s, and the registrar's ACTIVE
 * handler had skipped that ACTIVE edge because the attempt was still pending.
 * Nothing sent the registration again: the bridge got a link and no token,
 * after every app start but the first.
 *
 * Now a held link is opened, whose ACTIVE edge means the link is established
 * (AppLinks waits for that on the protocol's own timeout, not the send
 * budget), and each ACTIVE edge sends whatever is owed (DESIGN_PRINCIPLES §5:
 * the link before the send). An edge that arrives while a send is running
 * waits for it and then finds only what is still owed, so no edge is lost. A
 * registration without a proof stays owed until the next edge; nothing is
 * resent on a timer (§3). Once nothing is owed the link is closed.
 */
internal class HeldLinkRegistrations(
    private val destHash: ByteArray,
    private val app: String,
    private val aspectsCsv: String,
    private val scope: CoroutineScope,
    private val ops: HeldLinkOps = LiveHeldLinkOps,
    private val log: (priority: Int, message: String) -> Unit = { priority, message ->
        Log.println(priority, "HeldLinkReg", message)
    },
) {
    private val mutex = Mutex()
    private val owed = LinkedHashMap<String, ByteArray>()
    private val delivered = HashSet<String>()

    /**
     * Owe [payload] under [key], sent on the next ACTIVE edge of the held
     * link (at once if it is already up). A key already delivered in this
     * process is not sent again.
     */
    suspend fun owe(key: String, payload: ByteArray) {
        val sendNow = mutex.withLock {
            if (key in delivered) return
            owed[key] = payload
            // Installed on every call: ConnectionStateManager.unregister (a
            // stack stop) clears the status handlers.
            ops.setStatusHandler(destHash) { status ->
                if (status == RetichatBridge.AppLinkStatus.ACTIVE) scope.launch { onActive() }
            }
            if (ops.status(destHash) == RetichatBridge.AppLinkStatus.ACTIVE) {
                true
            } else {
                ops.openHeld(destHash, app, aspectsCsv)
                false
            }
        }
        if (sendNow) onActive()
    }

    /** An ACTIVE edge: the held link is established. Send what is owed. */
    suspend fun onActive() = mutex.withLock {
        if (owed.isEmpty()) return@withLock
        for ((key, payload) in owed.entries.toList()) {
            if (ops.sendData(destHash, app, aspectsCsv, payload)) {
                owed.remove(key)
                delivered.add(key)
                log(Log.INFO, "$app.$aspectsCsv: $key delivered")
            } else {
                log(Log.WARN, "$app.$aspectsCsv: $key not proved; owed until the link is next established")
            }
        }
        if (owed.isEmpty()) ops.close(destHash)
    }

    /**
     * Withdraw [key]: an owed registration is no longer sent (the channel
     * was left before it went out), and a delivered one may be owed again.
     */
    suspend fun forget(key: String) = mutex.withLock {
        owed.remove(key)
        delivered.remove(key)
        Unit
    }

    internal suspend fun owedKeys(): Set<String> = mutex.withLock { owed.keys.toSet() }
}
