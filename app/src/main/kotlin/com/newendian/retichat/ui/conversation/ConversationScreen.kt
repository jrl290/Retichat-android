package com.newendian.retichat.ui.conversation

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SelectAll
import androidx.paging.LoadState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.newendian.retichat.PhotoLocationNotRemoved
import com.newendian.retichat.PhotoMetadata
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.db.entity.AttachmentEntity
import com.newendian.retichat.data.db.entity.ChannelMessageEntity
import com.newendian.retichat.data.db.entity.MessageEntity
import com.newendian.retichat.data.repository.Reactions
import com.newendian.retichat.names.ChannelLabel
import com.newendian.retichat.names.NameBook
import com.newendian.retichat.names.SystemText
import com.newendian.retichat.service.ConnectionStateManager
import com.newendian.retichat.ui.theme.BubbleIncoming
import com.newendian.retichat.ui.theme.BubbleIncomingDark
import com.newendian.retichat.ui.theme.BubbleOutgoing
import com.newendian.retichat.ui.theme.RetichatTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
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
    val names = viewModel?.names?.collectAsState()?.value ?: NameBook.EMPTY
    val chatEntity = viewModel?.chat?.collectAsState()?.value
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val dayMarkers = rememberDayMarkerText()
    val isGroup = chatEntity?.isGroup == true
    val isPendingInvite = viewModel?.isPendingInvite?.collectAsState()?.value ?: false
    // REACTIONS-DESIGN.md §3 "Display": the addresses whose reactions are the user's.
    val reactionSelves = viewModel?.reactionSelves?.collectAsState()?.value ?: emptySet()
    // GROUPS-DISTRO-DESIGN.md §8.7, §13: a group whose own address is a distro D.
    val acting = viewModel?.groupActing?.collectAsState()?.value
    val distroGroup = acting?.distroGroup == true
    // Accept and decline can be signed and sent now: greyed, with no text, until the
    // start finishes (§8.7; James, 2026-10-11). The composer is not: a post waits as a DM does.
    val canAct = acting?.canAct == true
    // A former D (§8.7): a bar with its text and "Delete from this device" replaces the
    // composer, accept and decline. Never at start-up.
    val formerOwn = acting?.takeIf { it.former }?.own
    // The local-only delete (§8.7): no leave is sent; the screen closes.
    val deleteFromThisDevice: () -> Unit = {
        scope.launch {
            app.repository.deleteGroupFromThisDevice(chatId)
            onBack()
        }
    }

    // Pending attachment state
    var pendingAttachmentName by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentData by remember { mutableStateOf<ByteArray?>(null) }

    var showChatInfo by remember { mutableStateOf(false) }
    var showDeclineConfirm by remember { mutableStateOf(false) }
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

                val picked = cr.openInputStream(uri)?.use { it.readBytes() }
                if (picked != null && picked.isNotEmpty()) {
                    // A photo leaves without where it was taken (James,
                    // 2026-10-05): held as it will leave, so the preview shows
                    // what is sent, and one that cannot be made safe is not held.
                    val bytes = PhotoMetadata.withoutLocation(picked)
                    if (bytes == null) {
                        Toast.makeText(context, PhotoLocationNotRemoved.messageFor(filename), Toast.LENGTH_LONG).show()
                    } else {
                        pendingAttachmentName = filename
                        pendingAttachmentData = bytes
                    }
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

    // The top bar: a group's name, or the DM peer resolved live from its
    // contact (DISPLAY_NAMES.md §5.3), the same name the chat list shows.
    val peerHex = chatEntity?.memberHashes?.takeIf { !isGroup } ?: chatId.removePrefix("dm_")
    val displayTitle = when {
        chatEntity == null && !chatId.startsWith("dm_") -> ""
        isGroup -> chatEntity?.name.orEmpty()
        else -> names.contact(peerHex)
    }
    val contacts = viewModel?.contacts?.collectAsState()?.value ?: emptyList()
    val peerLocalName = if (isGroup) null else contacts.firstOrNull { it.destHashHex == peerHex }?.localName

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
                            val marker = dayMarkers.aboveNewestFirst(index, lazyItems.itemCount) {
                                lazyItems.peek(it)?.timestamp
                            }
                            WithDayMarker(marker) {
                                MessageRow(
                                    msg = msg,
                                    showSender = isGroup || !msg.isOutbound,
                                    senderLabel = ChannelLabel(names.contact(msg.senderHashHex), null),
                                    text = SystemText.render(msg.systemKind, msg.content, msg.senderHashHex, names),
                                    viewModel = viewModel,
                                    zone = dayMarkers.zone,
                                    // §7: no reaction row on a system line, a Group post with no post id, or an invite not yet accepted.
                                    reactable = !isPendingInvite && Reactions.canReact(isGroup, msg),
                                    reactionSelves = reactionSelves,
                                )
                            }
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

            // Input bar (or invite prompt for pending group invites), or a former D's bar
            if (formerOwn != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider()
                        FormerDistroBar(
                            own = formerOwn,
                            onDeleteFromThisDevice = deleteFromThisDevice,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            } else if (isPendingInvite) {
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
                                onClick = { showDeclineConfirm = true },
                                enabled = canAct,
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("Decline") }
                            Button(
                                onClick = { viewModel?.acceptInvite() },
                                enabled = canAct,
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
                                val sent = draft
                                val attachName = pendingAttachmentName
                                val attachData = pendingAttachmentData
                                // Cleared before the send, so a refusal can only put it back.
                                draft = ""
                                pendingAttachmentName = null
                                pendingAttachmentData = null
                                // §8.7 (James, 2026-10-11): a group post refused after Send (its
                                // distro went since the screen was drawn) says nothing: the text goes
                                // back in front of anything typed since, and the attachment unless
                                // another was picked, and the former-distro bar shows.
                                val keep: () -> Unit = {
                                    draft = sent + draft
                                    if (pendingAttachmentName == null) {
                                        pendingAttachmentName = attachName
                                        pendingAttachmentData = attachData
                                    }
                                }
                                if (attachName != null && attachData != null) {
                                    viewModel?.sendWithAttachment(sent, attachName, attachData, onRefused = keep)
                                } else {
                                    viewModel?.send(sent, onRefused = keep)
                                }
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

    // A decline is the user's leave, and final (James, 2026-10-01: "Once the
    // group is rejected/left, that person cannot rejoin").
    if (showDeclineConfirm) {
        AlertDialog(
            onDismissRequest = { showDeclineConfirm = false },
            // §13: in a D-group the decline is D's, on every device of it.
            title = { Text(if (distroGroup) DistroGroupTexts.DECLINE_TITLE else "Decline this group invite?") },
            text = { Text(if (distroGroup) DistroGroupTexts.DECLINE_TEXT else "You won't be able to join this group later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeclineConfirm = false
                        // The screen closes only when the decline was done.
                        viewModel?.declineInvite { onBack() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Decline") }
            },
            dismissButton = {
                TextButton(onClick = { showDeclineConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showChatInfo) {
        ChatInfoSheet(
            chatId = chatId,
            title = displayTitle,
            isGroup = isGroup,
            peerHashHex = chatEntity?.memberHashes?.takeIf { !isGroup },
            localName = peerLocalName,
            members = groupMembers,
            names = names,
            acting = acting,
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
            // The screen closes only when it was done: a group's leave is refused
            // while it cannot be signed or sent (GROUPS-DISTRO-DESIGN.md §8.7).
            onDelete = {
                scope.launch {
                    if (app.repository.deleteChat(chatId)) onBack()
                }
            },
            onLeave = {
                scope.launch {
                    if (app.repository.leaveGroupChat(chatId)) onBack()
                }
            },
            onDeleteFromThisDevice = deleteFromThisDevice,
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
    // §5.3 channel labels: the poster's channel name here, else its contact name.
    val namesFlow = remember { app.repository.nameBook() }
    val names by namesFlow.collectAsState(initial = NameBook.EMPTY)
    val channelNamesFlow = remember(channelId) {
        app.rfedChannelClient.sendersFlow(channelId)
            .map { senders -> senders.associate { it.senderHex to it.channelName } }
    }
    val channelNames by channelNamesFlow.collectAsState(initial = emptyMap())
    val canPullMoreMap by app.rfedChannelClient.canPullMore.collectAsState()
    val pullInFlight by app.rfedChannelClient.pullInFlight.collectAsState()
    val rfedLinkGeneration by app.rfedChannelClient.rfedLinkGeneration.collectAsState()
    val channelPullKey = channelId.lowercase()
    // Unknown (`null`) means "might be more" so the manual paging affordance
    // stays visible until the server explicitly reports `more_pending = false`.
    val canPullMore = canPullMoreMap[channelPullKey] != false
    val isPulling = channelPullKey in pullInFlight
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastAutoPullGeneration by remember(channelId) { mutableStateOf<Int?>(null) }

    var draft by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val dayMarkers = rememberDayMarkerText()

    // A sibling device's leave (RFed SPEC §17.12 rule 6) removes the channel while it is
    // open here: the screen closes, as the web closes it, and asks nothing. The user's
    // own leave closes it itself (below), so it is not closed twice.
    var channelSeen by remember(channelId) { mutableStateOf(false) }
    var leavingHere by remember(channelId) { mutableStateOf(false) }
    LaunchedEffect(channel) {
        if (channel != null) channelSeen = true
        else if (channelSeen && !leavingHere) onLeft()
    }

    DisposableEffect(channelId) {
        app.rfedChannelClient.retainRfedLinkMonitor()
        onDispose {
            app.rfedChannelClient.releaseRfedLinkMonitor()
        }
    }

    // Every return to the foreground runs the screen's entry again, as though
    // the channel were opened from the chat list (James, 2026-10-19): the
    // stream is armed, "Load earlier" shows again, and the pull goes as soon
    // as the rfed link is up, now or at its next ACTIVE edge. Until then a
    // resume pulled at once, link up or not, and a pull that failed on a link
    // still coming back waited for the next link.
    var entries by remember(channelId) { mutableStateOf(0) }

    LaunchedEffect(channel?.id, entries) {
        val ch = channel ?: return@LaunchedEffect
        app.rfedChannelClient.markChannelOpenedForStreaming(ch.id)
        app.rfedChannelClient.resetCanPullMore(ch.id)
    }

    // Auto-pull once per entry and once per rfed node link establishment while
    // the channel screen is open. Initial open also flows through this effect
    // because the current generation is observed immediately when the screen
    // starts collecting it. The pull runs on the screen's scope, so a key
    // change (a resume, a new link) never cancels one half-way.
    LaunchedEffect(channel?.id, rfedLinkGeneration, entries) {
        val ch = channel ?: return@LaunchedEffect
        if (ConnectionStateManager.rfedNodeLinkStatus() != RetichatBridge.AppLinkStatus.ACTIVE) {
            return@LaunchedEffect
        }
        val generation = rfedLinkGeneration
        if (lastAutoPullGeneration == generation) return@LaunchedEffect
        if (isPulling) return@LaunchedEffect
        lastAutoPullGeneration = generation
        scope.launch { runCatching { app.rfedChannelClient.pullDeferred(ch) } }
    }

    DisposableEffect(lifecycleOwner, channelId) {
        // Adding the observer delivers the current state's ON_RESUME at once:
        // that one is this entry, which the effects above already run.
        var entered = false
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (!entered) {
                entered = true
                return@LifecycleEventObserver
            }
            lastAutoPullGeneration = null
            entries++
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
                // Queued while the app was starting: the DM's pending clock (⏳) until it goes.
                ChannelMessageEntity.SEND_STATE_QUEUED -> RetichatBridge.MessageState.OUTBOUND
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
                val newestFirst = displayMessages.asReversed()
                itemsIndexed(items = newestFirst, key = { _, msg -> msg.id }) { index, msg ->
                    val marker = dayMarkers.aboveNewestFirst(index, newestFirst.size) {
                        newestFirst.getOrNull(it)?.timestamp
                    }
                    WithDayMarker(marker) {
                        MessageRow(
                            msg = msg,
                            showSender = !msg.isOutbound,
                            senderLabel = names.channelPost(msg.senderHashHex, channelNames[msg.senderHashHex.lowercase()]),
                            text = msg.content,
                            viewModel = null,
                            zone = dayMarkers.zone,
                        )
                    }
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
                            val text = OutgoingText.of(draft)
                            draft = ""
                            scope.launch {
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
                leavingHere = true
                scope.launch {
                    if (ch != null) app.rfedChannelClient.leaveChannel(ch.id)
                    onLeft()
                }
            },
        )
    }
}

/**
 * One bubble. [senderLabel] is the sender's resolved name (NameBook), with its
 * grey secondary text beside it on a channel post (§5.3: the channel name
 * under a local name, or the short hash under a channel name); [text] is the body,
 * with a system line's member name already resolved.
 *
 * A long press opens the message's actions (REACTIONS-DESIGN.md §7): the quick
 * reactions when [reactable], then Copy and Select text wherever there is text.
 * Links and attachments keep their own taps: a press on them is theirs. The
 * reactions given show after the time, in the checkmark's place; the popup
 * highlights the user's own (one of [reactionSelves]), and picking it again
 * removes it.
 */
@Composable
private fun MessageRow(
    msg: MessageEntity,
    showSender: Boolean,
    senderLabel: ChannelLabel,
    text: String,
    viewModel: ConversationViewModel?,
    zone: ZoneId,
    reactable: Boolean = false,
    reactionSelves: Set<String> = emptySet(),
) {
    val isOut = msg.isOutbound
    val alignment = if (isOut) Alignment.End else Alignment.Start
    val hasText = text.isNotBlank()
    val hasActions = hasText || reactable
    var actionsOpen by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

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
                .pointerInput(hasActions) {
                    if (hasActions) {
                        detectTapGestures(onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actionsOpen = true
                        })
                    }
                }
                .semantics {
                    if (hasActions) onLongClick(label = "Message actions") { actionsOpen = true; true }
                }
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 10.dp,
                    bottom = 10.dp,
                ),
        ) {
            Column {
                // Sender name for incoming messages (a system line names its
                // member in the text instead)
                if (!isOut && showSender && msg.systemKind == null) {
                    val nameColor = senderColor(msg.senderHashHex)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = senderLabel.name,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = nameColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // §5.3 secondary text: the poster's channel name
                        // beside a local name, or the short hash beside a
                        // channel name. A channel name can be long, so it is
                        // capped and ellipsized rather than crowding out the
                        // main label.
                        senderLabel.secondary?.let { secondary ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = secondary,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                        }
                    }
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
                if (text.isNotBlank()) {
                    Text(
                        text = linkifyText(text, contentColor),
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

                // The footer on the sender's side: at the right in a sent
                // bubble, at the left in a received one, as on iOS and the web
                // (James, 2026-10-17).
                Row(
                    modifier = Modifier.align(alignment),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatMessageTime(msg.timestamp, zone),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.6f),
                    )

                    // The reactions take the checkmark's place (REACTIONS-DESIGN.md
                    // §7; James, 2026-10-17): each one given, once, no count, no
                    // chip. Waiting, sending and failed keep their icon beside them.
                    val reactions = remember(msg.reactionsJson, reactionSelves) { Reactions.chips(msg.reactionsJson, reactionSelves) }
                    val checkmark = msg.state == RetichatBridge.MessageState.SENT || msg.state == RetichatBridge.MessageState.DELIVERED
                    if (isOut && (reactions.isEmpty() || !checkmark)) {
                        Spacer(Modifier.width(4.dp))
                        val isFailed = msg.state == RetichatBridge.MessageState.FAILED
                        Text(
                            text = stateIcon(msg.state),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isFailed) Color(0xFFFF5252)
                                else Color.White.copy(alpha = 0.7f),
                        )
                    }
                    if (reactions.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = reactions.joinToString("") { it.emoji },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics {
                                contentDescription = reactions.joinToString(", ") { "${it.emoji} ${it.count}" + if (it.mine) ", including yours" else "" }
                            },
                        )
                    }
                }
            }
            // Its actions, anchored to the bubble (a popup: it takes no room in it).
            MessageActions(
                expanded = actionsOpen,
                onDismiss = { actionsOpen = false },
                text = text,
                mine = if (reactable) Reactions.mine(msg.reactionsJson, reactionSelves) else null,
                onReact = viewModel?.takeIf { reactable }?.let { vm -> { emoji: String -> vm.react(msg, emoji) } },
                onSelect = { selecting = true },
            )
        }
    }

    if (selecting) {
        SelectTextDialog(text, onDismiss = { selecting = false })
    }
}

/**
 * A message's actions (REACTIONS-DESIGN.md §7), opened by a long press on its
 * bubble: the quick reactions when [onReact] is given (the one in [mine]
 * highlighted; picking it again removes it), then Copy and Select text when the
 * message has [text].
 */
@Composable
private fun MessageActions(
    expanded: Boolean,
    onDismiss: () -> Unit,
    text: String,
    mine: String?,
    onReact: ((String) -> Unit)?,
    onSelect: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val hasText = text.isNotBlank()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (onReact != null) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Reactions.QUICK.forEach { emoji ->
                    val picked = emoji == mine
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (picked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable(onClickLabel = if (picked) "Remove $emoji" else "React $emoji") {
                                onDismiss()
                                onReact(emoji)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }
            if (hasText) HorizontalDivider()
        }
        if (hasText) {
            DropdownMenuItem(
                text = { Text("Copy") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = { Text("Select text") },
                leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onSelect()
                },
            )
        }
    }
}

/** The text of a message, selectable (§7 "Select text"): the system's handles copy any part of it. */
@Composable
private fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Select text") },
        text = {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
    )
}

// ---- Helpers ----

private fun senderColor(hash: String): Color {
    val idx = hash.hashCode().and(0x7FFFFFFF) % senderColors.size
    return senderColors[idx]
}

private val msgTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** A bubble's time, in [zone]: the date markers' zone, so a time never sits under another day. */
private fun formatMessageTime(millis: Long, zone: ZoneId): String =
    msgTimeFormat.apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(millis))

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
    return ext in listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
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

// ---- Previews ----

/**
 * Date markers in a conversation on 30 September 2026 in London, top to
 * bottom as the list shows them: 31 December 2025 with its year, 1 January
 * and 27 September without, then "Yesterday" and "Today".
 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DayMarkersPreview() {
    val zone = ZoneId.of("Europe/London")
    val today = LocalDate.of(2026, 9, 30)
    fun at(date: LocalDate, hour: Int, minute: Int) =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    val alice = "aabbccdd"
    val messages = listOf(
        MessageEntity("p1", "preview", alice, "Happy new year!", at(LocalDate.of(2025, 12, 31), 23, 58), false),
        MessageEntity("p2", "preview", "me", "And to you", at(LocalDate.of(2026, 1, 1), 0, 2), true, RetichatBridge.MessageState.DELIVERED),
        MessageEntity("p3", "preview", alice, "The roof relay is up", at(today.minusDays(3), 18, 20), false),
        MessageEntity("p4", "preview", "me", "Seeing it from here", at(today.minusDays(1), 21, 10), true, RetichatBridge.MessageState.DELIVERED),
        MessageEntity("p5", "preview", alice, "Morning!", at(today, 8, 5), false),
        MessageEntity("p6", "preview", "me", "Morning", at(today, 8, 7), true, RetichatBridge.MessageState.SENT),
    )
    val labels = IcuDayLabels(Locale.UK, zone, today)
    val dayMarkers = DayMarkerText(DayMarkers(today, zone, labels::sameYear), labels)
    RetichatTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                messages.forEachIndexed { index, msg ->
                    WithDayMarker(dayMarkers.above(msg.timestamp, messages.getOrNull(index - 1)?.timestamp)) {
                        MessageRow(
                            msg = msg,
                            showSender = !msg.isOutbound,
                            senderLabel = ChannelLabel("Alice", null),
                            text = msg.content,
                            viewModel = null,
                            zone = zone,
                        )
                    }
                }
            }
        }
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
