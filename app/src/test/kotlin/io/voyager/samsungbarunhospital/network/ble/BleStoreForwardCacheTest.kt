package io.voyager.samsungbarunhospital.network.ble

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

    private fun call(recipient: ByteArray, tag: Int) = BlePacket(
        type = BlePacketType.CALL,
        ttl = 5,
        senderPeerId = peerId(9),
        recipientPeerId = recipient,
        messageId = msgId(tag),
        payload = "room|msg|call-$tag".toByteArray()
    )

    @Test
    fun `ignores broadcast packets`() {
        val cache = BleStoreForwardCache()

        cache.offer(confirmed(BleMeshConstants.BROADCAST_RECIPIENT, tag = 2))

        assertTrue(cache.flushFor(BleMeshConstants.BROADCAST_RECIPIENT.toHex()).isEmpty())
    }

    @Test
    fun `caches an undeliverable CALL and flushes it when the nurse is seen again`() {
        val cache = BleStoreForwardCache()
        val nurse = peerId(4)

        cache.offer(call(nurse, tag = 1), ttlMs = 60_000L, key = "CALL:call-1")

        val flushed = cache.flushFor(nurse.toHex())
        assertEquals(1, flushed.size)
        assertEquals(BlePacketType.CALL, flushed[0].type)
        assertTrue(cache.flushFor(nurse.toHex()).isEmpty()) // 한 번만 나간다
    }

    @Test
    fun `a pending CALL uses its own shorter ttl, not the default timeout`() {
        var now = 0L
        val cache = BleStoreForwardCache(timeoutMs = 30 * 60_000L, clock = { now })
        val nurse = peerId(5)

        cache.offer(call(nurse, tag = 1), ttlMs = 10 * 60_000L, key = "CALL:call-1")
        cache.offer(confirmed(nurse, tag = 2)) // 기본 30분

        now += 11 * 60_000L
        val flushed = cache.flushFor(nurse.toHex())

        assertEquals(1, flushed.size)
        assertEquals(BlePacketType.CONFIRMED, flushed[0].type)
    }

    @Test
    fun `cancelling a call that never went out withdraws it instead of delivering it later`() {
        val cache = BleStoreForwardCache()
        val nurse = peerId(6)

        cache.offer(call(nurse, tag = 1), ttlMs = 60_000L, key = "CALL:call-1")
        cache.offer(call(nurse, tag = 2), ttlMs = 60_000L, key = "CALL:call-2")

        assertTrue(cache.removeByKey(nurse.toHex(), "CALL:call-1"))
        assertEquals(false, cache.removeByKey(nurse.toHex(), "CALL:call-1")) // 이미 없음

        val flushed = cache.flushFor(nurse.toHex())
        assertEquals(1, flushed.size)
        assertEquals(msgId(2).toHex(), flushed[0].messageId.toHex())
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
