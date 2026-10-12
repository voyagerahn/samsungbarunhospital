package io.voyager.samsungbarunhospital.network.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * One physical BLE link, either us-as-central (isClient=true, we hold the [gatt] handle and
 * write to [writeCharacteristic]) or us-as-peripheral (isClient=false, the remote device holds
 * the connection to our GATT server and we notify it once [subscribed] is true).
 */
data class BleConnection(
    val address: String,
    val device: BluetoothDevice,
    val isClient: Boolean,
    @Volatile var gatt: BluetoothGatt? = null,
    @Volatile var writeCharacteristic: BluetoothGattCharacteristic? = null,
    @Volatile var subscribed: Boolean = false,
    val connectedAt: Long = System.currentTimeMillis(),
    /**
     * 이 링크 건너편 기기의 peerId. 클라이언트 링크는 스캔한 광고에서, 그 외에는 이 링크로 직접 도착한
     * (중계되지 않은) ANNOUNCE에서 알게 된다. 모르면 null. (bitchat의 addressPeerMap과 같은 역할)
     */
    @Volatile var peerIdHex: String? = null
) {
    /** 지금 이 링크로 실제로 데이터를 내보낼 수 있는지. */
    val isWritable: Boolean
        get() = if (isClient) gatt != null && writeCharacteristic != null else subscribed
}

/**
 * Tracks every live BLE link (both GATT-server and GATT-client side) so the mesh facade can
 * broadcast/relay a packet to "every connection except the one it arrived on" without knowing
 * which role established each link. Simplified from bitchat-android's
 * BluetoothConnectionTracker/BluetoothConnectionManager (RSSI-based eviction, duty cycling and
 * per-role debug toggles are dropped — not needed at ward scale).
 */
class BleConnectionManager {
    companion object {
        private const val TAG = "BleConnectionManager"

        // Android BLE only allows one outstanding write per GATT link. If the next fragment is
        // written before onCharacteristicWrite/onNotificationSent fires for the previous one,
        // the stack can silently drop it (GATT_WRITE_REQUEST_BUSY) or the peer never sees it.
        // This bounds how long we wait for that ack before giving up on this connection for the
        // current send — a dead/unresponsive link shouldn't stall the whole broadcast.
        private const val WRITE_ACK_TIMEOUT_MS = 2000L
    }

    private val connections = ConcurrentHashMap<String, BleConnection>() // key = device address

    // One pending-write completion per address at a time (Android BLE serializes writes per
    // link anyway, so a single slot per address is correct — a second write is never issued
    // for the same address until the first one's ack arrives or times out, see broadcast()).
    private val pendingWriteAcks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Called from BleGattClientManager's onCharacteristicWrite / BleGattServerManager's onNotificationSent. */
    fun onWriteAcked(address: String) {
        pendingWriteAcks.remove(address)?.complete(Unit)
    }

    fun add(conn: BleConnection) {
        connections[conn.address] = conn
    }

    fun remove(address: String): BleConnection? {
        // Connection is gone — any write we were waiting to have acked on it never will be.
        pendingWriteAcks.remove(address)?.complete(Unit)
        return connections.remove(address)
    }

    fun get(address: String): BleConnection? = connections[address]

    fun all(): List<BleConnection> = connections.values.toList()

    fun count(): Int = connections.size

    /**
     * 지금 실제로 데이터를 내보낼 수 있는 링크 수. 연결 직후에는 아직 서비스 탐색(클라이언트)이나
     * 구독(서버)이 안 끝나 [broadcast]가 그 링크를 조용히 건너뛰므로, "보낼 수 있는가"는 [count]가
     * 아니라 이 값으로 판단해야 한다.
     */
    fun writableCount(): Int = connections.values.count { it.isWritable }

    /** [address] 링크의 상대가 [peerIdHex]임을 기록한다(그 링크가 아직 살아있을 때만). */
    fun bindPeer(address: String, peerIdHex: String) {
        connections[address]?.peerIdHex = peerIdHex
    }

    /**
     * [peerIdHex]와 주고받을 수 있는 링크가 이미 있는지 — 방향(우리가 건 것/상대가 건 것)은 따지지 않는다.
     * 쓸 수 있는 링크만 센다: 구독이 안 끝난 서버 링크를 "연결됨"으로 치면, 그 링크가 끝내 준비되지
     * 않을 때 우리 쪽에서 새로 연결하는 것까지 막아버리기 때문.
     */
    fun isPeerLinked(peerIdHex: String): Boolean =
        connections.values.any { it.peerIdHex == peerIdHex && it.isWritable }

    fun isFull(): Boolean = connections.size >= BleMeshConstants.MAX_CONNECTIONS

    fun isDeviceConnected(address: String): Boolean = connections.containsKey(address)

    fun clear() {
        pendingWriteAcks.values.forEach { it.complete(Unit) }
        pendingWriteAcks.clear()
        connections.clear()
    }

    /**
     * Sends [bytes] to every connection except [excludeAddress] (the one the packet arrived on,
     * if any). Waits for each link's previous write to be acked before issuing this one — see
     * [WRITE_ACK_TIMEOUT_MS] — but different connections are written to concurrently, so one
     * slow/dead peer doesn't delay delivery to the others.
     */
    suspend fun broadcast(bytes: ByteArray, gattServer: BluetoothGattServer?, serverCharacteristic: BluetoothGattCharacteristic?, excludeAddress: String? = null) {
        coroutineScope {
            for (conn in connections.values) {
                if (conn.address == excludeAddress) continue
                launch {
                    try {
                        if (conn.isClient) {
                            val gatt = conn.gatt ?: return@launch
                            val characteristic = conn.writeCharacteristic ?: return@launch
                            writeAndAwaitAck(conn.address) { writeToCharacteristic(gatt, characteristic, bytes) }
                        } else if (conn.subscribed && gattServer != null && serverCharacteristic != null) {
                            writeAndAwaitAck(conn.address) { notifyDevice(gattServer, conn.device, serverCharacteristic, bytes) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send to ${conn.address}: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun writeAndAwaitAck(address: String, issueWrite: () -> Unit) {
        // A write already in flight for this address (shouldn't normally happen since callers
        // serialize per-connection via this same mechanism) — wait for it first so we never have
        // two outstanding writes on one link.
        pendingWriteAcks[address]?.let { withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { it.await() } }
        val deferred = CompletableDeferred<Unit>()
        pendingWriteAcks[address] = deferred
        issueWrite()
        withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { deferred.await() }
        pendingWriteAcks.remove(address)
    }

    /** Sends [bytes] to a single connection identified by device address, if currently connected. */
    fun sendTo(address: String, bytes: ByteArray, gattServer: BluetoothGattServer?, serverCharacteristic: BluetoothGattCharacteristic?): Boolean {
        val conn = connections[address] ?: return false
        return try {
            if (conn.isClient) {
                val gatt = conn.gatt ?: return false
                val characteristic = conn.writeCharacteristic ?: return false
                writeToCharacteristic(gatt, characteristic, bytes)
                true
            } else if (conn.subscribed && gattServer != null && serverCharacteristic != null) {
                notifyDevice(gattServer, conn.device, serverCharacteristic, bytes)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send to $address: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun writeToCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = bytes
        gatt.writeCharacteristic(characteristic)
    }

    @Suppress("DEPRECATION")
    private fun notifyDevice(gattServer: BluetoothGattServer, device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        characteristic.value = bytes
        gattServer.notifyCharacteristicChanged(device, characteristic, false)
    }
}
