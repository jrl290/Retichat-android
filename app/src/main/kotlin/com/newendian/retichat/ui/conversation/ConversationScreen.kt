package com.newendian.retichat.ui.conversation

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Info
import androidx.paging.LoadState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.db.entity.AttachmentEntity
import com.newendian.retichat.data.db.entity.ChannelMessageEntity
import com.newendian.retichat.data.db.entity.MessageEntity
import com.newendian.retichat.service.ConnectionStateManager
import com.newendian.retichat.ui.theme.BubbleIncoming
import com.newendian.retichat.ui.theme.BubbleIncomingDark
import com.newendian.retichat.ui.theme.BubbleOutgoing
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// Sender-name colour palette for group chats
private val senderColors = listOf(
    Color(0xFF007AFF), // blue
    Color(0xFFFF9500), // orange
    Color(0xFF34C759), // green
    Color(0xFFAF52DE), // purple
    Color(0xFFFF2D55), // pink
    Color(0xFF5AC8FA), // teal
    Color(0xFFFFCC00), // yellow
    Color(0xFFFF6482), // coral
)

/**
 * Selects between a DM/group conversation backed by LXMF and a pub-sub
 * channel backed by RFed. Mirrors iOS `ConversationMode`. Both modes share
 * the same chrome, message bubble, and input bar — only the data source
 * and a few affordances differ.
 */
sealed class ConversationMode {
    data class Dm(val chatId: String) : ConversationMode()
    data class Channel(val channelId: String) : ConversationMode()
}

