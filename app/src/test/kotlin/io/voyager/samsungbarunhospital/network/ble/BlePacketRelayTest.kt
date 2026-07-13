package io.voyager.samsungbarunhospital.network.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BlePacketRelayTest {

    private fun peerId(fill: Int) = ByteArray(BleMeshConstants.PEER_ID_SIZE) { fill.toByte() }
    private fun msgId(fill: Int) = ByteArray(BleMeshConstants.MESSAGE_ID_SIZE) { fill.toByte() }

    private val me = peerId(1)
    private val someoneElse = peerId(2)
    private val anotherPeer = peerId(3)

    private fun packet(sender: ByteArray, recipient: ByteArray, ttl: Byte) = BlePacket(
        type = BlePacketType.CALL,
        ttl = ttl,
        senderPeerId = sender,
        recipientPeerId = recipient,
        messageId = msgId(7),
        payload = "hi".toByteArray()
    )

    @Test
    fun `drops packet when ttl is zero`() {
        val p = packet(someoneElse, BleMeshConstants.BROADCAST_RECIPIENT, ttl = 0)
        assertNull(BlePacketRelay.decideRelay(p, me))
    }

    @Test
    fun `decrements ttl for a broadcast packet from someone else`() {
        val p = packet(someoneElse, BleMeshConstants.BROADCAST_RECIPIENT, ttl = 5)
        val relayed = BlePacketRelay.decideRelay(p, me)
        assertNotNull(relayed)
        assertEquals(4.toByte(), relayed!!.ttl)
    }

    @Test
    fun `does not relay our own packet`() {
        val p = packet(me, BleMeshConstants.BROADCAST_RECIPIENT, ttl = 5)
        assertNull(BlePacketRelay.decideRelay(p, me))
    }

    @Test
    fun `does not relay a unicast packet addressed to us`() {
        val p = packet(someoneElse, me, ttl = 5)
        assertNull(BlePacketRelay.decideRelay(p, me))
    }

    @Test
    fun `relays a unicast packet addressed to someone else`() {
        val p = packet(someoneElse, anotherPeer, ttl = 5)
        val relayed = BlePacketRelay.decideRelay(p, me)
        assertNotNull(relayed)
        assertEquals(4.toByte(), relayed!!.ttl)
    }
}
