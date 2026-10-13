package io.voyager.samsungbarunhospital.network.ble

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
import io.voyager.samsungbarunhospital.data.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * monitoring and duty-cycling — kept MTU negotiation and backoff retry on scan failure.
 *
 * Self-healing (added after a field report of "works after boot, then 0 connections until
 * reboot"): the scan is renewed before Android's 30-minute scan limit ([renewScan]) and right when
 * a link is lost ([onLinkLost]) — a scan left running forever silently stops delivering results,
 * which made a dropped link unrecoverable; connection attempts back off per address; and a peer that is already linked
 * (in either direction, or under an older advertising address) isn't connected again.
 *
 * Who to connect to is decided by [BleConnectPolicy]: registered nurses always get a slot first;
 * other tablets are still linked (they relay toward the nurse when it's out of direct range).
 */
class BleGattClientManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connectionManager: BleConnectionManager,
    private val delegate: BleTransportDelegate,
    /** 이 기기에 등록된 간호사 태블릿들의 peerId(hex) — 연결 자리를 먼저 확보할 대상. */
    private val registeredNurses: () -> Set<String> = { emptySet() },
    /** 상대가 우리 서버에 걸어온 링크를 끊어달라는 요청(주소). */
    private val evictServerLink: (String) -> Unit = {}
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
    @Volatile private var scanStartedAt = 0L
    private var renewJob: Job? = null
    private var linkLostRenewJob: Job? = null

    // 연속 스캔 실패 횟수. startScan()이 예외 없이 반환해도 실패는 나중에 onScanFailed로 오므로,
    // 거기서 0으로 되돌리면 안 된다(그러면 재시도 간격이 영영 안 늘어나 3초마다 반복되고, Android의
    // "30초에 5회" 제한에 걸려 오히려 복구가 늦어진다). 스캔 결과가 실제로 오거나 스캔이 한동안
    // 살아있을 때만 0으로 되돌린다.
    @Volatile private var scanFailureStreak = 0

    fun scanFailureStreak(): Int = scanFailureStreak

    private class ConnectBackoff(val failures: Int, val retryAt: Long)
    private val connectBackoff = java.util.concurrent.ConcurrentHashMap<String, ConnectBackoff>() // key = address

    // 연결을 시도 중인 주소 → 그 기기의 peerId(광고의 service data에서 읽음). 연결이 성립한 뒤의
    // "누구와 연결돼 있나"는 BleConnectionManager.isPeerLinked가 판단한다. Android는 광고 주소를
    // 주기적으로 바꾸므로 주소만 보면 이미 연결된 같은 태블릿에 새 주소로 또 연결하게 된다.
    private val clientPeerIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    // connectGatt()를 호출했지만 아직 connectionManager에 등록되지 않은(MTU 협상 중인) 주소.
    // 이게 없으면 같은 기기의 반복되는 스캔 결과가 그 창(window) 동안 connectToDevice()를
    // 중복 호출해서 BluetoothGatt 핸들이 leak되고 결국 GATT 슬롯이 고갈된다.
    private val pendingConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // connectGatt()로 연 모든 핸들(연결 중·연결됨 모두). stop() 때 직접 close()하기 위함 — 블루투스가
    // 꺼지는 상황에서는 STATE_DISCONNECTED 콜백(평소 close() 지점)이 안 올 수 있어서, 그대로 두면
    // 프로세스당 몇 개뿐인 GATT 클라이언트 슬롯이 블루투스를 껐다 켤 때마다 새어 결국 연결이 안 된다.
    private val openGatts = java.util.concurrent.ConcurrentHashMap<String, BluetoothGatt>()

    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (isActive) return true
        if (!BlePermissions.hasAll(context)) {
            Log.e(TAG, "Missing permissions, not starting GATT client")
            return false
        }
        if (bluetoothAdapter?.isEnabled != true || scanner == null) {
            Log.e(TAG, "Bluetooth disabled or scanner unavailable")
            DiagLog.log("BLE검색", "검색 시작 불가: " + if (bluetoothAdapter?.isEnabled != true) "블루투스 꺼짐" else "스캐너 없음")
            return false
        }
        isActive = true
        startScanning()
        renewJob?.cancel()
        renewJob = scope.launch {
            while (true) {
                delay(BleMeshConstants.SCAN_RENEW_INTERVAL_MS)
                if (!this@BleGattClientManager.isActive) break
                renewScan()
            }
        }
        return true
    }

    @Suppress("MissingPermission")
    fun stop() {
        isActive = false
        renewJob?.cancel()
        renewJob = null
        linkLostRenewJob?.cancel()
        linkLostRenewJob = null
        stopScanning()
        pendingConnections.clear()
        clientPeerIds.clear()
        connectBackoff.clear()
        for (gatt in openGatts.values) {
            try { gatt.disconnect() } catch (_: Exception) {}
            try { gatt.close() } catch (_: Exception) {}
        }
        openGatts.clear()
    }

    /**
     * 스캔을 새로 시작한다. 이유는 [BleMeshConstants.SCAN_RENEW_INTERVAL_MS] 주석 참고 — 한 번 시작하고
     * 내버려둔 스캔은 30분 뒤부터 결과를 주지 않아, 끊긴 상대를 재부팅 전까지 못 찾는다.
     */
    private suspend fun renewScan() {
        if (isScanning) {
            // 실패는 startScan 직후 onScanFailed로 오므로, 여기까지 살아있었으면 정상 동작으로 본다.
            if (System.currentTimeMillis() - scanStartedAt >= 30_000L) scanFailureStreak = 0
            stopScanning()
            delay(1000)
        }
        startScanning()
    }

    /**
     * 연결이 끊겼을 때. 그때가 상대를 다시 찾아야 하는 순간이므로, 돌던 스캔이 오래된 것이면 새로 시작한다
     * (방금 시작한 스캔이면 그대로 둔다 — startScan은 30초에 5회 제한이 있다).
     */
    fun onLinkLost() {
        if (!isActive) return
        if (isScanning && System.currentTimeMillis() - scanStartedAt < BleMeshConstants.SCAN_RENEW_ON_LINK_LOST_MIN_AGE_MS) return
        linkLostRenewJob?.cancel()
        linkLostRenewJob = scope.launch {
            delay(1000) // 여러 링크가 한꺼번에 끊길 때 한 번으로 합친다
            if (this@BleGattClientManager.isActive) renewScan()
        }
    }

    @Synchronized
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
                val streak = ++scanFailureStreak
                // 같은 실패가 몇 초마다 반복되므로 처음과 이후 10회마다만 기록한다.
                if (errorCode == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) {
                    // 기기가 아예 지원하지 않는 경우 — 재시도해도 같으므로 한 번만 기록하고 멈춘다.
                    if (streak == 1) DiagLog.log("BLE검색", "주변 기기 검색 불가: 이 기기가 지원하지 않음 (오류코드 $errorCode)")
                    return
                }
                if (streak == 1 || streak % 10 == 0) {
                    DiagLog.log("BLE검색", "주변 기기 검색 실패 ${streak}회 연속: 오류코드 $errorCode (${scanErrorText(errorCode)}), 간격을 늘려가며 재시도")
                }
                scheduleScanRestart()
            }
        }

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            scanStartedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
            isScanning = false
            val streak = ++scanFailureStreak
            if (streak == 1 || streak % 10 == 0) {
                DiagLog.log("BLE검색", "검색 시작 중 오류 ${streak}회 연속: ${e.message}")
            }
            scheduleScanRestart()
        }
    }

    private fun scanErrorText(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "이미 검색 중"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "블루투스 내부 등록 실패 — 계속되면 블루투스를 껐다 켜야 함"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "블루투스 내부 오류"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "기기가 지원하지 않음"
        5 -> "블루투스 자원 부족"
        6 -> "검색을 너무 자주 시작함"
        else -> "알 수 없음"
    }

    @Synchronized
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
        val delayMs = (BleMeshConstants.SCAN_RESTART_BACKOFF_BASE_MS * scanFailureStreak.coerceAtLeast(1))
            .coerceAtMost(BleMeshConstants.SCAN_RESTART_BACKOFF_MAX_MS)
        scope.launch {
            delay(delayMs)
            if (isActive && !isScanning) startScanning()
        }
    }

    @Suppress("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        scanFailureStreak = 0
        val device = result.device
        val address = device.address
        if (connectionManager.isDeviceConnected(address)) return
        if (pendingConnections.contains(address)) return
        val now = System.currentTimeMillis()
        val backoff = connectBackoff[address]
        if (backoff != null && now < backoff.retryAt) return

        // 광고의 scan response에 실린 상대 peerId(BleGattServerManager.startAdvertising 참고).
        // 못 읽으면 null — 그때는 연결한 뒤 직접 도착한 ANNOUNCE로 알게 된다(BleMeshManager).
        val peerIdHex = result.scanRecord
            ?.getServiceData(ParcelUuid(BleMeshConstants.SERVICE_UUID))
            ?.takeIf { it.size == BleMeshConstants.PEER_ID_SIZE }
            ?.toHex()
        // 이미 주고받을 수 있는 링크가 있는 상대(상대가 걸어온 링크 포함)거나 연결 시도 중이면 또 걸지 않는다.
        if (peerIdHex != null &&
            (connectionManager.isPeerLinked(peerIdHex) || clientPeerIds.containsValue(peerIdHex))
        ) return

        val nurses = registeredNurses()
        val evictable = findEvictableLink(nurses)
        val decision = BleConnectPolicy.decide(
            isRegisteredNurse = peerIdHex != null && peerIdHex in nurses,
            linkCount = connectionManager.count() + pendingConnections.size,
            maxLinks = connectionManager.maxLinks,
            unlinkedNurseCount = nurses.count { it != peerIdHex && !connectionManager.isPeerLinked(it) && !clientPeerIds.containsValue(it) },
            hasEvictable = evictable != null
        )
        when (decision) {
            BleConnectPolicy.Decision.SKIP -> return
            BleConnectPolicy.Decision.CONNECT_AFTER_EVICTING -> evictable?.let { evict(it) }
            BleConnectPolicy.Decision.CONNECT -> {}
        }
        connectToDevice(device, peerIdHex)
    }

    /** 등록된 간호사에게 자리를 내주기 위해 끊어도 되는 링크 — 간호사가 아닌 것이 확인된 링크 우선, 오래된 순. */
    private fun findEvictableLink(nurses: Set<String>): BleConnection? {
        val others = connectionManager.all().filter { it.peerIdHex == null || it.peerIdHex !in nurses }
        return others.filter { it.peerIdHex != null }.minByOrNull { it.connectedAt }
            ?: others.minByOrNull { it.connectedAt }
    }

    @Suppress("MissingPermission")
    private fun evict(conn: BleConnection) {
        if (conn.isClient) {
            try { conn.gatt?.disconnect() } catch (_: Exception) {}
        } else {
            evictServerLink(conn.address)
        }
    }

    /** 우리가 건 [address] 링크를 끊는다(같은 상대와의 중복 링크 정리용). */
    @Suppress("MissingPermission")
    fun disconnect(address: String) {
        try { openGatts[address]?.disconnect() } catch (_: Exception) {}
    }

    /** 연결이 성립(서비스 탐색 완료)하기 전에 끝난 시도를 실패로 세고, 다음 재시도 시각을 늦춘다. */
    private fun noteConnectFailure(address: String, status: Int, isNurse: Boolean) {
        if (connectBackoff.size > 200) connectBackoff.clear() // 광고 주소가 계속 바뀌므로 무한정 쌓이지 않게
        val failures = (connectBackoff[address]?.failures ?: 0) + 1
        val maxMs = if (isNurse) BleMeshConstants.CONNECT_RETRY_BACKOFF_MAX_NURSE_MS else BleMeshConstants.CONNECT_RETRY_BACKOFF_MAX_MS
        val delayMs = (BleMeshConstants.CONNECT_RETRY_BACKOFF_BASE_MS shl (failures - 1).coerceAtMost(4))
            .coerceAtMost(maxMs)
        connectBackoff[address] = ConnectBackoff(failures, System.currentTimeMillis() + delayMs)
        val links = connectionManager.count()
        if (failures == 1 || failures % 10 == 0) {
            DiagLog.log("BLE연결", "…${address.takeLast(5)} 연결 실패 ${failures}회 연속: 오류코드 $status (${BleStatusText.gattStatus(status)}) — 현재 연결 ${links}대, ${delayMs / 1000}초 뒤 재시도")
        }
        if (status == BleStatusText.HCI_CONNECTION_LIMIT_EXCEEDED &&
            connectionManager.markSaturated(links, explicitLimit = true)
        ) {
            DiagLog.log("BLE연결", "이 태블릿의 블루투스 동시 연결 한도를 ${links}대로 판단 — 연결이 하나 줄 때까지 더 연결하지 않음")
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice, peerIdHex: String?) {
        val gattCallback = object : BluetoothGattCallback() {
            // 서비스 탐색까지 끝나 실제로 주고받을 수 있게 된 적이 있는지 — 실패 횟수 집계용.
            @Volatile var established = false

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
                } else {
                    // 끊김, 또는 "연결됨"인데 오류 status인 경우 — 후자를 그냥 두면 핸들이 영구히 열린 채
                    // pendingConnections에 남아 그 기기로 다시는 연결을 시도하지 않는다.
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try { gatt.disconnect() } catch (_: Exception) {}
                    }
                    pendingConnections.remove(device.address)
                    openGatts.remove(device.address, gatt)
                    clientPeerIds.remove(device.address)
                    if (established) {
                        connectBackoff.remove(device.address)
                    } else {
                        noteConnectFailure(device.address, status, isNurse = peerIdHex != null && peerIdHex in registeredNurses())
                    }
                    // 이 콜백의 링크가 등록해둔 항목일 때만 지운다. 같은 주소로 상대가 걸어온 서버 링크나,
                    // 재시작 뒤 새로 맺은 링크를 뒤늦게 온 옛 콜백이 지우면 그 링크로 더는 못 보낸다.
                    // (bitchat-android의 linkID 세대 확인과 같은 목적)
                    if (connectionManager.get(device.address)?.gatt === gatt) {
                        connectionManager.remove(device.address)
                    }
                    // 실제로 맺어졌던 링크가 끊긴 경우만 "연결 끊김" 사건으로 알린다 — 그 사건이 스캔·광고를 새로
                    // 시작하는 계기라서, 실패한 연결 시도까지 알리면 시도할 때마다 그것들이 다시 시작된다.
                    if (established) delegate.onDeviceDisconnected(device.address)
                    scope.launch {
                        delay(200)
                        try { gatt.close() } catch (_: Exception) {}
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingConnections.remove(device.address)
                    connectionManager.add(BleConnection(device.address, device, isClient = true, gatt = gatt, peerIdHex = peerIdHex))
                    try {
                        gatt.discoverServices()
                    } catch (e: Exception) {
                        Log.w(TAG, "discoverServices failed: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "MTU negotiation failed for ${device.address}")
                DiagLog.log("BLE연결", "…${device.address.takeLast(5)} 연결 설정(MTU) 실패, 연결 해제")
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    DiagLog.log("BLE연결", "…${device.address.takeLast(5)} 서비스 탐색 실패(status=$status), 연결 해제")
                    gatt.disconnect()
                    return
                }
                val characteristic = gatt.getService(BleMeshConstants.SERVICE_UUID)
                    ?.getCharacteristic(BleMeshConstants.CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.e(TAG, "Required characteristic not found for ${device.address}")
                    DiagLog.log("BLE연결", "…${device.address.takeLast(5)} 통신 채널 없음(구버전 앱?), 연결 해제")
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
                established = true
                connectBackoff.remove(device.address)

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

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                // Signals "this link's outstanding write is done" regardless of status — a
                // failed write shouldn't leave BleConnectionManager waiting forever, the caller
                // just won't get delivery confirmation for this particular fragment.
                connectionManager.onWriteAcked(device.address)
            }
        }

        pendingConnections.add(device.address)
        if (peerIdHex != null) clientPeerIds[device.address] = peerIdHex
        try {
            // TRANSPORT_LE 고정: 지정하지 않으면 일부 기기가 일반 블루투스(BR/EDR)로 연결을 시도하다
            // status 133으로 실패한다.
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                pendingConnections.remove(device.address)
                clientPeerIds.remove(device.address)
            } else if (!isActive) {
                // stop()과 경합 — 이미 정리가 끝났으면 방금 연 핸들도 바로 닫는다.
                pendingConnections.remove(device.address)
                clientPeerIds.remove(device.address)
                try { gatt.close() } catch (_: Exception) {}
            } else {
                openGatts[device.address] = gatt
            }
        } catch (e: Exception) {
            pendingConnections.remove(device.address)
            clientPeerIds.remove(device.address)
            Log.e(TAG, "Exception connecting to ${device.address}: ${e.message}")
            DiagLog.log("BLE연결", "…${device.address.takeLast(5)} 연결 시도 오류: ${e.message}")
        }
    }
}
