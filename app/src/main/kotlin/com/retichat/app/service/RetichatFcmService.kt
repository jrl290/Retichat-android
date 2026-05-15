package com.retichat.app.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.retichat.app.RetichatApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging entry point.
 *
 * Replaces the old [PersistentConnectionService] foreground-service model
 * with a fully push-driven lifecycle: when the rfed bridge has new traffic
 * for us, it dispatches an FCM data message that wakes this service for
 * a few seconds, which in turn enqueues an expedited [WakeWorker] to
 * spin up the Reticulum stack and drain whatever's pending.
 *
 * No payload is required from the server — the wakeup itself is the
 * signal. Any `data` keys are passed through to the worker for diagnostics.
 */
class RetichatFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "RetichatFcm"
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token refreshed: ${token.take(12)}…")
        UserPreferences.setFcmDeviceToken(applicationContext, token)
        // Re-register with the rfed.fcm bridge so the new token replaces the old.
        val app = applicationContext as? RetichatApp ?: return
        app.applicationScope.launch(Dispatchers.IO) {
            val acquired = StackRuntime.acquire(applicationContext)
            try {
                if (acquired && StackRuntime.selfDestHash.size == 16) {
                    FcmTokenRegistrar.registerIfNeeded(applicationContext, StackRuntime.selfDestHash)
                }
            } finally {
                StackRuntime.release()
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.i(TAG, "FCM wakeup received (data=${remoteMessage.data.size} keys)")
        // The wake itself is the signal — enqueue an expedited worker that
        // will pull whatever's pending. Doing the work inline here would
        // race against the OS killing this process.
        WakeWorker.enqueue(applicationContext)
    }
}
