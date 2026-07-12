package io.falab.samsungbarunhospital.network.ble

/**
 * Relay decision logic, adapted from bitchat-android's PacketRelayManager
 * (app/src/main/java/com/bitchat/android/mesh/PacketRelayManager.kt). Source-routing and the
 * network-size-based probabilistic relay are dropped: a hospital ward mesh is small enough
 * that plain TTL-bounded flooding is sufficient, and skipping probability keeps relay
 * behavior deterministic and easy to test.
 *
 * Loop prevention is TTL (bounds how many hops a packet can travel) combined with
 * [BleDedupCache] (stops a node from re-relaying a copy it already flooded).
 */
object BlePacketRelay {

    /**
     * @return a TTL-decremented copy to re-broadcast to all other connections, or null if this
     * node should not relay [packet] further (it's ours, it's addressed to us, or TTL is exhausted).
     */
    fun decideRelay(packet: BlePacket, myPeerId: ByteArray): BlePacket? {
        if (packet.senderPeerId.contentEquals(myPeerId)) return null
        if (!packet.isBroadcast && packet.recipientPeerId.contentEquals(myPeerId)) return null
        if (packet.ttl <= 0) return null
        return packet.copy(ttl = (packet.ttl - 1).toByte())
    }
}
