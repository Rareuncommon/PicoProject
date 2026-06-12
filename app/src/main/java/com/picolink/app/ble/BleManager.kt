package com.picolink.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE client for the Nordic UART Service (NUS) exposed by the Pico W firmware.
 *
 * Responsibilities:
 *  - scanning for peripherals,
 *  - GATT connection lifecycle (connect, MTU negotiation, service discovery,
 *    notification subscription),
 *  - newline-framed message transport in both directions, chunked to the
 *    negotiated MTU with a serialized write queue.
 *
 * All permission-guarded calls are gated by the UI layer, which never invokes
 * connect/scan before the runtime permissions are granted.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone -> pico
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // pico -> phone
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val REQUESTED_MTU = 247
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, DISCOVERING, READY }

    data class DiscoveredDevice(
        val device: BluetoothDevice,
        val name: String?,
        val address: String,
        val rssi: Int,
        val lastSeen: Long,
    )

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scanResults = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val scanResults: StateFlow<List<DiscoveredDevice>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    /** Complete newline-terminated lines received from the peripheral. */
    private val _incomingLines = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val incomingLines: SharedFlow<String> = _incomingLines.asSharedFlow()

    /** Raw transport events for the on-screen log (TX/RX/state changes). */
    private val _transportLog = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val transportLog: SharedFlow<String> = _transportLog.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var mtuPayload = 20
    private val rxBuffer = StringBuilder()

    /** Outgoing chunks, drained one write at a time. */
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var writeInFlight = false

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // ---------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val entry = DiscoveredDevice(
                device = result.device,
                name = result.scanRecord?.deviceName ?: result.device.name,
                address = result.device.address,
                rssi = result.rssi,
                lastSeen = System.currentTimeMillis(),
            )
            _scanResults.value = (_scanResults.value.filter { it.address != entry.address } + entry)
                .sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            _transportLog.tryEmit("Scan failed (code $errorCode)")
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (_isScanning.value) return
        _scanResults.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No service-UUID filter: some firmware variants don't advertise the NUS
        // UUID, and an unfiltered scan lets the user see everything nearby.
        scanner.startScan(null, settings, scanCallback)
        _isScanning.value = true
    }

    fun stopScan() {
        if (!_isScanning.value) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _isScanning.value = false
    }

    // -------------------------------------------------------------- connection

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _transportLog.tryEmit("Link up, requesting MTU $REQUESTED_MTU")
                    _connectionState.value = ConnectionState.DISCOVERING
                    if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _transportLog.tryEmit("Disconnected (status $status)")
                    g.close()
                    cleanup()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuPayload = (if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23) - 3
            _transportLog.tryEmit("MTU negotiated: payload $mtuPayload bytes")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(NUS_SERVICE)
            val rx = service?.getCharacteristic(NUS_RX)
            val tx = service?.getCharacteristic(NUS_TX)
            if (service == null || rx == null || tx == null) {
                _transportLog.tryEmit("Device does not expose the Nordic UART service")
                disconnect()
                return
            }
            rxCharacteristic = rx
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                onReady()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD) onReady()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = handleNotification(value)

        @Deprecated("Pre-API 33 path")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < 33) {
                characteristic.value?.let { handleNotification(it) }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeInFlight = false
            drainWriteQueue()
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _rssi.value = rssi
        }
    }

    private fun onReady() {
        _connectionState.value = ConnectionState.READY
        _transportLog.tryEmit("Notifications enabled — link ready")
    }

    fun connect(device: BluetoothDevice) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        stopScan()
        _connectionState.value = ConnectionState.CONNECTING
        _connectedDevice.value = device
        _transportLog.tryEmit("Connecting to ${device.address}…")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun connect(address: String): Boolean {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
        connect(device)
        return true
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        cleanup()
    }

    private fun cleanup() {
        gatt = null
        rxCharacteristic = null
        rxBuffer.setLength(0)
        writeQueue.clear()
        writeInFlight = false
        _rssi.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun requestRssi() {
        gatt?.readRemoteRssi()
    }

    // --------------------------------------------------------------- transport

    private fun handleNotification(value: ByteArray) {
        rxBuffer.append(String(value, Charsets.UTF_8))
        while (true) {
            val nl = rxBuffer.indexOf("\n")
            if (nl < 0) break
            val line = rxBuffer.substring(0, nl).trim()
            rxBuffer.delete(0, nl + 1)
            if (line.isNotEmpty()) _incomingLines.tryEmit(line)
        }
    }

    /** Sends one newline-terminated message, chunked to the negotiated MTU. */
    fun sendLine(line: String): Boolean {
        if (_connectionState.value != ConnectionState.READY) return false
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + mtuPayload, payload.size)
            writeQueue.add(payload.copyOfRange(offset, end))
            offset = end
        }
        drainWriteQueue()
        return true
    }

    @Synchronized
    private fun drainWriteQueue() {
        if (writeInFlight) return
        val g = gatt ?: return
        val rx = rxCharacteristic ?: return
        val chunk = writeQueue.poll() ?: return
        writeInFlight = true
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            rx.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(rx)
        }
    }
}
