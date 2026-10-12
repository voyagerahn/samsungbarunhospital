package io.voyager.samsungbarunhospital.network.ble

/**
 * Caches undeliverable unicast packets for a peer that's currently unreachable, and hands them
 * back once that peer is seen again. Adapted from bitchat-android's StoreForwardManager
 * (app/src/main/java/com/bitchat/android/mesh/StoreForwardManager.kt).
 *
 * Two uses:
 * - CONFIRMED (nurse -> patient acknowledgement) when the patient tablet isn't currently seen.
 *   Default timeout matches CallQueue.EXPIRE_MS (30 minutes) rather than bitchat's 12h — a reply
 *   that old is no longer meaningful to the patient tablet's banner.
 * - CALL/CANCEL that couldn't actually leave this device because it had no BLE link at all
 *   (see BleMeshManager.sendUnicast) — kept for the shorter
 *   [BleMeshConstants.PENDING_CALL_TIMEOUT_MS] and tagged with a [key] so a later CANCEL can
 *   withdraw a CALL that never went out instead of delivering both.
 */
class BleStoreForwardCache(
    private val timeoutMs: Long = BleMeshConstants.STORE_FORWARD_TIMEOUT_MS,
    private val maxPerPeer: Int = BleMeshConstants.STORE_FORWARD_MAX_PER_PEER,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val packet: BlePacket, val expiresAt: Long, val key: String?)

    private val queues = HashMap<String, ArrayDeque<Entry>>() // key = recipientPeerId hex

    /**
     * Caches [packet] for later delivery. No-ops for broadcast packets (nobody specific to wait for).
     *
     * @param ttlMs how long this entry stays deliverable
     * @param key optional tag for [removeByKey]
     */
    @Synchronized
    fun offer(packet: BlePacket, ttlMs: Long = timeoutMs, key: String? = null) {
        if (packet.isBroadcast) return
        val queue = queues.getOrPut(packet.recipientPeerId.toHex()) { ArrayDeque() }
        queue.addLast(Entry(packet, clock() + ttlMs, key))
        while (queue.size > maxPerPeer) queue.removeFirst()
    }

    /** Withdraws still-pending entries tagged [key] for [peerIdHex]. @return true if any was removed */
    @Synchronized
    fun removeByKey(peerIdHex: String, key: String): Boolean {
        purgeExpired()
        val queue = queues[peerIdHex] ?: return false
        val removed = queue.removeAll { it.key == key }
        if (queue.isEmpty()) queues.remove(peerIdHex)
        return removed
    }

    /** Returns and clears all still-fresh packets cached for [peerIdHex] (call when that peer is seen). */
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
        val now = clock()
        val it = queues.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.removeAll { e -> e.expiresAt <= now }
            if (entry.value.isEmpty()) it.remove()
        }
    }
}
