package io.voyager.samsungbarunhospital.network.ble

/** Callbacks from the GATT server/client managers up to [BleMeshManager]. */
interface BleTransportDelegate {
    fun onRawPacketReceived(bytes: ByteArray, fromAddress: String)
    fun onDeviceConnected(address: String)
    fun onDeviceDisconnected(address: String)
}
