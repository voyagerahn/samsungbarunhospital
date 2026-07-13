package io.voyager.samsungbarunhospital.network.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks peer online/offline state from ANNOUNCE packets, adapted from bitchat-android's
 * PeerManager (app/src/main/java/com/bitchat/android/mesh/PeerManager.kt): online is "we heard
 * an ANNOUNCE recently", offline is periodic stale-timeout cleanup rather than a GATT disconnect
 * callback (a peer can be reachable through more than one relay hop, so a single link dropping
 * doesn't necessarily mean the peer itself went offline).
 */
class BlePeerManager(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPeerCameOnline: (peerIdHex: String) -> Unit
) {
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var cleanupJob: Job? = null

    fun markSeen(peerIdHex: String) {
        val wasOnline = isOnline(peerIdHex)
        lastSeen[peerIdHex] = clock()
        if (!wasOnline) onPeerCameOnline(peerIdHex)
    }

    fun isOnline(peerIdHex: String): Boolean {
        val ts = lastSeen[peerIdHex] ?: return false
        return clock() - ts < BleMeshConstants.STALE_PEER_TIMEOUT_MS
    }

    fun startCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(BleMeshConstants.STALE_PEER_TIMEOUT_MS)
                val cutoff = clock() - BleMeshConstants.STALE_PEER_TIMEOUT_MS
                lastSeen.entries.removeIf { it.value < cutoff }
            }
        }
    }

    fun stopCleanup() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    fun clear() {
        lastSeen.clear()
    }
}
