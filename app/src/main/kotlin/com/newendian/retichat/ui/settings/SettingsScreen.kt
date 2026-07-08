package com.newendian.retichat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.newendian.retichat.ServiceState
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.db.entity.InterfaceConfigEntity
import com.newendian.retichat.service.ConnectionStateManager
import com.newendian.retichat.service.DefaultEndpointManager
import com.newendian.retichat.service.UserPreferences
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val interfaces by viewModel.interfaces.collectAsState()
    val state by viewModel.serviceState.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingInterface by remember { mutableStateOf<InterfaceConfigEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            // ---- Section: Service Status ----
            item {
                ServiceStatusCard(state = state, onRestart = { viewModel.restartService() })
            }

            // ---- Section: Profile (display name + channel display name) ----
            item {
                ProfileCard()
            }

            // ---- Section: Privacy ----
            item {
                PrivacyCard()
            }

            // ---- Section: RFed node + LXMF Propagation ----
            item {
                RfedConfigCard()
            }

            // ---- Section: Interfaces ----
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Interfaces",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalIconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add interface")
                    }
                }
            }

            // Default TCP endpoint (always shown)
            item {
                DefaultTcpCard()
            }

            items(interfaces, key = { it.id }) { iface ->
                InterfaceRow(
                    iface = iface,
                    onToggle = { viewModel.toggleEnabled(iface.id, it) },
                    onEdit = { editingInterface = iface },
                    onDelete = { viewModel.deleteInterface(iface.id) },
                )
            }

            // ---- Section: About ----
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    "About",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Retichat", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Powered by Reticulum + LXMF (Rust)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ---- Add dialog ----
    if (showAddDialog) {
        InterfaceEditorDialog(
            existing = null,
            onDismiss = { showAddDialog = false },
            onSave = { entity ->
                viewModel.saveInterface(entity)
                showAddDialog = false
            },
        )
    }

    // ---- Edit dialog ----
    editingInterface?.let { existing ->
        InterfaceEditorDialog(
            existing = existing,
            onDismiss = { editingInterface = null },
            onSave = { entity ->
                viewModel.saveInterface(entity)
                editingInterface = null
            },
        )
    }
}

// ---- Interface row ----

