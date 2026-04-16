package com.retichat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.retichat.app.data.model.Contact
import com.retichat.app.data.model.hexToBytes
import com.retichat.app.service.MessageNotificationHelper
import com.retichat.app.service.ReticulumService
import com.retichat.app.ui.navigation.RetichatNavHost
import com.retichat.app.ui.navigation.Routes
import com.retichat.app.ui.theme.RetichatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var navController: NavHostController? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS permission granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Ensure the Reticulum service is running while the app is visible.
        // This is the only place we start the service — Android 12+ forbids
        // startService() from BroadcastReceivers or WorkManager.
        ReticulumService.start(this)

        setContent {
            RetichatTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nc = rememberNavController()
                    navController = nc
                    RetichatNavHost(navController = nc)
                }
            }
        }

        // If launched from a notification, navigate to the chat
        handleChatIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleChatIntent(intent)
    }

    private fun handleChatIntent(intent: Intent?) {
        // Handle notification tap
        val chatId = intent?.getStringExtra(MessageNotificationHelper.EXTRA_CHAT_ID)
        if (!chatId.isNullOrBlank()) {
            Log.i(TAG, "Navigating to chatId=$chatId from notification")
            MessageNotificationHelper.clearChat(chatId)
            navigateToChat(chatId)
            intent?.removeExtra(MessageNotificationHelper.EXTRA_CHAT_ID)
            return
        }

        // Handle lxmf:// deep link  (e.g. lxmf://ab01cd23ef...)
        val uri = intent?.data
        if (uri != null && uri.scheme.equals("lxmf", ignoreCase = true)) {
            val hexHash = uri.host?.lowercase()?.filter { it in '0'..'9' || it in 'a'..'f' } ?: return
            if (hexHash.isEmpty()) return
            Log.i(TAG, "Deep link: lxmf://$hexHash")
            intent?.data = null  // consume so rotation doesn't re-trigger

            val app = applicationContext as RetichatApp
            CoroutineScope(Dispatchers.Main).launch {
                val destBytes = hexHash.hexToBytes()
                app.repository.addContact(destBytes, hexHash.take(8))
                val contact = Contact(destBytes, hexHash.take(8))
                val destChatId = app.repository.getOrCreateDirectChat(contact)
                navigateToChat(destChatId)
            }
        }
    }

    private fun navigateToChat(chatId: String) {
        window.decorView.post {
            navController?.navigate(Routes.conversation(chatId)) {
                popUpTo(Routes.CHAT_LIST) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-start the service when the app becomes visible.
        // startService is idempotent — if already running, delivers onStartCommand only.
        ReticulumService.start(this)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
