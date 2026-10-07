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
import io.voyager.samsungbarunhospital.data.DiagLog
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
    @Volatile private var advertising = false

    fun isAdvertising(): Boolean = advertising

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
            DiagLog.log("BLE광고", "⚠ 광고 시작 불가: " + if (bluetoothAdapter?.isEnabled != true) "블루투스 꺼짐" else "이 기기는 BLE 광고 기능이 없음 — 다른 기기가 이 기기를 찾을 수 없음")
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
        advertising = false
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
                // Android는 우리가 클라이언트로 연결을 건 상대에 대해서도 GATT 서버 콜백을 같이
                // 올려보낸다(물리 링크가 하나라서). 이미 클라이언트 연결로 등록된 주소를 여기서
                // 서버 연결(subscribed=false)로 덮어쓰면, broadcast()가 그 기기를 "아직 구독 안 함"
                // 으로 보고 조용히 건너뛰어 호출이 전달되지 않는다 — 클라이언트 쪽 항목은 클라이언트
                // 콜백(BleGattClientManager)이 스스로 관리하게 둔다.
                if (connectionManager.get(device.address)?.isClient == true) return
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (connectionManager.isFull()) {
                            Log.w(TAG, "Connection cap reached, rejecting ${device.address}")
                            DiagLog.log("BLE연결", "⚠ 최대 연결 수(${BleMeshConstants.MAX_CONNECTIONS}대) 초과로 …${device.address.takeLast(5)} 연결 거부")
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
            DiagLog.log("BLE광고", "⚠ 이 기기 하드웨어가 BLE 광고를 지원하지 않음 — 다른 기기가 이 기기를 먼저 찾을 수 없고, 이 기기가 찾아가 연결할 때만 통신 가능")
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
                advertising = true
                Log.i(TAG, "Advertising started")
                DiagLog.log("BLE광고", "광고 시작됨 (다른 기기가 이 기기를 찾을 수 있음)")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
                advertising = false
                DiagLog.log("BLE광고", "⚠ 광고 실패: 오류코드 $errorCode (${advertiseErrorText(errorCode)})")
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

    private fun advertiseErrorText(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "광고 데이터 너무 큼"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "다른 앱이 광고 슬롯을 다 씀, 재시도함"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "이미 시작됨"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "블루투스 내부 오류, 재시도함"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "기기가 광고 미지원"
        else -> "알 수 없음"
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
