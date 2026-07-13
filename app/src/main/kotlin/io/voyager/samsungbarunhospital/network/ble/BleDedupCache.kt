package io.voyager.samsungbarunhospital.network.ble

/**
 * Tracks recently-seen message IDs so a flooded packet isn't processed/relayed twice by the
 * same node. Adapted from the duplicate-detection half of bitchat-android's SecurityManager
 * (app/src/main/java/com/bitchat/android/mesh/SecurityManager.kt) — signature verification and
 * replay-attack handling are dropped since this transport carries no cryptographic identity.
 *
 * TTL + this cache together prevent relay loops: TTL bounds how far a packet can travel,
 * this cache stops a node from re-relaying a copy it already flooded moments earlier.
 */
class BleDedupCache(
    private val windowMs: Long = BleMeshConstants.DEDUP_WINDOW_MS,
    private val maxEntries: Int = BleMeshConstants.DEDUP_CACHE_MAX,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val seen = LinkedHashMap<String, Long>()

    /** @return true if [messageIdHex] was already seen within the dedup window (caller should drop it). */
    @Synchronized
    fun isDuplicate(messageIdHex: String): Boolean {
        purgeExpired()
        if (seen.containsKey(messageIdHex)) return true
        if (seen.size >= maxEntries) {
            val oldestKey = seen.keys.iterator().next()
            seen.remove(oldestKey)
        }
        seen[messageIdHex] = clock()
        return false
    }

    @Synchronized
    fun size(): Int = seen.size

    @Synchronized
    fun clear() = seen.clear()

    private fun purgeExpired() {
        val cutoff = clock() - windowMs
        seen.entries.removeIf { it.value < cutoff }
    }
}
