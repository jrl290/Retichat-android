package com.newendian.retichat.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
 * The "Distro address" section, matching the web client's Identity screen
 * (Retichat-js app.js _buildDistroIdentitySection): one LXMF address shared
 * by all your devices; anything sent to it is fanned out by RFed to every
 * registered device. Generate or Import when there is none; Address,
 * Contact link, Add another device and Forget when there is one. The private
 * key is never displayed.
 */
@Composable
fun DistroIdentityCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val distro by DistroManager.state.collectAsState()
    val status by RfedDistroClient.status.collectAsState()
    var showGenerate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showForget by remember { mutableStateOf(false) }
    var showAddDevice by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Distro address",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
            val d = distro
            if (d == null) {
                Text(
                    "One LXMF address shared by all your devices. Anything sent to it is fanned out by RFed to every registered device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showGenerate = true }) { Text("Generate") }
                    OutlinedButton(onClick = { showImport = true }) { Text("Import") }
                }
            } else {
                LabeledValue("Address", d.deliveryHashHex) { copy(context, "Distro delivery address", d.deliveryHashHex) }
                LabeledValue("Identity", d.identityHashHex) { copy(context, "Distro identity hash", d.identityHashHex) }
                LabeledValue("Contact link", d.contactUri) { copy(context, "Distro contact link", d.contactUri) }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Give senders the contact link. Address is where distro mail is delivered; identity is the key's own hash and is not routable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                val line = when {
                    status.lastError != null -> "RFed: ${status.lastError}"
                    status.registered && status.announced -> "Registered with RFed; RFed announces this address"
                    status.registered -> "Registered with RFed"
                    else -> "Not yet registered with RFed"
                }
                Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAddDevice = true }) { Text("Add another device") }
                    TextButton(onClick = { showForget = true }) { Text("Forget") }
                }
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
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false, modifier = Modifier.fillMaxWidth())
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
                    Text("Send the distro identity to another device you own. It is delivered as a private LXMF message; the other device is asked before importing it.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = target, onValueChange = { target = it }, singleLine = true,
                        label = { Text("That device's LXMF address") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = target.trim().length == 32,
                    onClick = {
                        val dest = target.trim()
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

@Composable
private fun LabeledValue(label: String, value: String, onCopy: () -> Unit) {
    Spacer(Modifier.height(6.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onCopy) { Text("Copy") }
    }
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
