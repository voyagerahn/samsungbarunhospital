package io.falab.samsungbarunhospital.network.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleStoreForwardCacheTest {

    private fun peerId(fill: Int) = ByteArray(BleMeshConstants.PEER_ID_SIZE) { fill.toByte() }
    private fun msgId(fill: Int) = ByteArray(BleMeshConstants.MESSAGE_ID_SIZE) { fill.toByte() }

    private fun confirmed(recipient: ByteArray, tag: Int) = BlePacket(
        type = BlePacketType.CONFIRMED,
        ttl = 1,
        senderPeerId = peerId(9),
        recipientPeerId = recipient,
        messageId = msgId(tag),
        payload = "CONFIRMED|room|msg".toByteArray()
    )

    @Test
    fun `caches unicast CONFIRMED and flushes it when the peer comes online`() {
        val cache = BleStoreForwardCache()
        val patient = peerId(1)

        cache.offer(confirmed(patient, tag = 1))
        assertEquals(1, cache.pendingCountFor(patient.toHex()))

        val flushed = cache.flushFor(patient.toHex())
        assertEquals(1, flushed.size)
        assertEquals(0, cache.pendingCountFor(patient.toHex())) // flush drains the queue
    }

    @Test
    fun `ignores broadcast and non-CONFIRMED packets`() {
        val cache = BleStoreForwardCache()

        cache.offer(
            BlePacket(
                type = BlePacketType.CALL, ttl = 5, senderPeerId = peerId(1),
                recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT, messageId = msgId(1), payload = ByteArray(0)
            )
        )
        cache.offer(confirmed(BleMeshConstants.BROADCAST_RECIPIENT, tag = 2)) // CONFIRMED but broadcast recipient

        assertTrue(cache.flushFor(BleMeshConstants.BROADCAST_RECIPIENT.toHex()).isEmpty())
    }

    @Test
    fun `entries expire after the store-forward timeout`() {
        var now = 0L
        val cache = BleStoreForwardCache(timeoutMs = 1000L, clock = { now })
        val patient = peerId(2)

        cache.offer(confirmed(patient, tag = 1))
        now += 2000

        assertEquals(0, cache.pendingCountFor(patient.toHex()))
        assertTrue(cache.flushFor(patient.toHex()).isEmpty())
    }

    @Test
    fun `evicts oldest entry once per-peer capacity is exceeded`() {
        val cache = BleStoreForwardCache(maxPerPeer = 2)
        val patient = peerId(3)

        cache.offer(confirmed(patient, tag = 1))
        cache.offer(confirmed(patient, tag = 2))
        cache.offer(confirmed(patient, tag = 3)) // evicts tag=1

        val flushed = cache.flushFor(patient.toHex())
        assertEquals(2, flushed.size)
        assertEquals(msgId(2).toHex(), flushed[0].messageId.toHex())
        assertEquals(msgId(3).toHex(), flushed[1].messageId.toHex())
    }
}
