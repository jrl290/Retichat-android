package com.retichat.app.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.retichat.app.bridge.RetichatBridge
import com.retichat.app.data.db.entity.MessageEntity
import com.retichat.app.ui.theme.BubbleIncoming
import com.retichat.app.ui.theme.BubbleOutgoing
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chatId: String,
    onBack: () -> Unit,
    viewModel: ConversationViewModel? = null,
) {
    val messages = viewModel?.messages?.collectAsState()?.value ?: dummyMessages(chatId)
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isGroup = chatId.startsWith("grp_")

    // Scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chatName(chatId), style = MaterialTheme.typography.titleMedium)
                        if (isGroup) {
                            Text(
                                "3 members",
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageRow(msg, showSender = isGroup || !msg.isOutbound)
                }
            }

            // Input bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { /* TODO: file picker */ }) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = "Attach",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        shape = MaterialTheme.shapes.large,
                        maxLines = 4,
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
                            viewModel?.send(draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank(),
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
}

@Composable
private fun MessageRow(msg: MessageEntity, showSender: Boolean) {
    val isOut = msg.isOutbound
    val alignment = if (isOut) Alignment.End else Alignment.Start

    // Bubble shape: rounded with a small tail on the sender's side
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isOut) 18.dp else 4.dp,
        bottomEnd = if (isOut) 4.dp else 18.dp,
    )

    val bgColor = if (isOut) BubbleOutgoing else BubbleIncoming
    val contentColor = if (isOut) Color.White else MaterialTheme.colorScheme.onSurface

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
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                // Sender name for incoming messages
                if (!isOut && showSender) {
                    val senderName = senderName(msg.senderHashHex)
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

                Text(
                    text = msg.content,
                    color = contentColor,
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatMessageTime(msg.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOut) Color.White.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )

                    if (isOut) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stateIcon(msg.state),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

// ---- Helpers ----

private fun senderName(hash: String): String = when (hash) {
    "aabbccdd" -> "Alice"
    "11223344" -> "Bob"
    "55667788" -> "Carol"
    else -> hash.take(8)
}

private fun senderColor(hash: String): Color {
    val idx = hash.hashCode().and(0x7FFFFFFF) % senderColors.size
    return senderColors[idx]
}

private val msgTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatMessageTime(millis: Long): String =
    msgTimeFormat.format(Date(millis))

private fun stateIcon(state: Int): String = when (state) {
    RetichatBridge.MessageState.GENERATING -> "\u23F3"
    RetichatBridge.MessageState.SENDING    -> "\u2197"
    RetichatBridge.MessageState.SENT       -> "\u2713"
    RetichatBridge.MessageState.DELIVERED  -> "\u2713\u2713"
    RetichatBridge.MessageState.FAILED     -> "\u2717"
    else -> ""
}

// ---- Dummy data for preview ----

private fun chatName(chatId: String): String = when {
    chatId.contains("alice")   -> "Alice"
    chatId.contains("bob")     -> "Bob"
    chatId.contains("meshnet") -> "Meshnet Crew"
    chatId.contains("carol")   -> "Carol"
    else -> chatId.removePrefix("dm_").take(12)
}

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
