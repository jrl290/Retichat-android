package com.retichat.app.ui.chatlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retichat.app.ServiceState
import com.retichat.app.data.db.dao.ChatPreview
import com.retichat.app.ui.components.AvatarCircle
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatListScreen(
    onChatClick: (String) -> Unit,
    onNewChat: () -> Unit,
    onShowQr: () -> Unit,
    onSettings: () -> Unit = {},
    serviceState: ServiceState = ServiceState(),
    viewModel: ChatListViewModel? = null,
) {
    val chats = viewModel?.chats?.collectAsState()?.value ?: dummyPreviews
    var searchQuery by remember { mutableStateOf("") }

    val filtered = if (searchQuery.isBlank()) chats
    else chats.filter { it.name.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChat,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New chat")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // --- Search bar with inline QR + Settings ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Search chats",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    ),
                )

                IconButton(onClick = onShowQr) {
                    Icon(
                        Icons.Default.QrCode2,
                        contentDescription = "My QR code",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- Status bar ---
            if (serviceState.isInitialized || serviceState.error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dot = if (serviceState.isInitialized) "\u2022 " else "\u25CB "
                    val color = if (serviceState.isInitialized)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                    Text(
                        text = dot + (if (serviceState.isInitialized) serviceState.identityHashHex.take(16) else "Error"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = color.copy(alpha = 0.7f),
                    )
                }
            }

            // --- Chat list ---
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (chats.isEmpty()) "No conversations yet\nTap + to start one"
                        else "No results",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                ) {
                    items(filtered, key = { it.id }) { chat ->
                        SwipeToArchiveRow(
                            chat = chat,
                            onClick = { onChatClick(chat.id) },
                            onArchive = { viewModel?.archiveChat(chat.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToArchiveRow(
    chat: ChatPreview,
    onClick: () -> Unit,
    onArchive: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onArchive()
                true
            } else false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surface
                },
                label = "bg",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Archive,
                    contentDescription = "Archive",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ChatRow(chat = chat, onClick = onClick)
        }
    }
}

@Composable
private fun ChatRow(chat: ChatPreview, onClick: () -> Unit) {
    val hasUnread = chat.unreadCount > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(
            name = chat.name,
            size = 54.dp,
            backgroundColor = if (chat.isGroup)
                MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.primaryContainer,
        )
        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                chat.lastTimestamp?.let { ts ->
                    Text(
                        text = formatTime(ts),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasUnread) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.lastContent ?: "",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (hasUnread) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = if (hasUnread) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasUnread) {
                    Spacer(Modifier.width(10.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            chat.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000        -> "now"
        diff < 86_400_000    -> timeFormat.format(Date(millis))
        diff < 604_800_000   -> {
            val days = (diff / 86_400_000).toInt()
            if (days == 1) "Yesterday" else "$days d"
        }
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}

// ---- Dummy data for preview ----

private val dummyPreviews = listOf(
    ChatPreview("dm_alice", false, "Alice", "aabbccdd", null,
        "Hey! Just tested Retichat over LoRa \uD83D\uDE80",
        System.currentTimeMillis() - 300_000, 2),
    ChatPreview("dm_bob", false, "Bob", "11223344", null,
        "The mesh is growing fast",
        System.currentTimeMillis() - 3_600_000, 0),
    ChatPreview("grp_meshnet", true, "Meshnet Crew", "aabbccdd,11223344,55667788", "cafebabe",
        "Bob: Anyone near the relay node?",
        System.currentTimeMillis() - 7_200_000, 5),
    ChatPreview("dm_carol", false, "Carol", "55667788", null,
        "Thanks for the firmware update",
        System.currentTimeMillis() - 86_400_000, 0),
)
