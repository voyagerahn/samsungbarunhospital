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
import kotlinx.coroutines.Job
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
    @Volatile private var advertisingStartedAt = 0L

    // 광고 시작 실패 상태. 성공하면 모두 초기화된다.
    @Volatile private var advertiseErrorCode = 0          // 마지막 실패 코드(없으면 0)
    @Volatile private var advertiseFailureStreak = 0
    @Volatile private var refusedAtLinkCount = -1          // 칩이 거부했을 때의 링크 수(거부 상태가 아니면 -1)
    @Volatile private var lastAdvertiseAttemptAt = 0L

    /** 마지막 광고 시작 실패 코드(정상이면 0) — 자가진단 표시용. */
    fun advertiseErrorCode(): Int = if (advertising) 0 else advertiseErrorCode

    /**
     * 안드로이드가 정의한 실패 코드(1~5)가 아니면 블루투스 칩이 명령을 직접 거부한 것이다(HCI 상태값이
     * 그대로 올라옴). 대표적으로 12(0x0C, Command Disallowed) — 동시에 연결할 수 있는 수가 찼을 때.
     */
    private fun isChipRefusal(code: Int) = code !in 1..5
    private var healthJob: Job? = null

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
            DiagLog.log("BLE노출", "다른 태블릿이 이 태블릿을 찾을 수 있게 하지 못함: " + if (bluetoothAdapter?.isEnabled != true) "블루투스 꺼짐" else "이 기기가 지원하지 않음")
            return false
        }
        isActive = true
        scope.launch {
            setupGattServer()
            delay(300)
            startAdvertising()
        }
        // 연결이 하나도 없는 채로 오래 있으면 광고를 새로 시작한다 — 일부 기기에서 광고가 오류 콜백
        // 없이 멈추는 경우가 있어, 그러면 상대가 이 기기를 다시 찾지 못한 채 연결 0대로 남는다.
        healthJob?.cancel()
        healthJob = scope.launch {
            while (true) {
                delay(BleMeshConstants.SCAN_HEALTH_CHECK_INTERVAL_MS)
                if (!isActive) break
                val links = connectionManager.count()
                val alone = links == 0
                val now = System.currentTimeMillis()
                val age = now - advertisingStartedAt
                if (advertising && alone && age >= BleMeshConstants.ADVERTISE_REFRESH_WHEN_ALONE_MS) {
                    stopAdvertising()
                    advertising = false
                    delay(500)
                    startAdvertising()
                } else if (!advertising && refusedAtLinkCount >= 0) {
                    // 칩이 거부했던 광고: 연결이 그때보다 줄었으면(자리가 났으면) 바로, 아니면 드물게만 다시 시도.
                    val wait = if (refusedAtLinkCount == 0) BleMeshConstants.ADVERTISE_REFUSED_RETRY_IDLE_MS
                               else BleMeshConstants.ADVERTISE_REFUSED_RETRY_MS
                    if (links < refusedAtLinkCount || now - lastAdvertiseAttemptAt >= wait) startAdvertising()
                }
            }
        }
        return true
    }

    @Suppress("MissingPermission")
    fun stop() {
        isActive = false
        advertising = false
        advertiseErrorCode = 0
        advertiseFailureStreak = 0
        refusedAtLinkCount = -1
        healthJob?.cancel()
        healthJob = null
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
                            DiagLog.log("BLE연결", "최대 연결 수(${BleMeshConstants.MAX_CONNECTIONS}대) 초과로 …${device.address.takeLast(5)} 연결 거부")
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

    /** 상대가 우리 GATT 서버에 걸어온 링크를 끊는다(등록된 간호사에게 연결 자리를 내주기 위해). */
    @Suppress("MissingPermission")
    fun disconnectDevice(address: String) {
        val device = connectionManager.get(address)?.takeIf { !it.isClient }?.device ?: return
        try { gattServer?.cancelConnection(device) } catch (_: Exception) {}
    }

    fun getGattServer(): BluetoothGattServer? = gattServer
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic

    @Suppress("MissingPermission", "DEPRECATION")
    private fun startAdvertising() {
        if (!isActive || !BlePermissions.hasAll(context)) return
        if (bluetoothAdapter == null || advertiser == null) return
        if (bluetoothAdapter.isMultipleAdvertisementSupported != true) {
            Log.w(TAG, "Multiple advertisement not supported on this device")
            DiagLog.log("BLE노출", "이 기기가 지원하지 않아 다른 태블릿이 이 태블릿을 먼저 찾을 수 없음 — 이 태블릿이 상대를 찾아가 연결할 때만 통신 가능")
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
                advertisingStartedAt = System.currentTimeMillis()
                advertiseErrorCode = 0
                advertiseFailureStreak = 0
                refusedAtLinkCount = -1
                Log.i(TAG, "Advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
                advertising = false
                advertiseErrorCode = errorCode
                val streak = ++advertiseFailureStreak
                val links = connectionManager.all()
                val outgoing = links.count { it.isClient }
                // 같은 실패가 반복되므로 처음과 이후 10회마다만 기록한다. 그때의 연결 상태를 같이 남긴다 —
                // "몇 대 연결된 상태에서 거부됐는지"가 이 기기 칩의 한도를 알려준다.
                if (streak == 1 || streak % 10 == 0) {
                    DiagLog.log("BLE노출", "다른 태블릿이 이 태블릿을 찾을 수 있게 하기 실패 ${streak}회 연속: 오류코드 $errorCode (${advertiseErrorText(errorCode)}) — 현재 연결 ${links.size}대(이 태블릿이 건 것 $outgoing, 걸려온 것 ${links.size - outgoing})")
                }
                if (isChipRefusal(errorCode)) {
                    // 칩이 지금 상태에서는 안 된다고 한 것 — 몇 초마다 다시 넣지 않는다(상태 점검 루프가
                    // 자리가 나거나 한참 지난 뒤에 다시 시도). 이미 연결된 기기들과의 통신은 그대로 된다.
                    refusedAtLinkCount = links.size
                } else if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED &&
                    errorCode != ADVERTISE_FAILED_DATA_TOO_LARGE &&
                    errorCode != ADVERTISE_FAILED_FEATURE_UNSUPPORTED
                ) {
                    scheduleAdvertiseRestart()
                }
            }
        }

        try {
            lastAdvertiseAttemptAt = System.currentTimeMillis()
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
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "알림 데이터가 너무 큼"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "다른 앱이 블루투스 자원을 다 씀, 재시도함"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "이미 시작됨"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "블루투스 내부 오류, 재시도함"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "기기가 지원하지 않음"
        12 -> "블루투스 칩이 지금 상태에서는 허용하지 않음 — 동시에 연결할 수 있는 기기 수가 찼을 때 주로 발생"
        else -> "블루투스 칩이 거부함"
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
