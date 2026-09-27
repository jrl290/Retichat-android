package com.newendian.retichat.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.service.RfedChannelClient
import com.newendian.retichat.service.UserPreferences
import kotlinx.coroutines.launch

/**
 * Join (or create) an RFed channel. Mirrors iOS `NewChannelForm`
 * (in `NewConversationView.swift`):
 *   - Visibility segmented picker: Public / Private
 *   - One channel-name field with a monospace root label (`public.` or
 *     `<root>.`) and an editable name (filtered to a-z 0-9 . -)
 *   - Private mode adds an editable Root field (default: 16 random hex,
 *     "Regenerate prefix" makes a new one). Pasting "root.name" into the
 *     name field fills both. Rules live in [ChannelNameForm].
 *   - rfed node hash is taken from Settings — there is no field for it
 *   - Toolbar: Cancel (leading), Start (trailing)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinChannelScreen(
    onJoined: (channelId: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as RetichatApp
    val scope = rememberCoroutineScope()

    val effectiveNode = remember { UserPreferences.getEffectiveRfedNodeIdentityHash(context).trim().lowercase() }
    val rfedAddressConfigured = effectiveNode.length >= 32

    var privacy by remember { mutableStateOf(Privacy.Public) }
    // The name field keeps its selection so a paste over the whole name is
    // recognised as one (see ChannelNameForm.onNameInput).
    var nameField by remember { mutableStateOf(TextFieldValue("")) }
    val subdomain = nameField.text
    var privatePrefix by remember { mutableStateOf(ChannelNameForm.randomPrivateRoot()) }
    var isJoining by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val prefixLabel = if (privacy == Privacy.Public) "public." else "$privatePrefix."
    val isPrivate = privacy == Privacy.Private
    val rootProblem = if (isPrivate) ChannelNameForm.privateRootProblem(privatePrefix) else null
    val fullChannelName: String =
        ChannelNameForm.fullChannelName(isPrivate, privatePrefix, subdomain) ?: ""
    val canStart = fullChannelName.isNotEmpty() && rfedAddressConfigured && !isJoining

    fun start() {
        if (!canStart) return
        errorMessage = null
        isJoining = true
        scope.launch {
            val result = app.rfedChannelClient.joinChannel(fullChannelName, effectiveNode)
            when (result) {
                is RfedChannelClient.JoinResult.Joined -> onJoined(result.channel.id)
                is RfedChannelClient.JoinResult.Failed -> {
                    errorMessage = result.reason
                    isJoining = false
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("New Channel") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Cancel", color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    TextButton(onClick = ::start, enabled = canStart) {
                        Text(
                            "Start",
                            color = if (canStart) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!rfedAddressConfigured) {
                Text(
                    "Joining a channel requires an RFed node address. Set one in Settings first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // --- Visibility picker ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Visibility",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    Privacy.entries.forEachIndexed { idx, p ->
                        SegmentedButton(
                            selected = privacy == p,
                            onClick = { privacy = p },
                            shape = SegmentedButtonDefaults.itemShape(idx, Privacy.entries.size),
                        ) { Text(p.label) }
                    }
                }
                Text(
                    if (privacy == Privacy.Public)
                        "Anyone who knows the name can join."
                    else
                        "Only people you share the full name with can join.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- Private root field ---
            if (privacy == Privacy.Private) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Root",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (privatePrefix.isEmpty()) {
                                Text(
                                    "root",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            BasicTextField(
                                value = privatePrefix,
                                onValueChange = { v -> privatePrefix = ChannelNameForm.filterRoot(v) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    keyboardType = KeyboardType.Ascii,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    when (rootProblem) {
                        ChannelNameForm.RootProblem.Empty -> Text(
                            "Enter a root, or regenerate one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        ChannelNameForm.RootProblem.PublicReserved -> Text(
                            "\"public\" is the root of public channels. Choose Public above, or use another root.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        null -> {}
                    }
                    TextButton(
                        onClick = { privatePrefix = ChannelNameForm.randomPrivateRoot() },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Text(
                            "Regenerate prefix",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // --- Channel name field ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Channel name",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        prefixLabel,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (subdomain.isEmpty()) {
                            Text(
                                "general",
                                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                        BasicTextField(
                            value = nameField,
                            onValueChange = { v ->
                                val fields = ChannelNameForm.onNameInput(
                                    previousName = nameField.text,
                                    input = v.text,
                                    isPrivate = privacy == Privacy.Private,
                                    root = privatePrefix,
                                    selectionStart = nameField.selection.min,
                                    selectionEnd = nameField.selection.max,
                                )
                                privatePrefix = fields.root
                                nameField = if (fields.name == v.text) {
                                    v
                                } else {
                                    // Filtered or split: move the cursor back
                                    // by the characters that left the field.
                                    val cursor = v.selection.max - (v.text.length - fields.name.length)
                                    TextFieldValue(fields.name, TextRange(cursor.coerceIn(0, fields.name.length)))
                                }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Ascii,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (fullChannelName.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Default.Tag,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            fullChannelName,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // --- Private sharing hint ---
            if (privacy == Privacy.Private) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFFFA726),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val example = if (fullChannelName.isEmpty()) "${privatePrefix.ifEmpty { "root" }}.yourname" else fullChannelName
                        Text(
                            "Share the full name \"$example\" with others so they can join.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "The random root keeps the name from being guessed. To join a private channel someone shared, paste its full name into Channel name, or type its root above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            errorMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (isJoining) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Joining channel…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private enum class Privacy(val label: String) {
    Public("Public"),
    Private("Private"),
}
