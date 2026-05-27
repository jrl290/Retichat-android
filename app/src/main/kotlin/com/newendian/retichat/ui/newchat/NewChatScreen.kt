package com.newendian.retichat.ui.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newendian.retichat.IdentityShareFormat
import com.newendian.retichat.data.model.Contact
import com.newendian.retichat.data.model.toHex
import com.newendian.retichat.ui.components.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    onChatCreated: (String) -> Unit,
    onNewGroup: () -> Unit,
    onScanQr: () -> Unit,
    onBack: () -> Unit,
    onJoinChannel: () -> Unit = {},
    onDestHashChat: (String) -> Unit = {},
    contacts: List<Contact> = emptyList(),
    onSelectContact: (Contact) -> Unit = {},
) {
    var destHashInput by remember { mutableStateOf("") }
    var showDestInput by remember { mutableStateOf(false) }
    val destHashClean = IdentityShareFormat.normalizeDestinationHash(destHashInput)
    val isValidHash = destHashClean.length == 32 && destHashClean.all { it in '0'..'9' || it in 'a'..'f' }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ---- Actions ----

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDestInput = !showDestInput }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = "LXMF destination",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text("Enter LXMF Destination", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (showDestInput) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = destHashInput,
                            onValueChange = { destHashInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Destination hash (32 hex chars)") },
                            placeholder = {
                                Text(
                                    "e.g. a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            isError = destHashInput.isNotEmpty() && !isValidHash,
                            supportingText = if (destHashInput.isNotEmpty() && !isValidHash) {
                                { Text("Must be exactly 32 hex characters") }
                            } else null,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onDestHashChat(destHashClean) },
                            enabled = isValidHash,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Start Chat")
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNewGroup)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Group,
                        contentDescription = "New group",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text("New Group", style = MaterialTheme.typography.titleMedium)
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onJoinChannel)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "#",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Text("Join Channel", style = MaterialTheme.typography.titleMedium)
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onScanQr)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "Scan QR",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text("Scan QR Code", style = MaterialTheme.typography.titleMedium)
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Contacts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (contacts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No contacts yet.\nScan a QR code to add one.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            items(contacts) { contact ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectContact(contact) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarCircle(name = contact.displayName, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = contact.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = contact.destHash.toHex().take(16) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
