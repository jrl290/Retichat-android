package com.retichat.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.retichat.app.ui.chatlist.ChatListScreen
import com.retichat.app.ui.contacts.QrCodeScreen
import com.retichat.app.ui.conversation.ConversationScreen
import com.retichat.app.ui.newchat.NewChatScreen
import com.retichat.app.ui.newchat.NewGroupScreen

object Routes {
    const val CHAT_LIST    = "chat_list"
    const val CONVERSATION = "conversation/{chatId}"
    const val NEW_CHAT     = "new_chat"
    const val NEW_GROUP    = "new_group"
    const val QR_CODE      = "qr_code"
    const val QR_SCAN      = "qr_scan"

    fun conversation(chatId: String) = "conversation/$chatId"
}

@Composable
fun RetichatNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.CHAT_LIST) {

        composable(Routes.CHAT_LIST) {
            ChatListScreen(
                onChatClick = { chatId -> navController.navigate(Routes.conversation(chatId)) },
                onNewChat   = { navController.navigate(Routes.NEW_CHAT) },
                onShowQr    = { navController.navigate(Routes.QR_CODE) },
            )
        }

        composable(
            route = Routes.CONVERSATION,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            ConversationScreen(
                chatId = chatId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.NEW_CHAT) {
            NewChatScreen(
                onChatCreated = { chatId ->
                    navController.navigate(Routes.conversation(chatId)) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onNewGroup = { navController.navigate(Routes.NEW_GROUP) },
                onScanQr   = { navController.navigate(Routes.QR_SCAN) },
                onBack     = { navController.popBackStack() },
            )
        }

        composable(Routes.NEW_GROUP) {
            NewGroupScreen(
                onGroupCreated = { chatId ->
                    navController.navigate(Routes.conversation(chatId)) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.QR_CODE) {
            QrCodeScreen(
                mode = QrCodeScreen.Mode.SHOW,
                onBack = { navController.popBackStack() },
                onScanned = {},
            )
        }

        composable(Routes.QR_SCAN) {
            QrCodeScreen(
                mode = QrCodeScreen.Mode.SCAN,
                onBack = { navController.popBackStack() },
                onScanned = { destHashHex ->
                    // Pop back to new-chat with result
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("scannedHash", destHashHex)
                    navController.popBackStack()
                },
            )
        }
    }
}
