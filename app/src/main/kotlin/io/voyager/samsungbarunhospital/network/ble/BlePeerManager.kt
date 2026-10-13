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
    /** @param hops 그 기기까지 거친 태블릿 수(0 = 직접 연결된 기기에서 바로 옴, -1 = 모름) */
    class OnlinePeer(@JvmField val peerIdHex: String, @JvmField val name: String?, @JvmField val hops: Int)

    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val names = ConcurrentHashMap<String, String>()
    private val hops = ConcurrentHashMap<String, Int>()
    // 그 기기의 소식이 들어온 링크(주소). "지금 살아있는 링크로 그 기기까지 길이 있다"는 근거다 —
    // 그 링크가 끊기면 지워지고([onLinkLost]), 다른 링크로 소식이 오면 다시 생긴다.
    private val via = ConcurrentHashMap<String, String>()
    private var cleanupJob: Job? = null

    /**
     * @param hopCount 이 패킷이 거쳐 온 태블릿 수(0 = 중계 없이 옴)
     * @param viaAddress 이 패킷이 들어온 링크의 주소
     */
    fun markSeen(peerIdHex: String, hopCount: Int = -1, viaAddress: String? = null) {
        val wasOnline = isOnline(peerIdHex)
        lastSeen[peerIdHex] = clock()
        if (hopCount >= 0) hops[peerIdHex] = hopCount
        if (viaAddress != null) via[peerIdHex] = viaAddress
        if (!wasOnline) onPeerCameOnline(peerIdHex)
    }

    /**
     * 이미 본 소식(중복)이 [viaAddress] 링크로도 왔다 — 그 기기까지 가는 길이 이 링크에도 있다는 뜻이다.
     * 길을 모르는 상태일 때만 기록한다(가장 먼저 도착한 링크 = 가장 빠른 길을 유지).
     */
    fun confirmPath(peerIdHex: String, viaAddress: String) {
        if (isOnline(peerIdHex)) via.putIfAbsent(peerIdHex, viaAddress)
    }

    /** [address] 링크가 끊겼다 — 그 링크로만 소식을 듣던 기기들은 길이 확인되지 않은 상태가 된다. */
    fun onLinkLost(address: String) {
        via.entries.removeIf { it.value == address }
    }

    /** 지금 살아있는 링크를 통해 [peerIdHex]까지 길이 있다고 확인된 상태인지. */
    fun hasPath(peerIdHex: String): Boolean = isOnline(peerIdHex) && via.containsKey(peerIdHex)

    /** [peerIdHex]까지 거치는 태블릿 수(0 = 직접). 모르면 -1. */
    fun hopsTo(peerIdHex: String): Int = if (isOnline(peerIdHex)) hops[peerIdHex] ?: -1 else -1

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
        lastSeen.keys.filter { isOnline(it) }.map { OnlinePeer(it, names[it], hops[it] ?: -1) }

    fun startCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(BleMeshConstants.STALE_PEER_TIMEOUT_MS)
                val cutoff = clock() - BleMeshConstants.STALE_PEER_TIMEOUT_MS
                lastSeen.entries.removeIf { it.value < cutoff }
                hops.keys.retainAll(lastSeen.keys)
                via.keys.retainAll(lastSeen.keys)
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
        hops.clear()
        via.clear()
    }
}
