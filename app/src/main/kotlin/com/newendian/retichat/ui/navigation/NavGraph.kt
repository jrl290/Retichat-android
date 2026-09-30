package com.newendian.retichat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.newendian.retichat.service.DistroContacts
import com.newendian.retichat.service.DistroManager
import com.newendian.retichat.ui.settings.DistroTransferOfferDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.model.Contact
import com.newendian.retichat.data.model.hexToBytes
import com.newendian.retichat.names.NameBook
import com.newendian.retichat.ui.chatlist.ChatListScreen
import com.newendian.retichat.ui.chatlist.ChatListViewModel
import com.newendian.retichat.ui.contacts.QrCodeScreen
import com.newendian.retichat.ui.conversation.ConversationMode
import com.newendian.retichat.ui.conversation.ConversationScreen
import com.newendian.retichat.ui.conversation.ConversationViewModel
import com.newendian.retichat.ui.channels.JoinChannelScreen
import com.newendian.retichat.ui.newchat.NewChatScreen
import com.newendian.retichat.ui.newchat.NewGroupScreen
import com.newendian.retichat.ui.settings.SettingsScreen
import com.newendian.retichat.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

object Routes {
    const val CHAT_LIST    = "chat_list"
    const val CONVERSATION = "conversation/{chatId}"
    const val NEW_CHAT     = "new_chat"
    const val NEW_GROUP    = "new_group"
    const val QR_CODE      = "qr_code"
    const val QR_SCAN      = "qr_scan"
    const val SETTINGS     = "settings"
    const val IDENTITY     = "identity"
    const val JOIN_CHANNEL = "join_channel"
    const val CHANNEL      = "channel/{channelId}"

    fun conversation(chatId: String) = "conversation/$chatId"
    fun channel(channelId: String) = "channel/$channelId"
}

/**
 * A screen navigates only while it is the one on top. A tap can reach a
 * screen that no longer is:
 *   - a screen stays drawn, and takes taps, while its exit animation runs;
 *   - taps queued while the UI thread is busy land late (a photo arriving).
 * A back arrow tapped twice that way popped the chat list and the graph
 * under it, leaving an empty back stack: a blank app that Back only sends
 * home (2026-09-30). The same late tap on a navigate opens a second copy
 * of a screen.
 */
private fun NavBackStackEntry.isCurrent() = lifecycle.currentState == Lifecycle.State.RESUMED

