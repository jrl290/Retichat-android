package com.retichat.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.retichat.app.ServiceState
import com.retichat.app.data.db.entity.InterfaceConfigEntity
import com.retichat.app.service.DefaultEndpointManager
import com.retichat.app.service.PersistentConnectionService
import com.retichat.app.service.UserPreferences
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

            // ---- Section: Display Name ----
            item {
                DisplayNameCard()
            }

            // ---- Section: Persistent Connection ----
            item {
                PersistentConnectionCard()
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
private fun DisplayNameCard() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(UserPreferences.PREF_NAME, android.content.Context.MODE_PRIVATE)
    }
    var name by remember {
        mutableStateOf(prefs.getString(UserPreferences.PREF_KEY_DISPLAY_NAME, "") ?: "")
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
                "Display name",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Visible to other Reticulum users when you announce. " +
                    "Leave blank to use the default (\"Retichat\").",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { newName ->
                    name = newName
                    prefs.edit()
                        .putString(UserPreferences.PREF_KEY_DISPLAY_NAME, newName)
                        .apply()
                },
                label = { Text("Name") },
                placeholder = { Text("Retichat") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        }
    }
}

// ---- Persistent connection card ----

@Composable
private fun PersistentConnectionCard() {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(PersistentConnectionService.isEnabled(context))
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Persistent connection",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Stay connected in the background",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { newValue ->
                        enabled = newValue
                        PersistentConnectionService.setEnabled(context, newValue)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Uses more battery. Keeps the connection alive so messages " +
                    "arrive instantly, even when the app is closed. " +
                    "A persistent notification will appear \u2014 you can " +
                    "long-press it and choose \"Minimize\" to hide it.",
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
    val endpoint = DefaultEndpointManager.current

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
                    text = "${endpoint.first}:${endpoint.second}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
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
                        com.retichat.app.bridge.RetichatBridge.setDropAnnounces(newValue)
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

// ---- Helpers ----

private fun buildSummary(iface: InterfaceConfigEntity): String {
    val json = try { JSONObject(iface.configJson) } catch (_: Exception) { return "" }
    return when (iface.type) {
        "TCPClientInterface" -> "${json.optString("target_host")}:${json.optString("target_port")}"
        "TCPServerInterface" -> "${json.optString("listen_ip")}:${json.optString("listen_port")}"
        "UDPInterface" -> "${json.optString("listen_ip")}:${json.optString("listen_port")} → ${json.optString("forward_ip")}:${json.optString("forward_port")}"
        "AutoInterface" -> json.optString("group_id", "reticulum")
        "I2PInterface" -> json.optString("peers").take(30)
        else -> ""
    }
}
