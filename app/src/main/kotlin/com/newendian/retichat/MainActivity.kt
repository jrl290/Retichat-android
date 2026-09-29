package com.newendian.retichat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.newendian.retichat.data.model.hexToBytes
import com.newendian.retichat.service.MessageNotificationHelper
import com.newendian.retichat.service.RTNodeBluetooth
import com.newendian.retichat.service.UserPreferences
import com.newendian.retichat.ui.navigation.RetichatNavHost
import com.newendian.retichat.ui.navigation.Routes
import com.newendian.retichat.ui.theme.RetichatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.newendian.retichat.service.DistroContacts

class MainActivity : ComponentActivity() {

    private var navController: NavHostController? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "POST_NOTIFICATIONS permission granted=$granted")
        requestBluetoothIfWanted()
    }

    /** Bluetooth to nearby RTNodes (RTNodeBluetooth), asked after notifications. */
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        Log.i(TAG, "Bluetooth permissions granted=$granted")
        if (granted) {
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch { RTNodeBluetooth.onPermissionsGranted(app) }
        }
    }

    private fun requestBluetoothIfWanted() {
        if (UserPreferences.isRtnodeBluetoothEnabled(this) && !RTNodeBluetooth.hasPermissions(this)) {
            bluetoothPermissionLauncher.launch(RTNodeBluetooth.PERMISSIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+, then Bluetooth
        // (one permission request at a time).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestBluetoothIfWanted()
        }

        // The stack is held while the app is on screen by RetichatApp's
        // ForegroundHold, not per activity (a rotation would re-take it).

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

        // Handle direct-contact deep links from Retichat iOS, Android, or Columba.
        val uri = intent?.data
        val scheme = uri?.scheme?.lowercase()
        if (uri != null && (scheme == "lxma" || scheme == "lxmf")) {
            val hexHash = IdentityShareFormat.extractDestinationHash(uri.toString()) ?: return
            Log.i(TAG, "Deep link: $scheme://$hexHash")
            DistroContacts.noteShared(IdentityShareFormat.parse(uri.toString()))
            intent?.data = null  // consume so rotation doesn't re-trigger

            val app = applicationContext as RetichatApp
            CoroutineScope(Dispatchers.Main).launch {
                val destBytes = hexHash.hexToBytes()
                DistroContacts.adopt(applicationContext, hexHash)
                // Adds the peer as a contact without touching a known one's
                // names or key.
                val destChatId = app.repository.getOrCreateDirectChat(destBytes)
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

    companion object {
        private const val TAG = "MainActivity"
    }
}
