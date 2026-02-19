package com.retichat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.retichat.app.service.ReticulumService
import com.retichat.app.ui.navigation.RetichatNavHost
import com.retichat.app.ui.theme.RetichatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the Reticulum foreground service
        ReticulumService.start(this)

        setContent {
            RetichatTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    RetichatNavHost(navController = navController)
                }
            }
        }
    }
}
