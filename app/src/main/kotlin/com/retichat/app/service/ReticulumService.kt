package com.retichat.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.retichat.app.RetichatApp
import com.retichat.app.bridge.AnnounceCallback
import com.retichat.app.bridge.MessageCallback
import com.retichat.app.bridge.RetichatBridge
import com.retichat.app.data.db.entity.InterfaceConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Background service that keeps the Reticulum stack running while
 * the app is visible.
 *
 * Uses a regular (non-foreground) service so no persistent notification
 * is shown.  Lifecycle:
 *   - Started by [MainActivity.onCreate] / [MainActivity.onStart]
 *   - The system may kill it at any time after the Activity stops
 *   - [PropagationPollWorker] handles background 15-min polling
 *
 * On startup it reads the enabled interface configurations from Room,
 * generates a Reticulum config file, and bootstraps the full
 * Reticulum → LXMF Router → delivery callback chain.
 *
 * After bootstrap it configures the propagation node and performs a
 * one-shot poll for waiting messages.
 */
class ReticulumService : Service() {

    companion object {
        private const val TAG = "ReticulumService"
        private const val ACTION_POLL_PROPAGATION = "com.retichat.app.POLL_PROPAGATION"
        private const val MAX_AUTO_RETRIES = 3
        private const val MONITOR_POLL_MS = 500L
        private const val MONITOR_TIMEOUT_MS = 60_000L

        /** True when the service has valid identity + router handles. */
        @Volatile
        @JvmStatic
        var isInitialised: Boolean = false
            private set

        fun start(context: Context) {
            try {
                context.startService(Intent(context, ReticulumService::class.java))
            } catch (e: Exception) {
                // BackgroundServiceStartNotAllowedException on API 31+
                Log.w(TAG, "Cannot start service from background: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReticulumService::class.java))
        }

        /** Ask the running service to tear down and re-init with fresh config. */
        fun restart(context: Context) {
            try {
                val intent = Intent(context, ReticulumService::class.java)
                intent.putExtra("restart", true)
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot restart service from background: ${e.message}")
            }
        }

        /** Trigger a propagation poll without a full restart. */
        fun pollPropagation(context: Context) {
            try {
                val intent = Intent(context, ReticulumService::class.java)
                intent.action = ACTION_POLL_PROPAGATION
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot poll propagation from background: ${e.message}")
            }
        }
    }

    private var identityHandle: Long = 0L
    private var routerHandle: Long = 0L
    private var destHandle: Long = 0L

    /** Manages the shuffled list of propagation nodes. */
    private val propagationNodeManager = PropagationNodeManager()

    /** Currently running state-monitor job (cancelled on teardown or new poll). */
    private var propagationMonitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as RetichatApp
        app.applicationScope.launch(Dispatchers.IO) {
            initReticulum(app)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as RetichatApp

        when {
            // Explicit restart (e.g. after settings change)
            intent?.getBooleanExtra("restart", false) == true -> {
                app.applicationScope.launch(Dispatchers.IO) {
                    tearDown(app)
                    initReticulum(app)
                }
            }
            // Propagation poll request (from WorkManager or DeviceStateReceiver)
            intent?.action == ACTION_POLL_PROPAGATION -> {
                app.applicationScope.launch(Dispatchers.IO) {
                    pollPropagationNode()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tearDown(application as RetichatApp)
    }

    // ---- Propagation polling ----

    /**
     * Configure the propagation node on the router, request messages,
     * and launch an async monitor that watches the transfer state machine.
     *
     * On async failure (PR_FAILED, PR_NO_PATH, PR_NO_IDENTITY_RCVD, PR_NO_ACCESS)
     * rotates to the next node and retries up to [MAX_AUTO_RETRIES] times.
     */
    private fun pollPropagationNode(retriesRemaining: Int = MAX_AUTO_RETRIES) {
        if (routerHandle == 0L || identityHandle == 0L) {
            Log.w(TAG, "pollPropagationNode: not initialised yet")
            return
        }

        val nodeHash = propagationNodeManager.primaryNode
        val nodeHex = nodeHash.joinToString("") { "%02x".format(it) }

        if (!RetichatBridge.routerSetPropagationNode(routerHandle, nodeHash)) {
            val err = RetichatBridge.lastError() ?: "unknown"
            Log.e(TAG, "Failed to set propagation node $nodeHex: $err")
            if (retriesRemaining > 0) {
                propagationNodeManager.reportFailure()
                pollPropagationNode(retriesRemaining - 1)
            }
            return
        }

        Log.i(TAG, "Requesting messages from propagation node $nodeHex")
        if (!RetichatBridge.routerRequestMessages(routerHandle, identityHandle)) {
            val err = RetichatBridge.lastError() ?: "unknown"
            Log.e(TAG, "Failed to request messages: $err")
            if (retriesRemaining > 0) {
                propagationNodeManager.reportFailure()
                pollPropagationNode(retriesRemaining - 1)
            }
            return
        }

        // Launch the async state monitor
        startPropagationMonitor(nodeHex, retriesRemaining)
    }

    /**
     * Polls [RetichatBridge.routerGetPropagationState] every 500 ms until
     * the transfer completes, fails, or times out (60 s).
     *
     * Terminal states:
     *   PR_COMPLETE (0x07)          → success, report to node manager
     *   PR_FAILED / PR_NO_PATH /
     *   PR_NO_IDENTITY_RCVD /
     *   PR_NO_ACCESS (>= 0xF0)     → failure, rotate node, retry if budget remains
     *   timeout                     → cancel, rotate, retry
     */
    private fun startPropagationMonitor(nodeHex: String, retriesRemaining: Int) {
        // Cancel any existing monitor
        propagationMonitorJob?.cancel()

        val app = application as RetichatApp
        propagationMonitorJob = app.applicationScope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + MONITOR_TIMEOUT_MS
            var lastState = -1

            while (isActive && System.currentTimeMillis() < deadline) {
                if (routerHandle == 0L) {
                    Log.d(TAG, "Monitor: router torn down, stopping")
                    return@launch
                }

                val state = RetichatBridge.routerGetPropagationState(routerHandle)
                val progress = RetichatBridge.routerGetPropagationProgress(routerHandle)

                if (state != lastState) {
                    Log.i(TAG, "Propagation state: ${stateName(state)} (0x${"%02X".format(state)}) progress=${"%1.0f%%".format(progress * 100)}")
                    lastState = state
                }

                when {
                    // Success
                    state == RetichatBridge.PropagationState.PR_COMPLETE -> {
                        Log.i(TAG, "Propagation sync complete from $nodeHex")
                        propagationNodeManager.reportSuccess()
                        return@launch
                    }
                    // Back to idle (completed quickly or was never started)
                    state == RetichatBridge.PropagationState.PR_IDLE && lastState > 0 -> {
                        Log.i(TAG, "Propagation returned to idle — sync done")
                        propagationNodeManager.reportSuccess()
                        return@launch
                    }
                    // Error states (>= 0xF0)
                    state >= 0xF0 -> {
                        Log.w(TAG, "Propagation failed: ${stateName(state)} from $nodeHex")
                        propagationNodeManager.reportFailure()
                        if (retriesRemaining > 0) {
                            pollPropagationNode(retriesRemaining - 1)
                        }
                        return@launch
                    }
                }

                delay(MONITOR_POLL_MS)
            }

            // Timeout
            if (isActive) {
                Log.w(TAG, "Propagation monitor timed out after ${MONITOR_TIMEOUT_MS / 1000}s for $nodeHex")
                RetichatBridge.routerCancelPropagation(routerHandle)
                propagationNodeManager.reportFailure()
                if (retriesRemaining > 0) {
                    pollPropagationNode(retriesRemaining - 1)
                }
            }
        }
    }

    /** Human-readable name for PR_* state constants. */
    private fun stateName(state: Int): String = when (state) {
        RetichatBridge.PropagationState.PR_IDLE              -> "IDLE"
        RetichatBridge.PropagationState.PR_PATH_REQUESTED    -> "PATH_REQUESTED"
        RetichatBridge.PropagationState.PR_LINK_ESTABLISHING -> "LINK_ESTABLISHING"
        RetichatBridge.PropagationState.PR_LINK_ESTABLISHED  -> "LINK_ESTABLISHED"
        RetichatBridge.PropagationState.PR_REQUEST_SENT      -> "REQUEST_SENT"
        RetichatBridge.PropagationState.PR_RECEIVING         -> "RECEIVING"
        RetichatBridge.PropagationState.PR_RESPONSE_RECEIVED -> "RESPONSE_RECEIVED"
        RetichatBridge.PropagationState.PR_COMPLETE          -> "COMPLETE"
        RetichatBridge.PropagationState.PR_NO_PATH           -> "NO_PATH"
        RetichatBridge.PropagationState.PR_NO_IDENTITY_RCVD  -> "NO_IDENTITY_RCVD"
        RetichatBridge.PropagationState.PR_NO_ACCESS         -> "NO_ACCESS"
        RetichatBridge.PropagationState.PR_FAILED            -> "FAILED"
        else -> "UNKNOWN(0x${"%02X".format(state)})"
    }

    // ---- Reticulum bootstrap ----

    private suspend fun initReticulum(app: RetichatApp) {
        // Skip if already running with valid handles (idempotent start)
        if (identityHandle != 0L && routerHandle != 0L) {
            Log.i(TAG, "initReticulum: already running (identity=$identityHandle, router=$routerHandle)")
            return
        }

        if (!RetichatBridge.isLoaded) {
            app.updateServiceState(error = "Native library not loaded")
            Log.w(TAG, "Native library not loaded — skipping init")
            return
        }

        val configDir = File(filesDir, "reticulum").also { it.mkdirs() }

        // Read enabled interfaces from Room and generate config
        var interfaces = app.database.interfaceConfigDao().enabledInterfaces()
        val useDefault = interfaces.isEmpty() && UserPreferences.isDefaultTcpEnabled(this)
        if (useDefault) {
            // No user-configured interfaces — use a default public endpoint
            val ep = DefaultEndpointManager.current
            Log.i(TAG, "No interfaces configured — using default endpoint ${ep.first}:${ep.second}")
            interfaces = listOf(DefaultEndpointManager.asInterfaceConfig())
        } else if (UserPreferences.isDefaultTcpEnabled(this)) {
            // User has interfaces AND default is enabled — include both
            val ep = DefaultEndpointManager.current
            interfaces = interfaces + DefaultEndpointManager.asInterfaceConfig()
        }
        generateConfig(configDir, interfaces)

        if (!RetichatBridge.init(configDir.absolutePath)) {
            val err = RetichatBridge.lastError() ?: "unknown"
            if (err.contains("already", ignoreCase = true)) {
                Log.i(TAG, "Reticulum already initialised — continuing with handle setup")
            } else {
                app.updateServiceState(error = "Init failed: $err")
                Log.e(TAG, "Init failed: $err")
                return
            }
        }

        // Identity (create or load from disk)
        val idFile = File(filesDir, "identity")
        identityHandle = if (idFile.exists()) {
            RetichatBridge.identityFromFile(idFile.absolutePath)
        } else {
            val h = RetichatBridge.identityCreate()
            if (h != 0L) RetichatBridge.identityToFile(h, idFile.absolutePath)
            h
        }
        if (identityHandle == 0L) {
            val err = RetichatBridge.lastError() ?: "unknown"
            app.updateServiceState(error = "Identity failed: $err")
            Log.e(TAG, "Identity failed: $err")
            return
        }

        // LXMRouter
        val storagePath = File(filesDir, "lxmf_storage").also { it.mkdirs() }
        routerHandle = RetichatBridge.routerCreate(identityHandle, storagePath.absolutePath)
        if (routerHandle == 0L) {
            val err = RetichatBridge.lastError() ?: "unknown"
            app.updateServiceState(error = "Router failed: $err")
            Log.e(TAG, "Router failed: $err")
            return
        }

        // Register delivery identity
        val displayName = UserPreferences.getDisplayName(this)
        destHandle = RetichatBridge.routerRegisterDelivery(
            routerHandle, identityHandle, displayName,
        )

        // Configure the repository with our own hash and router handle
        val repo = app.repository
        val destHash = RetichatBridge.destinationHash(identityHandle, "lxmf", "delivery")
            ?: ByteArray(0)
        repo.configure(destHash, routerHandle, identityHandle)

        // Delivery callback → repository handles inbound messages
        RetichatBridge.routerSetDeliveryCallback(routerHandle, object : MessageCallback {
            override fun onMessage(
                hash: ByteArray, srcHash: ByteArray, destHash: ByteArray,
                title: String, content: String, timestamp: Double, signatureValid: Boolean,
                fieldsRaw: ByteArray,
            ) {
                repo.onMessageReceived(
                    hash, srcHash, destHash, title, content, timestamp, signatureValid,
                    fieldsRaw,
                )
            }
        })

        // Announce callback → update contact display names from announces
        RetichatBridge.routerSetAnnounceCallback(routerHandle, object : AnnounceCallback {
            override fun onAnnounce(destHash: ByteArray, displayName: String?) {
                repo.onAnnounceReceived(destHash, displayName)
            }
        })

        // Apply announce-drop preference (opt-in, default off)
        if (UserPreferences.isDropAnnouncesEnabled(this)) {
            RetichatBridge.setDropAnnounces(true)
        }

        // Announce our delivery destination
        if (destHash.isNotEmpty()) {
            RetichatBridge.routerAnnounce(routerHandle, destHash)
        }

        val hashHex = destHash.joinToString("") { "%02x".format(it) }
        app.updateServiceState(
            isInitialized = true,
            identityHashHex = hashHex,
            interfaceCount = interfaces.size,
        )
        Log.i(TAG, "Reticulum started — dest=$hashHex, ${interfaces.size} interface(s)")
        isInitialised = true

        // Give the network a moment to settle, then poll the propagation node
        delay(2000)
        pollPropagationNode()
    }

    private fun tearDown(app: RetichatApp) {
        isInitialised = false

        // Cancel any running propagation monitor
        propagationMonitorJob?.cancel()
        propagationMonitorJob = null

        // Clear the repository's handle references first so no stale handles are used
        app.repository.configure(ByteArray(0), 0L, 0L)

        if (routerHandle != 0L) {
            RetichatBridge.routerDestroy(routerHandle)
            routerHandle = 0L
        }
        if (identityHandle != 0L) {
            RetichatBridge.identityDestroy(identityHandle)
            identityHandle = 0L
        }
        destHandle = 0L
        RetichatBridge.shutdown()
        app.updateServiceState()
    }

    // ---- Config generation ----

    /**
     * Write a Reticulum config file from the enabled Room interface entities.
     * Always overwrites so that Settings changes take effect on restart.
     */
    private fun generateConfig(
        configDir: File,
        interfaces: List<InterfaceConfigEntity>,
    ) {
        val sb = StringBuilder()

        sb.appendLine("[reticulum]")
        sb.appendLine("  enable_transport = false")
        sb.appendLine("  share_instance = false")
        sb.appendLine("  shared_instance_port = 37428")
        sb.appendLine("  instance_control_port = 37429")
        sb.appendLine("  panic_on_interface_errors = false")
        sb.appendLine()
        sb.appendLine("[logging]")
        sb.appendLine("  loglevel = 4")
        sb.appendLine()
        sb.appendLine("[interfaces]")

        for (iface in interfaces) {
            sb.appendLine("  [[${iface.name}]]")
            sb.appendLine("    type = ${iface.type}")
            sb.appendLine("    enabled = true")
            try {
                val json = JSONObject(iface.configJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optString(key, "")
                    if (value.isNotEmpty()) {
                        sb.appendLine("    $key = $value")
                    }
                }
            } catch (_: Exception) {
                // skip malformed configJson
            }
            sb.appendLine()
        }

        File(configDir, "config").writeText(sb.toString())
        Log.d(TAG, "Wrote config with ${interfaces.size} interface(s)")
    }
}

// ---------------------------------------------------------------------------
// Package-level helper so PropagationPollWorker can generate the config
// without needing a Service instance.
// ---------------------------------------------------------------------------

/**
 * Write a Reticulum config file from the enabled Room interface entities.
 * Same logic as [ReticulumService.generateConfig] but accessible from
 * outside the Service class.
 */
internal fun writeReticulumConfig(
    configDir: File,
    interfaces: List<InterfaceConfigEntity>,
) {
    val sb = StringBuilder()

    sb.appendLine("[reticulum]")
    sb.appendLine("  enable_transport = false")
    sb.appendLine("  share_instance = false")
    sb.appendLine("  shared_instance_port = 37428")
    sb.appendLine("  instance_control_port = 37429")
    sb.appendLine("  panic_on_interface_errors = false")
    sb.appendLine()
    sb.appendLine("[logging]")
    sb.appendLine("  loglevel = 4")
    sb.appendLine()
    sb.appendLine("[interfaces]")

    for (iface in interfaces) {
        sb.appendLine("  [[${iface.name}]]")
        sb.appendLine("    type = ${iface.type}")
        sb.appendLine("    enabled = true")
        try {
            val json = JSONObject(iface.configJson)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (value.isNotEmpty()) {
                    sb.appendLine("    $key = $value")
                }
            }
        } catch (_: Exception) {
            // skip malformed configJson
        }
        sb.appendLine()
    }

    File(configDir, "config").writeText(sb.toString())
}
