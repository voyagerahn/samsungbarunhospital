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
    val connectedAt: Long = System.currentTimeMillis()
)

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
