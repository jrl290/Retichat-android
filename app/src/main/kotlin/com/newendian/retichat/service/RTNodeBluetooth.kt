package com.newendian.retichat.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.newendian.retichat.bridge.PrnsBleCallback
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** What the Nearby RTNode card in Settings shows. */
sealed class RTNodeBluetoothStatus {
    /** The switch is off, or the stack is not running. */
    data object Off : RTNodeBluetoothStatus()
    /** Scanning for an RTNode. */
    data object Searching : RTNodeBluetoothStatus()
    /** Dialling or handshaking with one. */
    data object Connecting : RTNodeBluetoothStatus()
    /** Linked; the RTNode's Bluetooth identity, first 8 hex digits. */
    data class Connected(val node: String) : RTNodeBluetoothStatus()
    /** Bluetooth is off, not permitted or absent, or the engine failed. */
    data class Unavailable(val reason: String) : RTNodeBluetoothStatus()
}

/**
 * Bluetooth link to any RTNode in range, with no configuration: the Prns
 * native protocol, from the dialer's (GATT central's) side. Mirrors iOS
 * `RTNodeBluetoothCoordinator`.
 *
 * The app is only ever the central. It advertises nothing and runs no GATT
 * server, so no phone can connect to it; it dials RTNodes only (their
 * advertisement carries the Prns peripheral-only flag).
 *
 * The protocol lives in Reticulum-rust (`interfaces::prns_ble`, the
 * `nativePrnsBle*` JNI): which advertisement to dial, the handshake,
 * fragments, and one Reticulum interface per RTNode. This object is the
 * radio: it scans when the engine asks, reports each advertisement, connects
 * when the engine hands back a link, sets the GATT link up (MTU, service
 * discovery, both subscriptions, one operation at a time), performs the
 * writes it is asked for and reports every event. It keeps no timers and
 * never re-dials on its own: the engine decides.
 *
 * Started by StackRuntime's bootstrap after the delivery destination is
 * published; stopped by shutdownNow before the stack shuts down.
 */
@SuppressLint("MissingPermission") // every path starts from start(), which checks the permissions
object RTNodeBluetooth {

    private const val TAG = "RTNodeBLE"

    // Reticulum-rust interfaces/prns_ble/wire.rs
    private val SERVICE: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e3")
    private val CONTROL: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e7")
    private val DATA: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e8")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The manufacturer data RTNode puts its role in (Prns's experimental role field). */
    private const val ROLE_COMPANY_ID = 0xFFFF

    /** Prns Android dialers ask for this before service discovery. */
    private const val REQUESTED_MTU = 517

    /** BLUETOOTH_SCAN is declared neverForLocation. */
    val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private val _status = MutableStateFlow<RTNodeBluetoothStatus>(RTNodeBluetoothStatus.Off)
    val status: StateFlow<RTNodeBluetoothStatus> = _status

    private val thread = HandlerThread("rtnode-ble").apply { start() }
    private val handler = Handler(thread.looper)

    /** One dial or connection, under the engine's link id. */
    private class Node(val link: Long, val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null
        var control: BluetoothGattCharacteristic? = null
        var data: BluetoothGattCharacteristic? = null
        var mtu: Int = 23
    }

    // Confined to `handler`'s thread.
    private var appContext: Context? = null
    private var running = false
    private var scanWanted = false
    private var scanning = false
    private val nodes = HashMap<Long, Node>()

    // Touched only by start/stop, which StackRuntime serialises (initLock).
    @Volatile private var engineRunning = false
    @Volatile private var receiverRegistered = false
    /** Where start() would have put the engine had the permissions been granted. */
    @Volatile private var pendingStorageDir: String? = null

