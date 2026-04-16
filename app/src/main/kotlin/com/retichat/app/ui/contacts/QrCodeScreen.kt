package com.retichat.app.ui.contacts

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors

/**
 * Shows the user's own LXMF destination hash as a QR code (SHOW mode)
 * or opens the camera to scan another user's QR code (SCAN mode).
 *
 * The QR payload is the hex-encoded LXMF destination hash (32 hex chars).
 */
object QrCodeScreen {
    enum class Mode { SHOW, SCAN }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeScreen(
    mode: QrCodeScreen.Mode,
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
    selfDestHashHex: String = "",  // injected in production
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == QrCodeScreen.Mode.SHOW) "My QR Code" else "Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (mode) {
                QrCodeScreen.Mode.SHOW -> ShowQrContent(selfDestHashHex)
                QrCodeScreen.Mode.SCAN -> ScanQrContent(onScanned)
            }
        }
    }
}

@Composable
private fun ShowQrContent(destHashHex: String) {
    if (destHashHex.isBlank()) {
        Text("Identity not yet initialised", style = MaterialTheme.typography.bodyLarge)
        return
    }

    val lxmfUri = "lxmf://$destHashHex"
    val bitmap = remember(lxmfUri) { generateQrBitmap(lxmfUri, 512) }
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR code",
                modifier = Modifier.size(280.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = lxmfUri,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("LXMF Address", lxmfUri))
                copied = true
            },
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (copied) "Copied!" else "Copy Address")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Share this QR code or link so others can message you",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ScanQrContent(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var scannedValue by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (scannedValue != null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Scanned!", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(scannedValue ?: "", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { onScanned(scannedValue!!) }) {
                Text("Add Contact")
            }
        }
        return
    }

    if (!hasCameraPermission) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("Camera permission required to scan QR codes")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant Permission")
            }
        }
        return
    }

    // Camera preview with barcode detection
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processBarcode(imageProxy) { value ->
                                scannedValue = value
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analyzer,
                    )
                } catch (_: Exception) {}
            }, ctx.mainExecutor)

            previewView
        },
    )
}

@OptIn(ExperimentalGetImage::class)
private fun processBarcode(imageProxy: ImageProxy, onResult: (String) -> Unit) {
    val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    val scanner = BarcodeScanning.getClient()

    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                val value = barcode.rawValue ?: continue
                // Accept lxmf:// URI → extract the hex hash
                val lxmfMatch = Regex("^lxmf://([0-9a-fA-F]{32})$").find(value)
                if (lxmfMatch != null) {
                    onResult(lxmfMatch.groupValues[1].lowercase())
                    break
                }
                // Also accept a raw 32-char hex string for backwards compat
                if (value.matches(Regex("^[0-9a-fA-F]{32}$"))) {
                    onResult(value.lowercase())
                    break
                }
            }
        }
        .addOnCompleteListener { imageProxy.close() }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}