/**
 * Unified conversation entry point. DM/group chats and RFed channels both
 * land here; mode-specific content is dispatched internally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    mode: ConversationMode,
    onBack: () -> Unit,
    onLeft: () -> Unit = {},
    viewModel: ConversationViewModel? = null,
) {
    when (mode) {
        is ConversationMode.Dm -> DmConversationContent(
            chatId = mode.chatId,
            onBack = onBack,
            viewModel = viewModel,
        )
        is ConversationMode.Channel -> ChannelConversationContent(
            channelId = mode.channelId,
            onBack = onBack,
            onLeft = onLeft,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DmConversationContent(
    chatId: String,
    onBack: () -> Unit,
    viewModel: ConversationViewModel? = null,
) {
    // Track which chat is visible so notifications are suppressed
    DisposableEffect(chatId) {
        RetichatApp.activeChatId = chatId
        // Open a persistent APP_LINK to the peer for the lifetime of the
        // screen so the very first DIRECT send doesn't have to wait for
        // path + link establishment from cold.  Idempotent; no-op for
        // groups (handled inside the VM).
        viewModel?.onConversationVisible()
        onDispose {
            RetichatApp.activeChatId = null
            viewModel?.onConversationHidden()
        }
    }

    val context = LocalContext.current
    val app = context.applicationContext as RetichatApp
    val scope = rememberCoroutineScope()
    val lazyItems = viewModel?.pagedMessages?.collectAsLazyPagingItems()
    val contactNames = viewModel?.contactNames?.collectAsState()?.value ?: emptyMap()
    val chatEntity = viewModel?.chat?.collectAsState()?.value
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isGroup = chatEntity?.isGroup == true
    val isPendingInvite = viewModel?.isPendingInvite?.collectAsState()?.value ?: false

    // Pending attachment state
    var pendingAttachmentName by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentData by remember { mutableStateOf<ByteArray?>(null) }

    var showChatInfo by remember { mutableStateOf(false) }
    val groupMembers = viewModel?.groupMembers?.collectAsState()?.value ?: emptyList()

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val cr = context.contentResolver
                // Read the filename from the URI
                val filename = cr.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    } else null
                } ?: uri.lastPathSegment ?: "attachment"

                val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    pendingAttachmentName = filename
                    pendingAttachmentData = bytes
                }
            } catch (e: Exception) {
                android.util.Log.e("ConversationScreen", "Failed to read attachment", e)
            }
        }
    }

    // Track keyboard visibility for bottom padding
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottom > 0

    // Auto-scroll to newest message when the keyboard opens
    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible && (lazyItems?.itemCount ?: 0) > 0) {
            listState.animateScrollToItem(0)
        }
    }

    // Auto-scroll to newest message when a new message arrives, but only if
    // the user is already near the bottom — otherwise we'd interrupt them
    // while they're scrolled up reading history.
    val itemCount = lazyItems?.itemCount ?: 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0 && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    // Resolve the display name for the top bar
    val displayTitle = chatEntity?.name
        ?: chatId.removePrefix("dm_").take(12)

    // Member count for group chats
    val memberCount = if (isGroup) groupMembers.size else 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable {
                            showChatInfo = true
                        }
                    ) {
                        Text(displayTitle, style = MaterialTheme.typography.titleMedium)
                        if (isGroup && memberCount > 0) {
                            Text(
                                "$memberCount members",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showChatInfo = true
                    }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "Chat info",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // Messages – reverse layout so index 0 is at the bottom (newest).
            // The list naturally starts at the latest message; new incoming
            // messages and keyboard show/hide are handled by the LaunchedEffect
            // that scrolls to index 0.
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (lazyItems != null) {
                    items(count = lazyItems.itemCount) { index ->
                        val msg = lazyItems[index]
                        if (msg != null) {
                            MessageRow(
                                msg = msg,
                                showSender = isGroup || !msg.isOutbound,
                                contactNames = contactNames,
                                viewModel = viewModel,
                            )
                        }
                    }
                    // "Load earlier messages" indicator at top of list
                    // (visually top because reverseLayout = true → end of items).
                    val appendState = lazyItems.loadState.append
                    if (appendState is LoadState.Loading) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Loading earlier messages\u2026",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Input bar (or invite prompt for pending group invites)
            if (isPendingInvite) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        HorizontalDivider()
                        Text(
                            text = "You have been invited to this group",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { viewModel?.declineInvite(); onBack() },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("Decline") }
                            Button(
                                onClick = { viewModel?.acceptInvite() },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("Accept") }
                        }
                    }
                }
            } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Pending attachment preview
                    if (pendingAttachmentName != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Show image thumbnail preview if it's an image
                            val isImageAttachment = pendingAttachmentName?.let { isImageFile(it) } == true
                            if (isImageAttachment && pendingAttachmentData != null) {
                                AsyncImage(
                                    model = pendingAttachmentData,
                                    contentDescription = "Preview",
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = pendingAttachmentName ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    pendingAttachmentName = null
                                    pendingAttachmentData = null
                                },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Attach",
                                tint = if (pendingAttachmentName != null)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message") },
                            shape = MaterialTheme.shapes.large,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            ),
                        )

                        Spacer(Modifier.width(4.dp))

                        FilledIconButton(
                            onClick = {
                                val attachName = pendingAttachmentName
                                val attachData = pendingAttachmentData
                                if (attachName != null && attachData != null) {
                                    viewModel?.sendWithAttachment(draft, attachName, attachData)
                                } else {
                                    viewModel?.send(draft)
                                }
                                draft = ""
                                pendingAttachmentName = null
                                pendingAttachmentData = null
                            },
                            enabled = draft.isNotBlank() || pendingAttachmentName != null,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
            } // end else (isPendingInvite)
        }
    }

    if (showChatInfo) {
        ChatInfoSheet(
            chatId = chatId,
            title = displayTitle,
            isGroup = isGroup,
            peerHashHex = chatEntity?.memberHashes?.takeIf { !isGroup },
            members = groupMembers,
            contactNames = contactNames,
            onDismiss = { showChatInfo = false },
            onRename = { newName ->
                scope.launch {
                    if (isGroup) {
                        app.repository.renameGroup(chatId, newName)
                    } else {
                        val destHashHex = chatEntity?.memberHashes.orEmpty()
                        if (destHashHex.isNotBlank()) {
                            app.repository.renameContact(destHashHex, newName)
                        }
                    }
                }
            },
            onArchive = {
                scope.launch {
                    app.repository.archiveChat(chatId)
                    onBack()
                }
            },
            onDelete = {
                scope.launch {
                    app.repository.deleteChat(chatId)
                    onBack()
                }
            },
            onLeave = {
                scope.launch {
                    app.repository.leaveGroupChat(chatId)
                    onBack()
                }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Channel mode (RFed pub-sub channels)
// Reuses MessageRow/input bar so visual conventions match DM/group exactly.
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelConversationContent(
    channelId: String,
    onBack: () -> Unit,
    onLeft: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as RetichatApp
    val scope = rememberCoroutineScope()

    val channel by app.rfedChannelClient.channelsFlow()
        .map { list -> list.firstOrNull { it.id == channelId } }
        .collectAsState(initial = null)
    val channelMessages by app.rfedChannelClient.messagesFlow(channelId)
        .collectAsState(initial = emptyList())
    val canPullMoreMap by app.rfedChannelClient.canPullMore.collectAsState()
    val pullInFlight by app.rfedChannelClient.pullInFlight.collectAsState()
    val rfedLinkGeneration by app.rfedChannelClient.rfedLinkGeneration.collectAsState()
    val channelPullKey = channelId.lowercase()
    // Unknown (`null`) means "might be more" so the manual paging affordance
    // stays visible until the server explicitly reports `more_pending = false`.
    val canPullMore = canPullMoreMap[channelPullKey] != false
    val isPulling = channelPullKey in pullInFlight
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestChannel by rememberUpdatedState(channel)
    val latestIsPulling by rememberUpdatedState(isPulling)
    var lastAutoPullGeneration by remember(channelId) { mutableStateOf<Int?>(null) }

    var draft by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    DisposableEffect(channelId) {
        app.rfedChannelClient.retainRfedLinkMonitor()
        onDispose {
            app.rfedChannelClient.releaseRfedLinkMonitor()
        }
    }

    LaunchedEffect(channel?.id) {
        val ch = channel ?: return@LaunchedEffect
        app.rfedChannelClient.markChannelOpenedForStreaming(ch.id)
        app.rfedChannelClient.resetCanPullMore(ch.id)
    }

    // Auto-pull once per rfed node link establishment while the channel screen
    // is open. Initial open also flows through this effect because the current
    // generation is observed immediately when the screen starts collecting it.
    LaunchedEffect(channel?.id, rfedLinkGeneration) {
        val ch = channel ?: return@LaunchedEffect
        if (ConnectionStateManager.rfedNodeLinkStatus() != RetichatBridge.AppLinkStatus.ACTIVE) {
            return@LaunchedEffect
        }
        val generation = rfedLinkGeneration
        if (lastAutoPullGeneration == generation) return@LaunchedEffect
        if (isPulling) return@LaunchedEffect
        lastAutoPullGeneration = generation
        runCatching { app.rfedChannelClient.pullDeferred(ch) }
    }

    DisposableEffect(lifecycleOwner, channelId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val ch = latestChannel ?: return@LifecycleEventObserver
                lastAutoPullGeneration = rfedLinkGeneration
                app.rfedChannelClient.resetCanPullMore(ch.id)
                if (!latestIsPulling) {
                    scope.launch {
                        runCatching { app.rfedChannelClient.pullDeferred(ch) }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottom > 0
    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible && channelMessages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(channelMessages.size) {
        if (channelMessages.isNotEmpty() && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    // Synthesize MessageEntity rows so we can reuse the DM MessageRow.
    // Channels never carry attachments, and pass viewModel = null so
    // attachmentsFor() returns the empty default. The channel's `sendState`
    // is mapped to `MessageState` so the bubble renders only ✓ (sent to
    // rfed) or ✗ in red (failed) — no "sending" hourglass while in flight.
    val displayMessages = remember(channelMessages) {
        channelMessages.map { ch ->
            val mappedState = if (ch.isOutbound) when (ch.sendState) {
                ChannelMessageEntity.SEND_STATE_SENT -> RetichatBridge.MessageState.SENT
                ChannelMessageEntity.SEND_STATE_FAILED -> RetichatBridge.MessageState.FAILED
                // SEND_STATE_SENDING (or any unknown) → use CANCELLED so the
                // glyph helper returns "" and no indicator is drawn.
                else -> RetichatBridge.MessageState.CANCELLED
            } else {
                RetichatBridge.MessageState.DELIVERED
            }
            MessageEntity(
                id = ch.id,
                chatId = ch.channelId,
                senderHashHex = ch.sourceHashHex,
                content = ch.content,
                timestamp = ch.timestamp,
                isOutbound = ch.isOutbound,
                state = mappedState,
            )
        }
    }
    val title = channel?.let { "#${it.channelName}" } ?: channelId.take(12)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "Channel info",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding()
                .navigationBarsPadding(),
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(items = displayMessages.asReversed(), key = { it.id }) { msg ->
                    MessageRow(
                        msg = msg,
                        showSender = !msg.isOutbound,
                        contactNames = emptyMap(),
                        viewModel = null,
                    )
                }
                // "Load earlier messages" — visible until the server explicitly
                // reports `more_pending = false` for this channel.
                if (canPullMore && channel != null) {
                    item(key = "__channel_load_earlier__") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            TextButton(
                                onClick = {
                                    val ch = channel ?: return@TextButton
                                    scope.launch {
                                        runCatching { app.rfedChannelClient.pullDeferred(ch) }
                                    }
                                },
                                enabled = !isPulling,
                            ) {
                                if (isPulling) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Loading\u2026",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Text(
                                        "Load earlier messages",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Input bar (mirrors DM exactly, minus the attach button).
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(channel?.let { "Message #${it.channelName}\u2026" } ?: "Message")
                        },
                        shape = MaterialTheme.shapes.large,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    FilledIconButton(
                        onClick = {
                            val ch = channel ?: return@FilledIconButton
                            val text = draft
                            draft = ""
                            app.applicationScope.launch {
                                app.rfedChannelClient.sendMessage(ch, text)
                            }
                        },
                        enabled = draft.isNotBlank() && channel != null,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }

    if (showInfo) {
        com.newendian.retichat.ui.channels.ChannelInfoSheet(
            channelName = channel?.channelName ?: "",
            channelId = channelId,
            onDismiss = { showInfo = false },
            onLeave = {
                showInfo = false
                val ch = channel
                scope.launch {
                    if (ch != null) app.rfedChannelClient.leaveChannel(ch.id)
                    onLeft()
                }
            },
        )
    }
}

@Composable
private fun MessageRow(
    msg: MessageEntity,
    showSender: Boolean,
    contactNames: Map<String, String>,
    viewModel: ConversationViewModel?,
) {
    val isOut = msg.isOutbound
    val alignment = if (isOut) Alignment.End else Alignment.Start

    // Load attachments for this message
    val attachments by viewModel?.attachmentsFor(msg.id)
        ?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<AttachmentEntity>()) }

    // Bubble shape: rounded with a small tail on the sender's side
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isOut) 18.dp else 4.dp,
        bottomEnd = if (isOut) 4.dp else 18.dp,
    )

    val dark = isSystemInDarkTheme()
    val bgColor = if (isOut) BubbleOutgoing else if (dark) BubbleIncomingDark else BubbleIncoming
    val contentColor = if (isOut) Color.White else if (dark) Color.White else Color.Black

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isOut) 48.dp else 0.dp,
                end = if (isOut) 0.dp else 48.dp,
            ),
        horizontalAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(bgColor)
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 10.dp,
                    bottom = 10.dp,
                ),
        ) {
            Column {
                // Sender name for incoming messages
                if (!isOut && showSender) {
                    val senderName = contactNames[msg.senderHashHex]
                        ?: msg.senderHashHex.take(8)
                    val nameColor = senderColor(msg.senderHashHex)
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = nameColor,
                    )
                    Spacer(Modifier.height(2.dp))
                }

                // Display attachments (images inline, files as chips)
                if (attachments.isNotEmpty()) {
                    val ctx = LocalContext.current
                    attachments.forEach { att ->
                        val openAttachment = {
                            try {
                                val file = File(att.localPath)
                                val uri = FileProvider.getUriForFile(
                                    ctx,
                                    "${ctx.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, att.mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(intent)
                            } catch (e: Exception) {
                                android.util.Log.e("ConversationScreen", "Cannot open attachment", e)
                            }
                        }

                        if (att.mimeType.startsWith("image/")) {
                            // Inline image — tap to open full-screen
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(File(att.localPath))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = att.filename,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { openAttachment() },
                                contentScale = ContentScale.FillWidth,
                            )
                            Spacer(Modifier.height(6.dp))
                        } else {
                            // Non-image file attachment — tap to open
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(contentColor.copy(alpha = 0.08f))
                                    .clickable { openAttachment() }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = contentColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = att.filename,
                                    color = contentColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                // Text content (with clickable hyperlinks)
                if (msg.content.isNotBlank()) {
                    Text(
                        text = linkifyText(msg.content, contentColor),
                        color = contentColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // Upload progress bar (only for messages with attachments)
                if (isOut && attachments.isNotEmpty() && msg.state == RetichatBridge.MessageState.SENDING && msg.progress > 0f && msg.progress < 1f) {
                    val pct = ((msg.progress * 100).toInt()).coerceIn(0, 100)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LinearProgressIndicator(
                            progress = { msg.progress },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color.White.copy(alpha = 0.85f),
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "$pct%",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatMessageTime(msg.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.6f),
                    )

                    if (isOut) {
                        Spacer(Modifier.width(4.dp))
                        val isFailed = msg.state == RetichatBridge.MessageState.FAILED
                        Text(
                            text = stateIcon(msg.state),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isFailed) Color(0xFFFF5252)
                                else Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

// ---- Helpers ----

private fun senderColor(hash: String): Color {
    val idx = hash.hashCode().and(0x7FFFFFFF) % senderColors.size
    return senderColors[idx]
}

private val msgTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatMessageTime(millis: Long): String =
    msgTimeFormat.format(Date(millis))

private fun stateIcon(state: Int): String = when (state) {
    RetichatBridge.MessageState.GENERATING -> "\u23F3"  // hourglass
    RetichatBridge.MessageState.OUTBOUND   -> "\u23F3"  // hourglass (queued, waiting for path/link)
    RetichatBridge.MessageState.SENDING    -> "\u2197"  // arrow
    RetichatBridge.MessageState.SENT       -> "\u2713"  // single check
    RetichatBridge.MessageState.DELIVERED  -> "\u2713\u2713" // double check
    RetichatBridge.MessageState.FAILED     -> "\u2717"  // X
    else -> ""
}

/** Check whether a filename looks like an image. */
private fun isImageFile(filename: String): Boolean {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
}

