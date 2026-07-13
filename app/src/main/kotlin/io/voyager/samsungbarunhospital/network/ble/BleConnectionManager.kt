package io.voyager.samsungbarunhospital.network.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.util.Log
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
    var gatt: BluetoothGatt? = null,
    var writeCharacteristic: BluetoothGattCharacteristic? = null,
    var subscribed: Boolean = false,
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
    }

    private val connections = ConcurrentHashMap<String, BleConnection>() // key = device address

    fun add(conn: BleConnection) {
        connections[conn.address] = conn
    }

    fun remove(address: String): BleConnection? = connections.remove(address)

    fun get(address: String): BleConnection? = connections[address]

    fun all(): List<BleConnection> = connections.values.toList()

    fun count(): Int = connections.size

    fun isFull(): Boolean = connections.size >= BleMeshConstants.MAX_CONNECTIONS

    fun isDeviceConnected(address: String): Boolean = connections.containsKey(address)

    fun clear() = connections.clear()

    /** Sends [bytes] to every connection except [excludeAddress] (the one the packet arrived on, if any). */
    fun broadcast(bytes: ByteArray, gattServer: BluetoothGattServer?, serverCharacteristic: BluetoothGattCharacteristic?, excludeAddress: String? = null) {
        for (conn in connections.values) {
            if (conn.address == excludeAddress) continue
            try {
                if (conn.isClient) {
                    val gatt = conn.gatt ?: continue
                    val characteristic = conn.writeCharacteristic ?: continue
                    writeToCharacteristic(gatt, characteristic, bytes)
                } else if (conn.subscribed && gattServer != null && serverCharacteristic != null) {
                    notifyDevice(gattServer, conn.device, serverCharacteristic, bytes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to ${conn.address}: ${e.message}")
            }
        }
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
