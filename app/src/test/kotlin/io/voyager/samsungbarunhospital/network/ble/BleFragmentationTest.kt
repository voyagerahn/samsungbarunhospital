package io.voyager.samsungbarunhospital.network.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFragmentationTest {

    private fun peerId(fill: Int) = ByteArray(BleMeshConstants.PEER_ID_SIZE) { fill.toByte() }
    private fun msgId(fill: Int) = ByteArray(BleMeshConstants.MESSAGE_ID_SIZE) { fill.toByte() }

    private fun packet(payload: ByteArray) = BlePacket(
        type = BlePacketType.CALL,
        ttl = 5,
        senderPeerId = peerId(1),
        recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
        messageId = msgId(3),
        payload = payload
    )

    @Test
    fun `small packet is not fragmented`() {
        val p = packet("짧은 호출 메시지".toByteArray())
        assertFalse(BleFragmentation.shouldFragment(p))
        assertEquals(listOf(p), BleFragmentation.fragment(p))
    }

    @Test
    fun `large packet is split into multiple FRAGMENT packets`() {
        val bigPayload = ByteArray(1200) { (it % 256).toByte() }
        val p = packet(bigPayload)

        assertTrue(BleFragmentation.shouldFragment(p))
        val fragments = BleFragmentation.fragment(p)

        assertTrue(fragments.size > 1)
        assertTrue(fragments.all { it.type == BlePacketType.FRAGMENT })
        // Each fragment must carry its own unique dedup id, distinct from the original message id.
        assertEquals(fragments.size, fragments.map { it.messageId.toHex() }.toSet().size)
    }

    @Test
    fun `reassembles fragments received in order`() {
        val bigPayload = ByteArray(1500) { (it % 256).toByte() }
        val original = packet(bigPayload)
        val fragments = BleFragmentation.fragment(original)
        val reassembler = BleFragmentation.Reassembler()

        var result: BlePacket? = null
        for (f in fragments) {
            result = reassembler.handleFragment(f)
        }

        assertNotNull(result)
        assertEquals(BlePacketType.CALL, result!!.type)
        assertArrayEquals(original.messageId, result.messageId)
        assertArrayEquals(bigPayload, result.payload)
        assertEquals(0, reassembler.pendingCount())
    }

    @Test
    fun `reassembles fragments received out of order`() {
        val bigPayload = ByteArray(900) { (it % 256).toByte() }
        val original = packet(bigPayload)
        val fragments = BleFragmentation.fragment(original).shuffled(java.util.Random(42))
        val reassembler = BleFragmentation.Reassembler()

        var result: BlePacket? = null
        for (f in fragments) {
            result = reassembler.handleFragment(f)
        }

        assertNotNull(result)
        assertArrayEquals(bigPayload, result!!.payload)
    }

    @Test
    fun `returns null until all fragments have arrived`() {
        val bigPayload = ByteArray(1000) { (it % 256).toByte() }
        val fragments = BleFragmentation.fragment(packet(bigPayload))
        val reassembler = BleFragmentation.Reassembler()

        for (f in fragments.dropLast(1)) {
            assertNull(reassembler.handleFragment(f))
        }
        assertEquals(1, reassembler.pendingCount())
    }

    @Test
    fun `stale partial fragments are purged after timeout`() {
        var now = 0L
        val fragments = BleFragmentation.fragment(packet(ByteArray(900) { it.toByte() }))
        val reassembler = BleFragmentation.Reassembler(timeoutMs = 1000L, clock = { now })

        reassembler.handleFragment(fragments.first())
        assertEquals(1, reassembler.pendingCount())

        now += 2000
        reassembler.handleFragment(fragments.first()) // triggers purge, then re-buffers this one fragment
        assertEquals(1, reassembler.pendingCount())
    }

    @Test
    fun `malformed fragment payload does not throw and is dropped`() {
        val garbage = BlePacket(
            type = BlePacketType.FRAGMENT,
            ttl = 5,
            senderPeerId = peerId(1),
            recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
            messageId = msgId(3),
            payload = byteArrayOf(1, 2, 3) // far too short to be a valid envelope
        )
        val reassembler = BleFragmentation.Reassembler()

        assertNull(reassembler.handleFragment(garbage))
        assertEquals(0, reassembler.pendingCount())
    }
}