/** Regex matching http/https URLs and lxma:// or lxmf:// addresses. */
private val urlRegex = Regex(
    """(?:https?://|lxma://|lxmf://)[^\s<>\"\)\]\}]+""",
    RegexOption.IGNORE_CASE,
)

/**
 * Build an AnnotatedString where URLs are clickable links that open in
 * the system browser.  Non-URL text keeps [textColor].
 */
private fun linkifyText(
    text: String,
    textColor: Color,
) = buildAnnotatedString {
    var cursor = 0
    for (match in urlRegex.findAll(text)) {
        // Append plain text before the match
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        // Append the URL as a clickable link
        val url = match.value
        withLink(
            LinkAnnotation.Url(
                url = url,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = textColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            )
        ) {
            append(url)
        }
        cursor = match.range.last + 1
    }
    // Remaining plain text
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

// ---- Dummy data for preview ----

private fun dummyMessages(chatId: String): List<MessageEntity> {
    val now = System.currentTimeMillis()
    return when {
        chatId.contains("alice") -> listOf(
            MessageEntity("m1", chatId, "me", "Hey Alice, you around?", now - 600_000, true, RetichatBridge.MessageState.DELIVERED),
            MessageEntity("m2", chatId, "aabbccdd", "Yeah! Just set up my RNode", now - 540_000, false),
            MessageEntity("m3", chatId, "me", "Nice! Can you see my announce?", now - 480_000, true, RetichatBridge.MessageState.DELIVERED),
            MessageEntity("m4", chatId, "aabbccdd", "Yep, loud and clear on 915MHz", now - 420_000, false),
            MessageEntity("m5", chatId, "me", "Sending a test image...", now - 360_000, true, RetichatBridge.MessageState.SENT),
            MessageEntity("m6", chatId, "aabbccdd", "Got it! Looks great \uD83D\uDC4D", now - 330_000, false),
            MessageEntity("m7", chatId, "aabbccdd", "Hey! Just tested Retichat over LoRa \uD83D\uDE80", now - 300_000, false),
        )
        chatId.contains("bob") -> listOf(
            MessageEntity("m10", chatId, "11223344", "Hey, got the new mesh node up", now - 7_200_000, false),
            MessageEntity("m11", chatId, "me", "Awesome! What range are you seeing?", now - 7_100_000, true, RetichatBridge.MessageState.DELIVERED),
            MessageEntity("m12", chatId, "11223344", "About 5km line of sight", now - 7_000_000, false),
            MessageEntity("m13", chatId, "me", "That's solid. The mesh is really coming together", now - 3_700_000, true, RetichatBridge.MessageState.DELIVERED),
            MessageEntity("m14", chatId, "11223344", "The mesh is growing fast", now - 3_600_000, false),
        )
        chatId.contains("meshnet") -> listOf(
            MessageEntity("m20", chatId, "me", "Welcome to the Meshnet Crew group!", now - 86_400_000, true, RetichatBridge.MessageState.DELIVERED),
            MessageEntity("m21", chatId, "aabbccdd", "Thanks for setting this up", now - 80_000_000, false),
            MessageEntity("m22", chatId, "11223344", "Great idea. Need to coordinate relay placement", now - 50_000_000, false),
            MessageEntity("m23", chatId, "55667788", "I can put one on my roof", now - 40_000_000, false),
            MessageEntity("m24", chatId, "me", "Perfect! That covers the east side", now - 30_000_000, true, RetichatBridge.MessageState.DELIVERED),
            MessageEntity("m25", chatId, "11223344", "Anyone near the relay node?", now - 7_200_000, false),
        )
        chatId.contains("carol") -> listOf(
            MessageEntity("m30", chatId, "me", "Here's the latest firmware for the RNode", now - 172_800_000, true, RetichatBridge.MessageState.DELIVERED),
            MessageEntity("m31", chatId, "55667788", "Thanks for the firmware update", now - 86_400_000, false),
        )
        else -> emptyList()
    }
}
