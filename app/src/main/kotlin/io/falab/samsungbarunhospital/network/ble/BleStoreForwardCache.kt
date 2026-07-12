package io.falab.samsungbarunhospital.network.ble

/**
 * Caches undeliverable unicast packets for a peer that's currently unreachable, and hands them
 * back once that peer is seen online again. Adapted from bitchat-android's StoreForwardManager
 * (app/src/main/java/com/bitchat/android/mesh/StoreForwardManager.kt), narrowed to this app's
 * only real unicast: CONFIRMED (nurse -> patient acknowledgement). CALL/CANCEL are broadcast
 * by nature and don't need per-recipient caching.
 *
 * Timeout matches CallQueue.EXPIRE_MS (30 minutes) rather than bitchat's 12h — a CONFIRMED
 * reply that old is no longer meaningful to the patient tablet's banner.
 */
class BleStoreForwardCache(
    private val timeoutMs: Long = BleMeshConstants.STORE_FORWARD_TIMEOUT_MS,
    private val maxPerPeer: Int = BleMeshConstants.STORE_FORWARD_MAX_PER_PEER,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val packet: BlePacket, val cachedAt: Long)

    private val queues = HashMap<String, ArrayDeque<Entry>>() // key = recipientPeerId hex

    /** Caches [packet] for later delivery. No-ops for anything other than a unicast CONFIRMED. */
    @Synchronized
    fun offer(packet: BlePacket) {
        if (packet.type != BlePacketType.CONFIRMED || packet.isBroadcast) return
        val key = packet.recipientPeerId.toHex()
        val queue = queues.getOrPut(key) { ArrayDeque() }
        queue.addLast(Entry(packet, clock()))
        while (queue.size > maxPerPeer) queue.removeFirst()
    }

    /** Returns and clears all still-fresh packets cached for [peerIdHex] (call when that peer comes online). */
    @Synchronized
    fun flushFor(peerIdHex: String): List<BlePacket> {
        purgeExpired()
        return queues.remove(peerIdHex)?.map { it.packet } ?: emptyList()
    }

    @Synchronized
    fun pendingCountFor(peerIdHex: String): Int {
        purgeExpired()
        return queues[peerIdHex]?.size ?: 0
    }

    private fun purgeExpired() {
        val cutoff = clock() - timeoutMs
        val it = queues.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.removeIf { e -> e.cachedAt < cutoff }
            if (entry.value.isEmpty()) it.remove()
        }
    }
}
