package com.newendian.retichat.ui.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.newendian.retichat.data.model.Contact
import com.newendian.retichat.data.repository.GroupCreation
import com.newendian.retichat.data.repository.GroupSelves
import com.newendian.retichat.ui.components.AvatarCircle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(
    onGroupCreated: (String) -> Unit,
    onBack: () -> Unit,
    contacts: List<Contact> = emptyList(),
    createGroup: suspend (name: String, members: List<ByteArray>) -> GroupCreation = { _, _ -> GroupCreation.Refused(GroupSelves.Signer.NOT_READY) },
    // Whether the start has finished, so a group can be signed and sent: Create is
    // greyed, with no text, until it has (GROUPS-DISTRO-DESIGN.md §8.7; James, 2026-10-11).
    started: Boolean = false,
) {
    var groupName by remember { mutableStateOf("") }
    var userEditedName by remember { mutableStateOf(false) }
    val selectedMembers = remember { mutableStateListOf<Contact>() }
    val scope = rememberCoroutineScope()
    var isCreating by remember { mutableStateOf(false) }

    // Auto-generate group name from selected members' first names
    val defaultName = remember(selectedMembers.toList()) {
        selectedMembers.joinToString(", ") { it.displayName.split(" ").first() }
    }
    if (!userEditedName) {
        groupName = defaultName
    }

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
                            isCreating = true
                            scope.launch {
                                when (val created = createGroup(groupName, selectedMembers.map { it.destHash })) {
                                    is GroupCreation.Created -> onGroupCreated(created.chatId)
                                    // Nothing was written; nothing is said (James, 2026-10-11): the
                                    // name and the members stay for the next try.
                                    is GroupCreation.Refused -> Unit
                                }
                                isCreating = false
                            }
                        },
                        enabled = started && groupName.isNotBlank() && selectedMembers.size >= 1 && !isCreating,
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Create")
                        }
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
                onValueChange = {
                    groupName = it
                    userEditedName = it.isNotEmpty()
                },
                label = { Text("Group name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
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
