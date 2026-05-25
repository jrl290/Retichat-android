package com.newendian.retichat.ui.channels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.service.UserPreferences
import kotlinx.coroutines.launch

/**
 * Bottom-sheet shown from the Conversation screen when the user is viewing
 * an RFed channel and taps the info button. Mirrors iOS `ChannelInfoSheet`:
 *  - "#name" header + raw 32-hex channel id
 *  - "Push All Messages" toggle (registers/deregisters rfed.notify)
 *  - "Notifications" toggle (gated on push)
 *  - "Leave Channel" destructive action
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelInfoSheet(
    channelName: String,
    channelId: String,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as RetichatApp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var pushEnabled by remember(channelId) {
        mutableStateOf(UserPreferences.isChannelPushEnabled(context, channelId))
    }
    var notificationsEnabled by remember(channelId) {
        mutableStateOf(UserPreferences.isChannelNotificationsEnabled(context, channelId))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Channel Info",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Done") }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "#${channelName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    channelId,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Push All Messages",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (pushEnabled)
                                    "Device is woken up for every new message"
                                else
                                    "No push wakeups for this channel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = pushEnabled,
                            onCheckedChange = { enabled ->
                                pushEnabled = enabled
                                scope.launch {
                                    if (enabled) {
                                        app.rfedChannelClient.enableChannelPush(channelId)
                                    } else {
                                        app.rfedChannelClient.disableChannelPush(channelId)
                                    }
                                }
                            },
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Notifications",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (pushEnabled)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                when {
                                    pushEnabled && notificationsEnabled ->
                                        "You'll be notified of new messages"
                                    pushEnabled ->
                                        "Notifications are off"
                                    else ->
                                        "Enable Push All Messages first"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = notificationsEnabled,
                            enabled = pushEnabled,
                            onCheckedChange = { enabled ->
                                notificationsEnabled = enabled
                                UserPreferences.setChannelNotificationsEnabled(
                                    context, channelId, enabled,
                                )
                            },
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = onLeave,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Leave Channel", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
