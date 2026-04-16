package com.retichat.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.retichat.app.bridge.RetichatBridge
import com.retichat.app.data.db.RetichatDatabase
import com.retichat.app.data.repository.ChatRepository
import com.retichat.app.service.PropagationPollWorker
import com.retichat.app.service.MessageNotificationHelper
import com.retichat.app.service.NetworkMonitor
import com.retichat.app.service.PersistentConnectionService
import com.retichat.app.service.ReticulumService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Observable state of the background Reticulum service. */
data class ServiceState(
    val isInitialized: Boolean = false,
    val identityHashHex: String = "",
    val interfaceCount: Int = 0,
    val error: String? = null,
)

class RetichatApp : Application() {

    companion object {
        private const val TAG = "RetichatApp"

        /** True while any Activity is in the started-or-resumed state. */
        @Volatile
        var isInForeground: Boolean = false
            private set

        /** Chat ID currently visible on screen, or null. */
        @Volatile
        var activeChatId: String? = null
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

    override fun onCreate() {
        super.onCreate()

        // Create notification channels (idempotent)
        MessageNotificationHelper.createChannel(this)
        PersistentConnectionService.createChannel(this)

        // Start monitoring network connectivity
        NetworkMonitor.register(this)

        // Track foreground state via activity lifecycle.
        // When the last Activity stops, relax keepalive intervals so the
        // still-running service uses fewer resources and survives longer.
        // When an Activity starts, restore defaults for real-time messaging.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(a: Activity) {
                startedActivities++
                if (!isInForeground) {
                    isInForeground = true
                    // Restore fast keepalive for real-time message delivery
                    RetichatBridge.setKeepaliveInterval(0.0)
                    // Reset idle backoff so the next background poll is immediate
                    PropagationPollWorker.resetIdleBackoff(this@RetichatApp)
                    Log.d(TAG, "App entered foreground — keepalive restored, idle backoff reset")
                }
            }
            override fun onActivityStopped(a: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    isInForeground = false
                    // Relax keepalive so the service drains less power in
                    // the background and the system is less likely to kill it.
                    RetichatBridge.setKeepaliveInterval(300.0)
                    Log.d(TAG, "App entered background — keepalive relaxed to 300s")
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        // Enqueue the periodic WorkManager propagation poll (every 15 min).
        // This is the *only* way messages arrive when no Activity is visible.
        PropagationPollWorker.enqueue(this)

        // If the user has enabled persistent connection, start the foreground service.
        // This keeps the process alive even when no Activity is visible.
        if (PersistentConnectionService.isEnabled(this)) {
            PersistentConnectionService.start(this)
        }
    }

    // ---- Service state (updated by ReticulumService) ----

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
