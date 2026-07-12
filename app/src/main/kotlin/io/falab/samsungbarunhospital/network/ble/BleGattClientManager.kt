package io.falab.samsungbarunhospital.network.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Central role: scans for [BleMeshConstants.SERVICE_UUID] and connects out to whatever it finds.
 * Runs in both [BleMode.CENTRAL_ONLY] and [BleMode.FULL] — a CENTRAL_ONLY (API 24-25) device
 * can never be discovered itself, but the GATT link it opens here is bidirectional once
 * established, so it can still send and receive over it (see BleMode's kdoc).
 *
 * Adapted from bitchat-android's BluetoothGattClientManager
 * (app/src/main/java/com/bitchat/android/mesh/BluetoothGattClientManager.kt): dropped RSSI
 * monitoring, duty-cycling and scan-rate self-healing watchdogs — kept MTU negotiation and a
 * simple backoff retry on scan failure.
 */
class BleGattClientManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connectionManager: BleConnectionManager,
    private val delegate: BleTransportDelegate
) {
    companion object {
        private const val TAG = "BleGattClientManager"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private var scanCallback: ScanCallback? = null
    @Volatile private var isActive = false
    @Volatile private var isScanning = false
    private var scanRetryCount = 0

    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (isActive) return true
        if (!BlePermissions.hasAll(context)) {
            Log.e(TAG, "Missing permissions, not starting GATT client")
            return false
        }
        if (bluetoothAdapter?.isEnabled != true || scanner == null) {
            Log.e(TAG, "Bluetooth disabled or scanner unavailable")
            return false
        }
        isActive = true
        startScanning()
        return true
    }

    @Suppress("MissingPermission")
    fun stop() {
        isActive = false
        stopScanning()
        connectionManager.all().filter { it.isClient }.forEach {
            try { it.gatt?.disconnect() } catch (_: Exception) {}
        }
    }

    @Suppress("MissingPermission")
    private fun startScanning() {
        if (!isActive || isScanning || !BlePermissions.hasAll(context) || scanner == null) return

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleMeshConstants.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                isScanning = false
                scheduleScanRestart()
            }
        }

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            scanRetryCount = 0
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
            isScanning = false
        }
    }

    @Suppress("MissingPermission")
    private fun stopScanning() {
        if (!isScanning || !BlePermissions.hasAll(context) || scanner == null) return
        try {
            scanCallback?.let { scanner.stopScan(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
        isScanning = false
    }

    private fun scheduleScanRestart() {
        scanRetryCount++
        val delayMs = (BleMeshConstants.SCAN_RESTART_BACKOFF_BASE_MS * scanRetryCount)
            .coerceAtMost(BleMeshConstants.SCAN_RESTART_BACKOFF_MAX_MS)
        scope.launch {
            delay(delayMs)
            if (isActive && !isScanning) startScanning()
        }
    }

    @Suppress("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        if (connectionManager.isDeviceConnected(device.address)) return
        if (connectionManager.isFull()) return
        connectToDevice(device)
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice) {
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    scope.launch {
                        delay(200)
                        try {
                            gatt.requestMtu(BleMeshConstants.GATT_MTU_REQUEST)
                        } catch (e: Exception) {
                            Log.w(TAG, "requestMtu failed: ${e.message}")
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionManager.remove(device.address)
                    delegate.onDeviceDisconnected(device.address)
                    scope.launch {
                        delay(200)
                        try { gatt.close() } catch (_: Exception) {}
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectionManager.add(BleConnection(device.address, device, isClient = true, gatt = gatt))
                    try {
                        gatt.discoverServices()
                    } catch (e: Exception) {
                        Log.w(TAG, "discoverServices failed: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "MTU negotiation failed for ${device.address}")
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt.disconnect()
                    return
                }
                val characteristic = gatt.getService(BleMeshConstants.SERVICE_UUID)
                    ?.getCharacteristic(BleMeshConstants.CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.e(TAG, "Required characteristic not found for ${device.address}")
                    gatt.disconnect()
                    return
                }

                val existing = connectionManager.get(device.address)
                if (existing != null) {
                    existing.writeCharacteristic = characteristic
                }

                gatt.setCharacteristicNotification(characteristic, true)
                val cccd = characteristic.getDescriptor(BleMeshConstants.CCCD_UUID)
                if (cccd == null) {
                    Log.e(TAG, "CCCD descriptor not found for ${device.address}")
                    gatt.disconnect()
                    return
                }
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)

                scope.launch {
                    delay(200)
                    delegate.onDeviceConnected(device.address)
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == BleMeshConstants.CHARACTERISTIC_UUID) {
                    delegate.onRawPacketReceived(characteristic.value, device.address)
                }
            }
        }

        try {
            device.connectGatt(context, false, gattCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception connecting to ${device.address}: ${e.message}")
        }
    }
}
