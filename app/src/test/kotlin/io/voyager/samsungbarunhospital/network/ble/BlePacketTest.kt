package io.voyager.samsungbarunhospital.network.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlePacketTest {

    private fun peerId(fill: Int) = ByteArray(BleMeshConstants.PEER_ID_SIZE) { fill.toByte() }
    private fun msgId(fill: Int) = ByteArray(BleMeshConstants.MESSAGE_ID_SIZE) { fill.toByte() }

    @Test
    fun `encode then decode round trips`() {
        val original = BlePacket(
            type = BlePacketType.CALL,
            ttl = 5,
            senderPeerId = peerId(1),
            recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
            messageId = msgId(2),
            payload = "환자101|너무 아파요|abc-123".toByteArray(Charsets.UTF_8)
        )

        val decoded = BlePacket.decode(original.encode())

        assertEquals(original, decoded)
    }

    @Test
    fun `decode handles empty payload`() {
        val original = BlePacket(
            type = BlePacketType.CANCEL,
            ttl = 1,
            senderPeerId = peerId(9),
            recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
            messageId = msgId(9),
            payload = ByteArray(0)
        )

        assertEquals(original, BlePacket.decode(original.encode()))
    }

    @Test
    fun `decode returns null for truncated bytes`() {
        val original = BlePacket(
            type = BlePacketType.CALL,
            ttl = 5,
            senderPeerId = peerId(1),
            recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
            messageId = msgId(2),
            payload = "hello".toByteArray()
        )
        val truncated = original.encode().copyOf(original.encode().size - 3)

        assertNull(BlePacket.decode(truncated))
    }

    @Test
    fun `decode returns null for too-short buffer`() {
        assertNull(BlePacket.decode(ByteArray(5)))
    }

    @Test
    fun `decode returns null for unknown type byte`() {
        val original = BlePacket(
            type = BlePacketType.CALL,
            ttl = 5,
            senderPeerId = peerId(1),
            recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
            messageId = msgId(2),
            payload = "hello".toByteArray()
        )
        val corrupted = original.encode()
        corrupted[1] = 0x7F // not a valid BlePacketType value

        assertNull(BlePacket.decode(corrupted))
    }

    @Test
    fun `decode returns null when declared payload length exceeds available bytes`() {
        val original = BlePacket(
            type = BlePacketType.CALL,
            ttl = 5,
            senderPeerId = peerId(1),
            recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
            messageId = msgId(2),
            payload = "hi".toByteArray()
        )
        val bytes = original.encode()
        // payloadLen field is the 2 bytes right before the payload itself
        val payloadLenOffset = BlePacket.HEADER_SIZE - 2
        bytes[payloadLenOffset] = 0x7F
        bytes[payloadLenOffset + 1] = 0xFF.toByte()

        assertNull(BlePacket.decode(bytes))
    }

    @Test
    fun `isBroadcast is true only for the broadcast recipient constant`() {
        val broadcast = BlePacket(
            type = BlePacketType.CALL, ttl = 1, senderPeerId = peerId(1),
            recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT, messageId = msgId(1), payload = ByteArray(0)
        )
        val unicast = BlePacket(
            type = BlePacketType.CONFIRMED, ttl = 1, senderPeerId = peerId(1),
            recipientPeerId = peerId(2), messageId = msgId(1), payload = ByteArray(0)
        )

        assert(broadcast.isBroadcast)
        assert(!unicast.isBroadcast)
    }
}
