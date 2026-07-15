package io.voyager.samsungbarunhospital.network.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Peripheral role only: advertises [BleMeshConstants.SERVICE_UUID] and runs a GATT server so
 * other devices can find and write to us. Only started in [BleMode.FULL] (API 26+) — advertising
 * is unreliable below that, see BleMode's kdoc.
 *
 * Adapted from bitchat-android's BluetoothGattServerManager
 * (app/src/main/java/com/bitchat/android/mesh/BluetoothGattServerManager.kt): dropped the
 * debug-setting toggles and RSSI bookkeeping, kept the advertise-failure backoff retry since
 * losing advertising silently (until a manual BLE toggle) would defeat the whole feature.
 */
class BleGattServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connectionManager: BleConnectionManager,
    private val delegate: BleTransportDelegate,
    private val myPeerId: ByteArray
) {
    companion object {
        private const val TAG = "BleGattServerManager"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseRetryCount = 0
    @Volatile private var isActive = false

    fun characteristicUuid() = BleMeshConstants.CHARACTERISTIC_UUID

    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (isActive) return true
        if (!BlePermissions.hasAll(context)) {
            Log.e(TAG, "Missing permissions, not starting GATT server")
            return false
        }
        if (bluetoothAdapter?.isEnabled != true || advertiser == null) {
            Log.e(TAG, "Bluetooth disabled or advertiser unavailable")
            return false
        }
        isActive = true
        scope.launch {
            setupGattServer()
            delay(300)
            startAdvertising()
        }
        return true
    }

    @Suppress("MissingPermission")
    fun stop() {
        isActive = false
        stopAdvertising()
        try {
            connectionManager.all().filter { !it.isClient }.forEach { gattServer?.cancelConnection(it.device) }
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling server connections: ${e.message}")
        }
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server: ${e.message}")
        }
        gattServer = null
    }

    @Suppress("MissingPermission")
    private fun setupGattServer() {
        if (!isActive || !BlePermissions.hasAll(context)) return

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (!isActive) return
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (connectionManager.isFull()) {
                            Log.w(TAG, "Connection cap reached, rejecting ${device.address}")
                            try { gattServer?.cancelConnection(device) } catch (_: Exception) {}
                            return
                        }
                        connectionManager.add(BleConnection(device.address, device, isClient = false))
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectionManager.remove(device.address)
                        delegate.onDeviceDisconnected(device.address)
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (isActive && characteristic.uuid == BleMeshConstants.CHARACTERISTIC_UUID) {
                    delegate.onRawPacketReceived(value, device.address)
                }
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "sendResponse failed: ${e.message}")
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (isActive && BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    connectionManager.get(device.address)?.subscribed = true
                    scope.launch {
                        delay(100)
                        if (isActive) delegate.onDeviceConnected(device.address)
                    }
                }
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "sendResponse failed: ${e.message}")
                    }
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                // Same reasoning as the client side's onCharacteristicWrite — signals the
                // outstanding notify on this link is done so BleConnectionManager.broadcast()
                // can send the next fragment.
                connectionManager.onWriteAcked(device.address)
            }
        }

        try {
            gattServer?.close()
        } catch (_: Exception) {}

        if (!isActive) return

        gattServer = bluetoothManager.openGattServer(context, callback)

        val newCharacteristic = BluetoothGattCharacteristic(
            BleMeshConstants.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val cccd = BluetoothGattDescriptor(
            BleMeshConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        newCharacteristic.addDescriptor(cccd)
        characteristic = newCharacteristic

        val service = BluetoothGattService(BleMeshConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(newCharacteristic)
        gattServer?.addService(service)
    }

    fun getGattServer(): BluetoothGattServer? = gattServer
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic

    @Suppress("MissingPermission", "DEPRECATION")
    private fun startAdvertising() {
        if (!isActive || !BlePermissions.hasAll(context)) return
        if (bluetoothAdapter == null || advertiser == null) return
        if (bluetoothAdapter.isMultipleAdvertisementSupported != true) {
            Log.w(TAG, "Multiple advertisement not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleMeshConstants.SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BleMeshConstants.SERVICE_UUID), myPeerId)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                advertiseRetryCount = 0
                Log.i(TAG, "Advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
                if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED &&
                    errorCode != ADVERTISE_FAILED_DATA_TOO_LARGE &&
                    errorCode != ADVERTISE_FAILED_FEATURE_UNSUPPORTED
                ) {
                    scheduleAdvertiseRestart()
                }
            }
        }

        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
        }
    }

    @Suppress("MissingPermission")
    private fun stopAdvertising() {
        if (!BlePermissions.hasAll(context) || advertiser == null) return
        try {
            advertiseCallback?.let { advertiser.stopAdvertising(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
    }

    private fun scheduleAdvertiseRestart() {
        advertiseRetryCount++
        val delayMs = (BleMeshConstants.ADVERTISE_RESTART_BACKOFF_BASE_MS * advertiseRetryCount)
            .coerceAtMost(BleMeshConstants.ADVERTISE_RESTART_BACKOFF_MAX_MS)
        scope.launch {
            delay(delayMs)
            if (isActive) {
                stopAdvertising()
                delay(100)
                startAdvertising()
            }
        }
    }
}
