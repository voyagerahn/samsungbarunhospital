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
    class OnlinePeer(@JvmField val peerIdHex: String, @JvmField val name: String?)

    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val names = ConcurrentHashMap<String, String>()
    private var cleanupJob: Job? = null

    fun markSeen(peerIdHex: String) {
        val wasOnline = isOnline(peerIdHex)
        lastSeen[peerIdHex] = clock()
        if (!wasOnline) onPeerCameOnline(peerIdHex)
    }

    /** ANNOUNCE 페이로드로 실려 온 표시 이름 — "근처 기기 검색" 화면에서 peerId 대신 보여줌. */
    fun updateName(peerIdHex: String, name: String) {
        names[peerIdHex] = name
    }

    fun isOnline(peerIdHex: String): Boolean {
        val ts = lastSeen[peerIdHex] ?: return false
        return clock() - ts < BleMeshConstants.STALE_PEER_TIMEOUT_MS
    }

    /** 현재 online으로 판정되는(최근 STALE_PEER_TIMEOUT_MS 이내에 본) peer 전체. */
    fun listOnline(): List<OnlinePeer> =
        lastSeen.keys.filter { isOnline(it) }.map { OnlinePeer(it, names[it]) }

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
        names.clear()
    }
}
