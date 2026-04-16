package com.retichat.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.retichat.app.RetichatApp
import com.retichat.app.data.model.Contact
import com.retichat.app.data.model.hexToBytes
import com.retichat.app.ui.chatlist.ChatListScreen
import com.retichat.app.ui.chatlist.ChatListViewModel
import com.retichat.app.ui.contacts.QrCodeScreen
import com.retichat.app.ui.conversation.ConversationScreen
import com.retichat.app.ui.conversation.ConversationViewModel
import com.retichat.app.ui.newchat.NewChatScreen
import com.retichat.app.ui.newchat.NewGroupScreen
import com.retichat.app.ui.settings.SettingsScreen
import com.retichat.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

object Routes {
    const val CHAT_LIST    = "chat_list"
    const val CONVERSATION = "conversation/{chatId}"
    const val NEW_CHAT     = "new_chat"
    const val NEW_GROUP    = "new_group"
    const val QR_CODE      = "qr_code"
    const val QR_SCAN      = "qr_scan"
    const val SETTINGS     = "settings"

    fun conversation(chatId: String) = "conversation/$chatId"
}

@Composable
fun RetichatNavHost(navController: NavHostController) {
    val app = LocalContext.current.applicationContext as RetichatApp
    val repository = app.repository

    NavHost(navController = navController, startDestination = Routes.CHAT_LIST) {

        composable(Routes.CHAT_LIST) {
            val vm: ChatListViewModel = viewModel(
                factory = ChatListViewModel.Factory(repository),
            )
            val svcState by app.serviceState.collectAsState()
            ChatListScreen(
                onChatClick = { chatId -> navController.navigate(Routes.conversation(chatId)) },
                onNewChat   = { navController.navigate(Routes.NEW_CHAT) },
                onShowQr    = { navController.navigate(Routes.QR_CODE) },
                onSettings  = { navController.navigate(Routes.SETTINGS) },
                serviceState = svcState,
                viewModel   = vm,
            )
        }

        composable(
            route = Routes.CONVERSATION,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val vm: ConversationViewModel = viewModel(
                factory = ConversationViewModel.Factory(chatId, repository),
            )
            ConversationScreen(
                chatId = chatId,
                onBack = { navController.popBackStack() },
                viewModel = vm,
            )
        }

        composable(Routes.NEW_CHAT) {
            val scope = rememberCoroutineScope()
            NewChatScreen(
                onChatCreated = { chatId ->
                    navController.navigate(Routes.conversation(chatId)) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onNewGroup = { navController.navigate(Routes.NEW_GROUP) },
                onScanQr   = { navController.navigate(Routes.QR_SCAN) },
                onBack     = { navController.popBackStack() },
                onDestHashChat = { hexHash ->
                    scope.launch {
                        val destBytes = hexHash.hexToBytes()
                        repository.addContact(destBytes, hexHash.take(8))
                        val contact = Contact(destBytes, hexHash.take(8))
                        val chatId = repository.getOrCreateDirectChat(contact)
                        navController.navigate(Routes.conversation(chatId)) {
                            popUpTo(Routes.CHAT_LIST)
                        }
                    }
                },
            )
        }

        composable(Routes.NEW_GROUP) {
            // Show everyone the user has a DM chat with as potential group members
            val chatPreviews by repository.chatPreviews().collectAsState(initial = emptyList())
            val dmContacts = remember(chatPreviews) {
                chatPreviews
                    .filter { !it.isGroup }
                    .map { preview ->
                        Contact(
                            destHash = preview.memberHashes.hexToBytes(),
                            displayName = preview.name,
                        )
                    }
            }
            NewGroupScreen(
                onGroupCreated = { chatId ->
                    navController.navigate(Routes.conversation(chatId)) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onBack = { navController.popBackStack() },
                contacts = dmContacts,
                createGroup = { name, members ->
                    repository.createGroupChat(name, members)
                },
            )
        }

        composable(Routes.QR_CODE) {
            val selfHex = remember {
                repository.selfDestHash.joinToString("") { "%02x".format(it) }
            }
            QrCodeScreen(
                mode = QrCodeScreen.Mode.SHOW,
                onBack = { navController.popBackStack() },
                onScanned = {},
                selfDestHashHex = selfHex,
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

        composable(Routes.SETTINGS) {
            val context = LocalContext.current
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    dao = app.database.interfaceConfigDao(),
                    serviceState = app.serviceState,
                    onRestart = {
                        com.retichat.app.service.ReticulumService.restart(context)
                    },
                ),
            )
            SettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = vm,
            )
        }
    }
}
