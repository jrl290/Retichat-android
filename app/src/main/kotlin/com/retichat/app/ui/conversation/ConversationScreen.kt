package com.retichat.app.ui.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.retichat.app.bridge.RetichatBridge
import com.retichat.app.data.db.entity.MessageEntity
import com.retichat.app.ui.components.ChatBubble
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    chatId: String,
    onBack: () -> Unit,
    viewModel: ConversationViewModel? = null, // injected in production
) {
    val messages = viewModel?.messages?.collectAsState()?.value ?: emptyList()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chatId.removePrefix("dm_").take(12)) },
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
                .padding(padding),
        ) {
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageRow(msg)
                }
            }

            // Input bar
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )

                    Spacer(Modifier.width(4.dp))

                    IconButton(
                        onClick = {
                            viewModel?.send(draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(msg: MessageEntity) {
    val alignment = if (msg.isOutbound) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        ChatBubble(isOutgoing = msg.isOutbound) {
            Column {
                Text(
                    text = msg.content,
                    color = if (msg.isOutbound) Color.White
                    else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatMessageTime(msg.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.isOutbound) Color.White.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (msg.isOutbound) {
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

private val msgTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatMessageTime(millis: Long): String =
    msgTimeFormat.format(Date(millis))

private fun stateIcon(state: Int): String = when (state) {
    RetichatBridge.MessageState.GENERATING -> "\u23F3"  // hourglass
    RetichatBridge.MessageState.SENDING    -> "\u2197"  // arrow
    RetichatBridge.MessageState.SENT       -> "\u2713"  // single check
    RetichatBridge.MessageState.DELIVERED  -> "\u2713\u2713" // double check
    RetichatBridge.MessageState.FAILED     -> "\u2717"  // X
    else -> ""
}