@Composable
private fun InterfaceRow(
    iface: InterfaceConfigEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val spec = InterfaceTypes.forType(iface.type)
    val typeLabel = spec?.label ?: iface.type
    val summary = buildSummary(iface)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = if (iface.enabled)
                MaterialTheme.colorScheme.surfaceContainerHigh
            else
                MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Toggle
            Switch(
                checked = iface.enabled,
                onCheckedChange = onToggle,
            )
            Spacer(Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = iface.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (summary.isNotEmpty()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }

            // Edit
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Delete
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ---- Add / Edit dialog ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterfaceEditorDialog(
    existing: InterfaceConfigEntity?,
    onDismiss: () -> Unit,
    onSave: (InterfaceConfigEntity) -> Unit,
) {
    val isEdit = existing != null

    // Selected type
    var selectedTypeIndex by remember {
        mutableIntStateOf(
            if (existing != null) InterfaceTypes.ALL.indexOfFirst { it.type == existing.type }.coerceAtLeast(0)
            else 0
        )
    }
    val spec = InterfaceTypes.ALL[selectedTypeIndex]

    // Name
    var name by remember { mutableStateOf(existing?.name ?: "") }

    // Field values
    val fieldValues = remember {
        val initial = mutableMapOf<String, String>()
        val json = try { JSONObject(existing?.configJson ?: "{}") } catch (_: Exception) { JSONObject() }
        spec.fields.forEach { f ->
            initial[f.key] = json.optString(f.key, f.default)
        }
        mutableStateMapOf(*initial.toList().toTypedArray())
    }

    // When type changes, reset field values to defaults
    LaunchedEffect(selectedTypeIndex) {
        if (!isEdit) {
            fieldValues.clear()
            spec.fields.forEach { f -> fieldValues[f.key] = f.default }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit Interface" else "Add Interface") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Type selector (only when adding)
                if (!isEdit) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value = spec.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            shape = MaterialTheme.shapes.medium,
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            InterfaceTypes.ALL.forEachIndexed { idx, typeSpec ->
                                DropdownMenuItem(
                                    text = { Text(typeSpec.label) },
                                    onClick = {
                                        selectedTypeIndex = idx
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Interface Name") },
                    placeholder = { Text("e.g. Home Server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )

                // Dynamic fields for selected type
                spec.fields.forEach { field ->
                    OutlinedTextField(
                        value = fieldValues[field.key] ?: "",
                        onValueChange = { fieldValues[field.key] = it },
                        label = { Text(field.label) },
                        placeholder = { Text(field.hint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = if (field.isNumber)
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        else KeyboardOptions.Default,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val json = JSONObject()
                    fieldValues.forEach { (k, v) -> json.put(k, v) }
                    onSave(
                        InterfaceConfigEntity(
                            id = existing?.id ?: 0,
                            name = name.ifBlank { spec.label },
                            type = spec.type,
                            enabled = existing?.enabled ?: true,
                            configJson = json.toString(),
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        )
                    )
                },
                enabled = name.isNotBlank() || !isEdit,
            ) {
                Text(if (isEdit) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ---- Display name card ----

@Composable
private fun ProfileCard() {
    val context = LocalContext.current
    var displayName by remember {
        mutableStateOf(UserPreferences.getDisplayName(context))
    }
    var channelDisplayName by remember {
        mutableStateOf(UserPreferences.getChannelDisplayName(context))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Profile",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(12.dp))

            Text(
                "Display Name",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    UserPreferences.setDisplayName(context, it)
                },
                placeholder = { Text("Your name in DMs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Channel Display Name",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = channelDisplayName,
                onValueChange = {
                    channelDisplayName = it
                    UserPreferences.setChannelDisplayName(context, it)
                },
                placeholder = {
                    Text(if (displayName.isEmpty()) "Same as Display Name" else displayName)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Shown to others in channels. If blank, uses your Display Name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PrivacyCard() {
    val context = LocalContext.current
    var filterStrangers by remember {
        mutableStateOf(UserPreferences.isFilterStrangersEnabled(context))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Privacy",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Privacy filter",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Only accept messages from contacts you have explicitly added",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = filterStrangers,
                    onCheckedChange = {
                        filterStrangers = it
						(context.applicationContext as? com.newendian.retichat.RetichatApp)
							?.repository
							?.setCoreFilterStrangers(it)
                    },
                )
            }
        }
    }
}

// ---- Push delivery info card (replaces former PersistentConnectionCard) ----

@Composable
private fun PushDeliveryInfoCard() {
    val context = LocalContext.current
    val tokenSet = remember { UserPreferences.getFcmDeviceToken(context).isNotEmpty() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Background delivery",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (tokenSet) "Push registered \u2014 messages wake the app on arrival."
                else "Push not yet registered. Open the app while online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Retichat now uses Firebase Cloud Messaging instead of a " +
                    "persistent foreground service. The app sleeps when not " +
                    "in use and wakes briefly when the rfed bridge has new " +
                    "traffic for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

// ---- Default TCP card ----

@Composable
private fun DefaultTcpCard() {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(UserPreferences.isDefaultTcpEnabled(context))
    }
    val endpoints = remember { DefaultEndpointManager.fallbackEndpoints() }
    val endpointText = remember(endpoints) {
        endpoints.joinToString("\n") { "${it.first}:${it.second}" }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surfaceContainerHigh
            else
                MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = enabled,
                onCheckedChange = { newValue ->
                    enabled = newValue
                    UserPreferences.setDefaultTcpEnabled(context, newValue)
                },
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = DefaultEndpointManager.DEFAULT_NAME,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = "TCP Client",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = endpointText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Text(
                    text = "Used only when no other interfaces are configured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ---- Drop Announces card ----

@Composable
private fun DropAnnouncesCard() {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(UserPreferences.isDropAnnouncesEnabled(context))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surfaceContainerHigh
            else
                MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Drop Announces",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = if (enabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { newValue ->
                        enabled = newValue
                        UserPreferences.setDropAnnouncesEnabled(context, newValue)
                        com.newendian.retichat.bridge.RetichatBridge.setDropAnnounces(newValue)
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Silently discard inbound announces from the network. " +
                    "Saves battery and bandwidth on busy networks. " +
                    "Path responses for your own messages are always kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

// ---- Service status card ----

@Composable
private fun ServiceStatusCard(state: ServiceState, onRestart: () -> Unit) {
    val statusColor = when {
        state.error != null -> MaterialTheme.colorScheme.error
        state.isInitialized -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val statusText = when {
        state.error != null -> "Error"
        state.isInitialized -> "Connected"
        else -> "Not started"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onRestart) {
                    Text("Restart")
                }
            }

            if (state.isInitialized) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "LXMF Address",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = state.identityHashHex.ifEmpty { "—" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${state.interfaceCount} interface(s) active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---- RFed config card ----

@Composable
private fun RfedConfigCard() {
    val context = LocalContext.current
    var nodeHash by remember {
        mutableStateOf(UserPreferences.getRfedNodeIdentityHash(context))
    }
    var lxmfPropOverride by remember {
        mutableStateOf(UserPreferences.getRfedLxmfPropOverride(context))
    }

    DisposableEffect(Unit) {
        ConnectionStateManager.retainRfedNodeStatusMonitor()
        onDispose { ConnectionStateManager.releaseRfedNodeStatusMonitor() }
    }

    fun isHex32(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RFed Node",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.width(8.dp))
                AppLinkStatusDot(
                    status = ConnectionStateManager.rfedNodeLinkStatusFlow.collectAsState().value,
                )
            }
            Spacer(Modifier.height(12.dp))

            Text(
                "Node Identity Hash",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = nodeHash,
                onValueChange = { nodeHash = it.trim().lowercase() },
                placeholder = { Text("32-char hex") },
                singleLine = true,
                isError = nodeHash.isNotEmpty() && !isHex32(nodeHash),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Ascii,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "LXMF Propagation",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = lxmfPropOverride,
                onValueChange = { lxmfPropOverride = it.trim().lowercase() },
                placeholder = { Text("32-char hex (optional)") },
                singleLine = true,
                isError = lxmfPropOverride.isNotEmpty() && !isHex32(lxmfPropOverride),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Ascii,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Enter the RFed Node's public identity hash. Notify, channel, delivery, " +
                    "and LXMF propagation hashes are derived automatically. Leave the propagation " +
                    "field empty to use the derived address, or enter a different one to override it. " +
                    "Changes take effect on next service restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (nodeHash.isEmpty() || isHex32(nodeHash)) {
                        UserPreferences.setRfedNodeIdentityHash(context, nodeHash)
                    }
                    if (lxmfPropOverride.isEmpty() || isHex32(lxmfPropOverride)) {
                        UserPreferences.setRfedLxmfPropOverride(context, lxmfPropOverride)
                    }
                },
                enabled = (nodeHash.isEmpty() || isHex32(nodeHash)) &&
                    (lxmfPropOverride.isEmpty() || isHex32(lxmfPropOverride)),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Apply")
            }
        }
    }
}

// ---- Helpers ----

/**
 * Status dot for RFed reachability. We reuse the APP_LINK status constants
 * for color mapping: NONE=no config, ACTIVE=path present,
 * DISCONNECTED=no path.
 *
 * Color semantics follow `/memories/ui-conventions.md`:
 *   * Grey   \u2014 NONE                                 (idle / not yet attempted)
 *   * Green  \u2014 ACTIVE                               (connected)
 *   * Red    \u2014 DISCONNECTED                         (attempt made, failed)
 *
 * NEVER use Red for "unknown" / "not yet tried" \u2014 that is Grey.
 * NEVER use Green until the success condition is actually verified.
 */
@Composable
private fun AppLinkStatusDot(status: Int) {
    val color = when (status) {
        RetichatBridge.AppLinkStatus.ACTIVE -> Color(0xFF34C759)          // Green
        RetichatBridge.AppLinkStatus.DISCONNECTED -> Color(0xFFFF3B30)     // Red
        else -> Color(0xFF8E8E93)                                          // Grey (NONE)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color = color, shape = CircleShape),
    )
}

private fun buildSummary(iface: InterfaceConfigEntity): String {
    val json = try { JSONObject(iface.configJson) } catch (_: Exception) { return "" }
    return when (iface.type) {
        "TCPClientInterface" -> "${json.optString("target_host")}:${json.optString("target_port")}"
        else -> ""
    }
}
