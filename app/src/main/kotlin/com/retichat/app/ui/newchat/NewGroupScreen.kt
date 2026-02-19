package com.retichat.app.ui.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retichat.app.data.model.Contact
import com.retichat.app.ui.components.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(
    onGroupCreated: (String) -> Unit,
    onBack: () -> Unit,
    contacts: List<Contact> = emptyList(),
) {
    var groupName by remember { mutableStateOf("") }
    val selectedMembers = remember { mutableStateListOf<Contact>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            // TODO: call repository.createGroupChat(...)
                        },
                        enabled = groupName.isNotBlank() && selectedMembers.size >= 1,
                    ) {
                        Text("Create")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Select members (${selectedMembers.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn {
                items(contacts) { contact ->
                    val isSelected = selectedMembers.any {
                        it.destHash.contentEquals(contact.destHash)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSelected) selectedMembers.removeAll {
                                    it.destHash.contentEquals(contact.destHash)
                                }
                                else selectedMembers.add(contact)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                if (it) selectedMembers.add(contact)
                                else selectedMembers.removeAll {
                                    it.destHash.contentEquals(contact.destHash)
                                }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        AvatarCircle(name = contact.displayName, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
