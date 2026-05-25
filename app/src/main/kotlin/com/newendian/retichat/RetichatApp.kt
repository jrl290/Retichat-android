package com.newendian.retichat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.db.RetichatDatabase
import com.newendian.retichat.data.repository.ChatRepository
import com.newendian.retichat.service.MessageNotificationHelper
import com.newendian.retichat.service.ConnectionStateManager
import com.newendian.retichat.service.NetworkMonitor
import com.newendian.retichat.service.PropagationSync
import com.newendian.retichat.service.RfedChannelClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Observable state of the Reticulum stack (set by [com.newendian.retichat.service.StackRuntime]). */
data class ServiceState(
    val isInitialized: Boolean = false,
    val identityHashHex: String = "",
    val interfaceCount: Int = 0,
    val error: String? = null,
)

class RetichatApp : Application() {

    companion object {
        private const val TAG = "RetichatApp"

        /** Initial foreground propagation poll delay (matches iOS 5 s). */
        private const val INITIAL_POLL_DELAY_MS = 5_000L

        /** Foreground propagation poll interval (matches iOS 300 s). */
        private const val POLL_INTERVAL_MS = 300_000L

        @Volatile var isInForeground: Boolean = false
            private set

        @Volatile var activeChatId: String? = null

        /**
         * Process-wide application instance, available without a Context.
         * Used by [com.newendian.retichat.service.StackRuntime] to schedule its
         * delayed shutdown without keeping a leaking Activity reference.
         */
        @Volatile var appInstance: RetichatApp? = null
            private set
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: RetichatDatabase by lazy { RetichatDatabase.getInstance(this) }

    val repository: ChatRepository by lazy {
        ChatRepository(
            appContext = this,
            chatDao = database.chatDao(),
            messageDao = database.messageDao(),
            contactDao = database.contactDao(),
            attachmentDir = File(filesDir, "attachments").also { it.mkdirs() },
            scope = applicationScope,
        )
    }

    val rfedChannelClient: RfedChannelClient by lazy {
        RfedChannelClient(
            appContext = this,
            channelDao = database.channelDao(),
            scope = applicationScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        appInstance = this

        MessageNotificationHelper.createChannel(this)
        NetworkMonitor.register(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(a: Activity) {
                startedActivities++
                if (!isInForeground) {
                    isInForeground = true
                    RetichatBridge.setKeepaliveInterval(0.0)
                    Log.d(TAG, "App foreground — keepalive=default")
                    startForegroundPropagationPolling()
                    // Re-open active conversation links and refresh rfed path tracking.
                    ConnectionStateManager.onAppForeground()
                    ConnectionStateManager.openRfedNodeLink()
                }
            }
            override fun onActivityStopped(a: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    isInForeground = false
                    RetichatBridge.setKeepaliveInterval(300.0)
                    Log.d(TAG, "App background — keepalive=300s")
                    stopForegroundPropagationPolling()
                    // Stop foreground rfed path tracking; it will be refreshed
                    // on the next foreground or network-reconnect event.
                    ConnectionStateManager.closeRfedNodeLink()
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // ---- Foreground LXMF propagation polling ----
    //
    // Mirrors iOS `ChatRepository.startPropagationPolling()` — when the app
    // is in the foreground, fire an initial poll after 5 s and then every
    // 300 s thereafter. Job is cancelled when the app backgrounds.
    private var foregroundPollJob: Job? = null

    private fun startForegroundPropagationPolling() {
        if (foregroundPollJob?.isActive == true) return
        foregroundPollJob = applicationScope.launch {
            // Initial poll after 5 s, then every 300 s — matches iOS cadence.
            delay(INITIAL_POLL_DELAY_MS)
            while (isActive) {
                runCatching { PropagationSync.runOnce(this@RetichatApp) }
                    .onFailure { Log.w(TAG, "foreground propagation poll failed", it) }
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Foreground propagation poll started (interval=${POLL_INTERVAL_MS}ms)")
    }

    private fun stopForegroundPropagationPolling() {
        foregroundPollJob?.cancel()
        foregroundPollJob = null
        Log.d(TAG, "Foreground propagation poll stopped")
    }

    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    fun updateServiceState(
        isInitialized: Boolean = false,
        identityHashHex: String = "",
        interfaceCount: Int = 0,
        error: String? = null,
    ) {
        _serviceState.value = ServiceState(isInitialized, identityHashHex, interfaceCount, error)
    }
}
