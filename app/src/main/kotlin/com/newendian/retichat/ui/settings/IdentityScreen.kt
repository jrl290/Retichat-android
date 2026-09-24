package com.newendian.retichat.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import com.newendian.retichat.IdentityShareFormat
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.service.StackRuntime
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newendian.retichat.service.DistroManager
import com.newendian.retichat.service.RfedDistroClient
import kotlinx.coroutines.launch

/**
 * Settings row that opens the Identity screen, as retichat.com's Settings
 * "Identity" entry does (app.js 4683-4697).
 */
@Composable
fun IdentityNavRow(onClick: () -> Unit) {
    val distro by DistroManager.state.collectAsState()
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Identity", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(2.dp))
                Text(
                    if (distro != null) "Device + distro addresses and keys" else "Device address and keys · no distro yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * The Identity screen (retichat.com `_renderIdentityModal`): the distro
 * address first — one LXMF address shared by all your devices — then this
 * device's own address. The private keys are never displayed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val distro by DistroManager.state.collectAsState()
    val status by RfedDistroClient.status.collectAsState()
    var showGenerate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showForget by remember { mutableStateOf(false) }
    var showAddDevice by remember { mutableStateOf(false) }

    val deviceHashHex = remember { StackRuntime.selfDestHash.toHexString() }
    val devicePubHex = remember { RetichatBridge.identityPublicKey(StackRuntime.identityHandle)?.toHexString() ?: "" }
    val deviceIdHex = remember { RetichatBridge.identityHash(StackRuntime.identityHandle)?.toHexString() ?: "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ── Distro ────────────────────────────────────────────────
            SectionCard(title = "Distro address") {
                val d = distro
                if (d == null) {
                    Hint("One LXMF address shared by all your devices. Anything sent to it is fanned out by RFed to every registered device.")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showGenerate = true }) { Text("Generate") }
                        OutlinedButton(onClick = { showImport = true }) { Text("Import") }
                    }
                } else {
                    KeyValueRow("Address", d.deliveryHashHex, context)
                    KeyValueRow("Identity", d.identityHashHex, context)
                    KeyValueRow("Public key", d.publicKeyHex, context)
                    KeyValueRow("Contact", d.contactUri, context)
                    Spacer(Modifier.height(8.dp))
                    Hint("Give senders the contact link. Address is where distro mail is delivered; identity is the key's own hash and is not routable.")
                    Spacer(Modifier.height(8.dp))
                    val line = when {
                        status.lastError != null -> "RFed: ${status.lastError}"
                        status.registered && status.announced -> "Registered with RFed · RFed announces this address"
                        status.registered -> "Registered with RFed"
                        else -> "Not yet registered with RFed"
                    }
                    Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showAddDevice = true }) { Text("Add another device") }
                        TextButton(onClick = { showForget = true }) { Text("Forget") }
                    }
                }
            }

            // ── This device ───────────────────────────────────────────
            SectionCard(title = "This device") {
                KeyValueRow("Address", deviceHashHex.ifEmpty { "—" }, context)
                KeyValueRow("Identity", deviceIdHex.ifEmpty { "—" }, context)
                KeyValueRow("Public key", devicePubHex.ifEmpty { "—" }, context)
                val link = IdentityShareFormat.encode(deviceHashHex, devicePubHex)
                if (link != null) KeyValueRow("Contact", link, context)
                Spacer(Modifier.height(8.dp))
                Hint(
                    if (distro != null) "This device's own address. Messages you send go out from the distro address."
                    else "This device's own address. Share the contact link so others can reach you.",
                )
            }
        }
    }

    if (showGenerate) {
        AlertDialog(
            onDismissRequest = { showGenerate = false },
            title = { Text("Generate a distro identity?") },
            text = { Text("This device will create a new shared address and register it with RFed. Add your other devices afterwards with “Add another device”.") },
            confirmButton = {
                TextButton(onClick = {
                    showGenerate = false
                    if (DistroManager.generate(context)) RfedDistroClient.registerIfNeeded(context)
                    else Toast.makeText(context, "Could not generate a distro identity", Toast.LENGTH_SHORT).show()
                }) { Text("Generate") }
            },
            dismissButton = { TextButton(onClick = { showGenerate = false }) { Text("Cancel") } },
        )
    }
    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Import distro identity") },
            text = {
                Column {
                    Text("Paste a rfed-distro-private-key:// URI or a 128-character hex private key.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, minLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (DistroManager.importText(context, text)) {
                        showImport = false
                        RfedDistroClient.registerIfNeeded(context)
                    } else {
                        Toast.makeText(context, "That is not a distro private key", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } },
        )
    }
    if (showForget) {
        AlertDialog(
            onDismissRequest = { showForget = false },
            title = { Text("Forget the distro identity?") },
            text = { Text("This device stops receiving mail for the shared address. Other devices that hold the key are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    showForget = false
                    scope.launch {
                        RfedDistroClient.unregister(context)
                        DistroManager.forget(context)
                    }
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { showForget = false }) { Text("Cancel") } },
        )
    }
    if (showAddDevice) {
        var target by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDevice = false },
            title = { Text("Add another device") },
            text = {
                Column {
                    Text("Send the distro identity to another device you own. It travels as a private LXMF message; the other device is asked before importing it.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = target, onValueChange = { target = it.trim().lowercase() }, singleLine = true,
                        label = { Text("That device's LXMF address") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = target.length == 32,
                    onClick = {
                        val dest = target
                        showAddDevice = false
                        scope.launch {
                            val ok = RfedDistroClient.sendIdentityTo(context, dest)
                            Toast.makeText(context, if (ok) "Identity sent" else "Could not send the identity", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Send identity") }
            },
            dismissButton = { TextButton(onClick = { showAddDevice = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Label above a full-width monospace value that wraps, with a copy button. */
@Composable
private fun KeyValueRow(label: String, value: String, context: Context) {
    Spacer(Modifier.height(10.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The weight must sit on a plain Box: SelectionContainer does not pass
        // it to its child, so long values took the whole row and pushed the
        // copy button off-screen.
        Box(modifier = Modifier.weight(1f)) {
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
        IconButton(onClick = { copy(context, label, value) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label", modifier = Modifier.size(18.dp))
        }
    }
}

/** Prompt shown when another of our devices offers its distro identity. */
@Composable
fun DistroTransferOfferDialog() {
    val context = LocalContext.current
    val offer by RfedDistroClient.pendingTransfer.collectAsState()
    val o = offer ?: return
    AlertDialog(
        onDismissRequest = { RfedDistroClient.resolveTransfer(context, accept = false) },
        title = { Text("Import distro identity?") },
        text = {
            Text(
                "Device ${o.fromHashHex.take(12)}… sent this device a shared distro identity. " +
                    "Importing it makes its address this device's address too" +
                    (if (DistroManager.hasDistro) ", replacing the current one." else "."),
            )
        },
        confirmButton = {
            TextButton(onClick = { RfedDistroClient.resolveTransfer(context, accept = true) }) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = { RfedDistroClient.resolveTransfer(context, accept = false) }) { Text("Ignore") }
        },
    )
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
