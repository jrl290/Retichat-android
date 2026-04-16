package com.retichat.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.retichat.app.MainActivity
import com.retichat.app.R

/**
 * Optional foreground service that keeps the Reticulum connection alive
 * even when the app is in the background or the screen is off.
 *
 * This is **not** enabled by default because it uses more battery.
 * The user activates it from Settings → "Persistent connection".
 *
 * When active, the system shows a persistent notification (which the
 * user can long-press → minimize to hide in the notification shade).
 *
 * The service itself doesn't manage the Reticulum stack — that's still
 * handled by [ReticulumService].  This service only provides the
 * foreground flag so the OS won't kill the process.
 */
class PersistentConnectionService : Service() {

    companion object {
        private const val TAG = "PersistentConnSvc"
        private const val CHANNEL_ID = "retichat_persistent"
        private const val NOTIF_ID = 9001
        const val PREF_NAME = "persistent_connection"
        const val PREF_KEY_ENABLED = "enabled"

        /** True when the foreground service is running. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Check if the user has opted in to persistent connection. */
        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getBoolean(PREF_KEY_ENABLED, false)
        }

        /** Save the user's preference and start/stop accordingly. */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_KEY_ENABLED, enabled)
                .apply()

            if (enabled) {
                start(context)
            } else {
                stop(context)
            }
        }

        /** Start the foreground service (only if preference is enabled). */
        fun start(context: Context) {
            if (!isEnabled(context)) return
            try {
                val intent = Intent(context, PersistentConnectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start persistent service: ${e.message}")
            }
        }

        /** Stop the foreground service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, PersistentConnectionService::class.java))
        }

        /** Create the low-importance notification channel for the persistent notification. */
        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_persistent),
                    NotificationManager.IMPORTANCE_LOW,   // no sound, no peek
                ).apply {
                    description = context.getString(R.string.notification_channel_persistent_desc)
                    setShowBadge(false)
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel(this)

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        isRunning = true

        // Make sure the regular service is also running
        ReticulumService.start(this)

        Log.i(TAG, "Persistent connection service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "Persistent connection service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_persistent_title))
            .setContentText(getString(R.string.notification_persistent_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