@Composable
fun RetichatNavHost(navController: NavHostController) {
    val app = LocalContext.current.applicationContext as RetichatApp
    val repository = app.repository
    // Another of our devices may offer us its distro identity at any time.
    DistroTransferOfferDialog()

    NavHost(navController = navController, startDestination = Routes.CHAT_LIST) {

        composable(Routes.CHAT_LIST) { entry ->
            val vm: ChatListViewModel = viewModel(
                factory = ChatListViewModel.Factory(repository, app.rfedChannelClient),
            )
            val svcState by app.serviceState.collectAsState()
            ChatListScreen(
                onChatClick    = { chatId -> if (entry.isCurrent()) navController.navigate(Routes.conversation(chatId)) },
                onChannelClick = { channelId -> if (entry.isCurrent()) navController.navigate(Routes.channel(channelId)) },
                onNewChat      = { if (entry.isCurrent()) navController.navigate(Routes.NEW_CHAT) },
                onShowQr       = { if (entry.isCurrent()) navController.navigate(Routes.QR_CODE) },
                onSettings     = { if (entry.isCurrent()) navController.navigate(Routes.SETTINGS) },
                serviceState   = svcState,
                viewModel      = vm,
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
                mode = ConversationMode.Dm(chatId),
                onBack = { if (entry.isCurrent()) navController.popBackStack() },
                viewModel = vm,
            )
        }

        composable(Routes.NEW_CHAT) { entry ->
            val scope = rememberCoroutineScope()
            // The contacts the user added or shares a group with (allowlisted,
            // as iOS lists them), each under its resolved name.
            val contactsFlow = remember { repository.contacts() }
            val allContacts by contactsFlow.collectAsState(initial = emptyList())
            val selfHex = remember { repository.selfDestHash.joinToString("") { "%02x".format(it) } }
            val contacts = remember(allContacts, selfHex) {
                allContacts.filter { it.isAllowlisted && it.destHashHex != selfHex }
            }
            NewChatScreen(
                onChatCreated = { chatId ->
                    if (entry.isCurrent()) navController.navigate(Routes.conversation(chatId)) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onNewGroup    = { if (entry.isCurrent()) navController.navigate(Routes.NEW_GROUP) },
                onJoinChannel = { if (entry.isCurrent()) navController.navigate(Routes.JOIN_CHANNEL) },
                onScanQr      = { if (entry.isCurrent()) navController.navigate(Routes.QR_SCAN) },
                onBack        = { if (entry.isCurrent()) navController.popBackStack() },
                onDestHashChat = { hexHash ->
                    scope.launch {
                        val destBytes = hexHash.hexToBytes()
                        DistroContacts.adopt(app, hexHash)
                        val chatId = repository.getOrCreateDirectChat(destBytes)
                        if (entry.isCurrent()) navController.navigate(Routes.conversation(chatId)) {
                            popUpTo(Routes.CHAT_LIST)
                        }
                    }
                },
                contacts = contacts,
                onSelectContact = { contact ->
                    scope.launch {
                        val chatId = repository.getOrCreateDirectChat(contact.destHash)
                        if (entry.isCurrent()) navController.navigate(Routes.conversation(chatId)) {
                            popUpTo(Routes.CHAT_LIST)
                        }
                    }
                },
            )
        }

        composable(Routes.NEW_GROUP) { entry ->
            // Show everyone the user has a DM chat with as potential group
            // members, each under its resolved name (DISPLAY_NAMES.md §5.3).
            val previewsFlow = remember { repository.chatPreviews() }
            val chatPreviews by previewsFlow.collectAsState(initial = emptyList())
            val namesFlow = remember { repository.nameBook() }
            val names by namesFlow.collectAsState(initial = NameBook.EMPTY)
            val dmContacts = remember(chatPreviews, names) {
                chatPreviews
                    .filter { !it.isGroup }
                    .map { preview ->
                        Contact(
                            destHash = preview.memberHashes.hexToBytes(),
                            displayName = names.contact(preview.memberHashes),
                        )
                    }
            }
            NewGroupScreen(
                onGroupCreated = { chatId ->
                    if (entry.isCurrent()) navController.navigate(Routes.conversation(chatId)) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onBack = { if (entry.isCurrent()) navController.popBackStack() },
                contacts = dmContacts,
                createGroup = { name, members ->
                    repository.createGroupChat(name, members)
                },
            )
        }

        composable(Routes.QR_CODE) { entry ->
            // Like the web client's sidebar: the distro address is the one to
            // share when this device holds one; otherwise the device address.
            val distro by DistroManager.state.collectAsState()
            val selfHex = distro?.deliveryHashHex ?: remember {
                repository.selfDestHash.joinToString("") { "%02x".format(it) }
            }
            val selfPubKeyHex = distro?.publicKeyHex ?: remember {
                RetichatBridge.identityPublicKey(repository.identityHandle)
                    ?.joinToString("") { "%02x".format(it) } ?: ""
            }
            QrCodeScreen(
                mode = QrCodeScreen.Mode.SHOW,
                onBack = { if (entry.isCurrent()) navController.popBackStack() },
                onScanned = {},
                selfDestHashHex = selfHex,
                selfPublicKeyHex = selfPubKeyHex,
            )
        }

        composable(Routes.QR_SCAN) { entry ->
            val scope = rememberCoroutineScope()
            QrCodeScreen(
                mode = QrCodeScreen.Mode.SCAN,
                onBack = { if (entry.isCurrent()) navController.popBackStack() },
                onScanned = { destHashHex ->
                    // Mirror the manual "Enter LXMF Destination" flow:
                    // add as contact, ensure DM chat exists, navigate.
                    scope.launch {
                        val destBytes = destHashHex.hexToBytes()
                        DistroContacts.adopt(app, destHashHex)
                        val chatId = repository.getOrCreateDirectChat(destBytes)
                        if (entry.isCurrent()) navController.navigate(Routes.conversation(chatId)) {
                            popUpTo(Routes.CHAT_LIST)
                        }
                    }
                },
            )
        }

        composable(Routes.SETTINGS) { entry ->
            val context = LocalContext.current
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    dao = app.database.interfaceConfigDao(),
                    serviceState = app.serviceState,
                    onRestart = {
                        // Tear down and re-bootstrap now so freshly-saved
                        // interface and RFed settings take effect.
                        app.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.newendian.retichat.service.StackRuntime.restart(context)
                        }
                    },
                ),
            )
            SettingsScreen(
                onBack = { if (entry.isCurrent()) navController.popBackStack() },
                viewModel = vm,
                onOpenIdentity = { if (entry.isCurrent()) navController.navigate(Routes.IDENTITY) },
            )
        }

        composable(Routes.IDENTITY) { entry ->
            com.newendian.retichat.ui.settings.IdentityScreen(
                onBack = { if (entry.isCurrent()) navController.popBackStack() },
            )
        }

        composable(Routes.JOIN_CHANNEL) { entry ->
            JoinChannelScreen(
                onJoined = { channelId ->
                    if (entry.isCurrent()) navController.navigate(Routes.channel(channelId)) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
                onBack = { if (entry.isCurrent()) navController.popBackStack() },
            )
        }

        composable(
            route = Routes.CHANNEL,
            arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
        ) { entry ->
            val channelId = entry.arguments?.getString("channelId") ?: return@composable
            ConversationScreen(
                mode = ConversationMode.Channel(channelId),
                onBack = { if (entry.isCurrent()) navController.popBackStack() },
                onLeft = {
                    if (entry.isCurrent()) navController.popBackStack(Routes.CHAT_LIST, inclusive = false)
                },
            )
        }
    }
}
