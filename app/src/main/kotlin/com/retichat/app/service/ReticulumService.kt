package com.retichat.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.retichat.app.R
import com.retichat.app.RetichatApp
import com.retichat.app.bridge.MessageCallback
import com.retichat.app.bridge.RetichatBridge
import java.io.File

/**
 * Foreground service that keeps the Reticulum stack running and processing
 * inbound messages while the app is in the background.
 */
class ReticulumService : Service() {

    companion object {
        private const val CHANNEL_ID = "reticulum_service"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, ReticulumService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReticulumService::class.java))
        }
    }

    private var identityHandle: Long = 0L
    private var routerHandle: Long = 0L
    private var destHandle: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initReticulum()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (routerHandle != 0L) RetichatBridge.routerDestroy(routerHandle)
        if (identityHandle != 0L) RetichatBridge.identityDestroy(identityHandle)
        RetichatBridge.shutdown()
    }

    // ---- Reticulum bootstrap ----

    private fun initReticulum() {
        if (!RetichatBridge.isLoaded) {
            android.util.Log.w("ReticulumService", "Native library not loaded — skipping Reticulum init")
            return
        }
        val configDir = File(filesDir, "reticulum").also { it.mkdirs() }
        ensureDefaultConfig(configDir)

        if (!RetichatBridge.init(configDir.absolutePath)) {
            android.util.Log.e("ReticulumService", "Init failed: ${RetichatBridge.lastError()}")
            return
        }

        // Identity (create or load)
        val idFile = File(filesDir, "identity")
        identityHandle = if (idFile.exists()) {
            RetichatBridge.identityFromFile(idFile.absolutePath)
        } else {
            val h = RetichatBridge.identityCreate()
            if (h != 0L) RetichatBridge.identityToFile(h, idFile.absolutePath)
            h
        }
        if (identityHandle == 0L) {
            android.util.Log.e("ReticulumService", "Identity failed: ${RetichatBridge.lastError()}")
            return
        }

        // Router
        val storagePath = File(filesDir, "lxmf_storage").also { it.mkdirs() }
        routerHandle = RetichatBridge.routerCreate(identityHandle, storagePath.absolutePath)
        if (routerHandle == 0L) {
            android.util.Log.e("ReticulumService", "Router failed: ${RetichatBridge.lastError()}")
            return
        }

        // Register delivery identity
        destHandle = RetichatBridge.routerRegisterDelivery(
            routerHandle, identityHandle, "Retichat"
        )

        // Delivery callback → repository
        val app = application as RetichatApp
        val repo = app.repository
        val selfHash = RetichatBridge.identityHash(identityHandle) ?: ByteArray(0)
        val destHash = RetichatBridge.destinationHash(identityHandle, "lxmf", "delivery")
            ?: selfHash
        repo.configure(destHash, routerHandle)

        RetichatBridge.routerSetDeliveryCallback(routerHandle, object : MessageCallback {
            override fun onMessage(
                hash: ByteArray, srcHash: ByteArray, destHash: ByteArray,
                title: String, content: String, timestamp: Double, signatureValid: Boolean,
            ) {
                repo.onMessageReceived(hash, srcHash, destHash, title, content, timestamp, signatureValid)
            }
        })

        // Announce
        if (destHash.isNotEmpty()) {
            RetichatBridge.routerAnnounce(routerHandle, destHash)
        }

        android.util.Log.i("ReticulumService", "Reticulum started, dest=${destHash.joinToString("") { "%02x".format(it) }}")
    }

    /**
     * Write a minimal Reticulum config file if none exists.
     * Users can later edit this to add TCP/UDP/RNode interfaces.
     */
    private fun ensureDefaultConfig(dir: File) {
        val configFile = File(dir, "config")
        if (configFile.exists()) return

        configFile.writeText("""
            [reticulum]
              enable_transport = false
              share_instance = false
              shared_instance_port = 37428
              instance_control_port = 37429
              panic_on_interface_errors = false

            [logging]
              loglevel = 4

            [interfaces]
              # Add your interfaces here.  Examples:
              #
              # [[TCP Client]]
              #   type = TCPClientInterface
              #   enabled = true
              #   target_host = 192.168.1.100
              #   target_port = 5454
        """.trimIndent() + "\n")
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_service_running))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
}