    fun hasPermissions(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---- Lifecycle (StackRuntime, off the main thread) ----

    /**
     * [storageDir] keeps the phone's Bluetooth identity (`ble_identity`).
     * Without the Bluetooth permissions it stays down and remembers the
     * directory until [onPermissionsGranted].
     */
    @Synchronized
    fun start(context: Context, storageDir: String) {
        if (engineRunning) return
        val app = context.applicationContext
        if (!hasPermissions(app)) {
            pendingStorageDir = storageDir
            publish(RTNodeBluetoothStatus.Unavailable("Bluetooth permission not granted"))
            return
        }
        pendingStorageDir = null
        if (adapter(app) == null) {
            publish(RTNodeBluetoothStatus.Unavailable("This device has no Bluetooth"))
            return
        }
        val identity = RetichatBridge.prnsBleStart(storageDir, engineCallback)
        if (identity == null) {
            val why = RetichatBridge.lastError() ?: "unknown error"
            Log.e(TAG, "Bluetooth did not start: $why")
            publish(RTNodeBluetoothStatus.Unavailable("Bluetooth did not start: $why"))
            return
        }
        engineRunning = true
        Log.i(TAG, "started, Bluetooth identity ${identity.hex()}")
        ContextCompat.registerReceiver(
            app, bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        handler.post {
            appContext = app
            running = true
            scanWanted = true
            applyScan()
            publishIdle()
        }
    }

    /** The user granted the Bluetooth permissions: start if the stack wanted to. */
    @Synchronized
    fun onPermissionsGranted(context: Context) {
        val dir = pendingStorageDir ?: return
        start(context, dir)
    }

    /**
     * Closes the link and removes the RTNode's interface. Blocks until the
     * engine has let go of it, so the stack can shut down after.
     */
    @Synchronized
    fun stop() {
        pendingStorageDir = null
        onHandler {
            running = false
            applyScan()
        }
        if (engineRunning) {
            RetichatBridge.prnsBleStop()
            engineRunning = false
        }
        onHandler {
            for (node in nodes.values) {
                node.gatt?.disconnect()
                node.gatt?.close()
            }
            nodes.clear()
            appContext?.let { ctx ->
                if (receiverRegistered) {
                    runCatching { ctx.unregisterReceiver(bluetoothStateReceiver) }
                    receiverRegistered = false
                }
            }
        }
        publish(RTNodeBluetoothStatus.Off)
        Log.i(TAG, "stopped")
    }

    // ---- Engine callbacks (Rust and Binder threads; hop to `handler`) ----

    private val engineCallback = object : PrnsBleCallback {
        override fun onScan(on: Boolean) {
            handler.post {
                scanWanted = on
                applyScan()
            }
        }

        override fun onWrite(link: Long, characteristic: Int, data: ByteArray) {
            handler.post { write(link, characteristic, data) }
        }

        override fun onDisconnect(link: Long) {
            handler.post {
                // The engine has already forgotten the link: no linkClosed.
                val node = nodes.remove(link) ?: return@post
                node.gatt?.disconnect()
                node.gatt?.close()
                publishIdle()
            }
        }

        override fun onLinkState(link: Long, state: Int, peer: ByteArray?, interfaceName: String?) {
            handler.post {
                if (!nodes.containsKey(link)) return@post
                when (state) {
                    1 -> {
                        Log.i(TAG, "link $link: settled with RTNode ${peer?.hex()}, interface $interfaceName")
                        publish(RTNodeBluetoothStatus.Connected(peer?.hex()?.take(8) ?: "?"))
                    }
                    0, 3 -> publish(RTNodeBluetoothStatus.Connecting)
                }
            }
        }
    }

    // ---- Radio work (on `handler`) ----

    private fun adapter(context: Context?): BluetoothAdapter? =
        context?.getSystemService(BluetoothManager::class.java)?.adapter

    private fun applyScan() {
        val adapter = adapter(appContext)
        val scanner = adapter?.bluetoothLeScanner
        val want = running && scanWanted && adapter?.isEnabled == true
        if (want && !scanning && scanner != null) {
            // Every advertisement, repeated: after a failed dial the engine
            // pauses that node, and only a later advertisement brings it back.
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } else if (!want && scanning) {
            runCatching { scanner?.stopScan(scanCallback) }
            scanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { sighted(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            handler.post { results.forEach { sighted(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                Log.w(TAG, "scan failed: $errorCode")
                publish(RTNodeBluetoothStatus.Unavailable("Bluetooth scan failed ($errorCode)"))
            }
        }
    }

    private fun sighted(result: ScanResult) {
        if (!running) return
        val device = result.device
        if (nodes.values.any { it.device.address == device.address }) return
        val role = result.scanRecord?.getManufacturerSpecificData(ROLE_COMPANY_ID) ?: return
        val link = RetichatBridge.prnsBleSighted(device.address, ROLE_COMPANY_ID, role)
        if (link == 0L) return
        val node = Node(link, device)
        nodes[link] = node
        Log.i(TAG, "link $link: dialling RTNode ${device.address}, RSSI ${result.rssi}")
        publish(RTNodeBluetoothStatus.Connecting)
        node.gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (node.gatt == null) closed(node, "connectGatt refused")
    }

    private fun nodeFor(gatt: BluetoothGatt): Node? =
        nodes.values.firstOrNull { it.gatt === gatt }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                val node = nodeFor(gatt)
                if (node == null) {
                    gatt.close()
                    return@post
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED ->
                        // MTU first, as Prns Android dialers do; the rest
                        // follows each completion, one operation at a time.
                        if (!gatt.requestMtu(REQUESTED_MTU)) fail(node, "requestMtu refused")
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        gatt.close()
                        closed(node, "disconnected (status $status)")
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                val node = nodeFor(gatt) ?: return@post
                node.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                if (!gatt.discoverServices()) fail(node, "discoverServices refused")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                val node = nodeFor(gatt) ?: return@post
                val service = gatt.getService(SERVICE)
                node.control = service?.getCharacteristic(CONTROL)
                node.data = service?.getCharacteristic(DATA)
                val control = node.control
                if (status != BluetoothGatt.GATT_SUCCESS || control == null || node.data == null) {
                    fail(node, "service discovery: status $status, Prns characteristics ${if (control == null || node.data == null) "missing" else "found"}")
                    return@post
                }
                // Control, then data; the Hello only after both are confirmed,
                // since the Welcome arrives as a notification.
                subscribe(node, control)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                val node = nodeFor(gatt) ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(node, "subscribe to ${descriptor.characteristic.uuid}: status $status")
                    return@post
                }
                when (descriptor.characteristic.uuid) {
                    CONTROL -> node.data?.let { subscribe(node, it) }
                    // One ATT PDU's worth per write (MTU - 3): a longer
                    // with-response write would become an ATT long write,
                    // which Prns peers do not handle.
                    DATA -> if (!RetichatBridge.prnsBleLinkReady(node.link, node.mtu - 3)) {
                        fail(node, "link_ready: ${RetichatBridge.lastError()}")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            received(gatt, characteristic, value)
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                received(gatt, characteristic, characteristic.value ?: return)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                val node = nodeFor(gatt) ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "link ${node.link}: write failed, status $status")
                RetichatBridge.prnsBleLinkWriteDone(node.link, status == BluetoothGatt.GATT_SUCCESS)
            }
        }
    }

    private fun received(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val bytes = value.copyOf()
        val which = if (characteristic.uuid == CONTROL) 0 else 1
        handler.post {
            val node = nodeFor(gatt) ?: return@post
            RetichatBridge.prnsBleLinkReceived(node.link, which, bytes)
        }
    }

    private fun subscribe(node: Node, characteristic: BluetoothGattCharacteristic) {
        val gatt = node.gatt ?: return
        val descriptor = characteristic.getDescriptor(CCCD)
        if (descriptor == null || !gatt.setCharacteristicNotification(characteristic, true)) {
            fail(node, "cannot subscribe to ${characteristic.uuid}")
            return
        }
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!queued) fail(node, "subscription to ${characteristic.uuid} refused")
    }

    private fun write(link: Long, characteristic: Int, data: ByteArray) {
        val node = nodes[link]
        val gatt = node?.gatt
        val target = if (characteristic == 0) node?.control else node?.data
        if (gatt == null || target == null) {
            // Link gone or not set up: the write cannot happen.
            RetichatBridge.prnsBleLinkWriteDone(link, false)
            return
        }
        // With response: RTNode's characteristics are write-with-response,
        // and the response is what paces the next fragment.
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(target, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            target.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(target)
        }
        if (!queued) {
            Log.w(TAG, "link $link: write refused")
            RetichatBridge.prnsBleLinkWriteDone(link, false)
        }
    }

    /** The connection or the attempt is gone: tell the engine. */
    private fun closed(node: Node, why: String) {
        nodes.remove(node.link)
        Log.i(TAG, "link ${node.link}: $why")
        RetichatBridge.prnsBleLinkClosed(node.link)
        publishIdle()
    }

    /** Setting the link up failed: drop the connection and tell the engine. */
    private fun fail(node: Node, why: String) {
        node.gatt?.disconnect()
        node.gatt?.close()
        closed(node, why)
    }

    /** Bluetooth turned off: every connection went with it. */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            handler.post {
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        applyScan()
                        publishIdle()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        for (node in nodes.values.toList()) {
                            node.gatt?.close()
                            closed(node, "Bluetooth turned off")
                        }
                        scanning = false
                        publish(RTNodeBluetoothStatus.Unavailable("Bluetooth is off"))
                    }
                }
            }
        }
    }

    private fun publishIdle() {
        if (nodes.isNotEmpty()) return
        val bluetoothOn = adapter(appContext)?.isEnabled == true
        publish(
            when {
                !running -> RTNodeBluetoothStatus.Off
                !bluetoothOn -> RTNodeBluetoothStatus.Unavailable("Bluetooth is off")
                else -> RTNodeBluetoothStatus.Searching
            }
        )
    }

    private fun publish(status: RTNodeBluetoothStatus) {
        _status.value = status
    }

    /** Run on the handler thread and wait for it (callers are off the main thread). */
    private fun onHandler(block: () -> Unit) {
        if (Thread.currentThread() === thread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
