package io.voyager.samsungbarunhospital.network.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePeerManagerTest {

    private var now = 1_000_000L
    private val peers = BlePeerManager(CoroutineScope(Job()), clock = { now }) {}
    private val nurse = "aaaaaaaaaaaaaaaa"

    @Test
    fun `a peer heard over a live link has a path and a relay count`() {
        peers.markSeen(nurse, hopCount = 2, viaAddress = "AA:01")
        assertTrue(peers.hasPath(nurse))
        assertEquals(2, peers.hopsTo(nurse))
        assertEquals(2, peers.listOnline().single().hops)
    }

    @Test
    fun `losing the link it was heard over leaves the peer online but without a confirmed path`() {
        peers.markSeen(nurse, hopCount = 1, viaAddress = "AA:01")
        peers.onLinkLost("AA:01")
        assertTrue(peers.isOnline(nurse)) // 다른 길이 있을 수 있으므로 호출은 계속 내보낸다
        assertFalse(peers.hasPath(nurse)) // 하지만 새 연결을 찾아야 하는 상태
    }

    @Test
    fun `losing an unrelated link keeps the path`() {
        peers.markSeen(nurse, hopCount = 1, viaAddress = "AA:01")
        peers.onLinkLost("AA:02")
        assertTrue(peers.hasPath(nurse))
    }

    @Test
    fun `the same news arriving over another link confirms the path again`() {
        peers.markSeen(nurse, hopCount = 1, viaAddress = "AA:01")
        peers.onLinkLost("AA:01")
        peers.confirmPath(nurse, "AA:02")
        assertTrue(peers.hasPath(nurse))
    }

    @Test
    fun `a peer not heard for a while has no path`() {
        peers.markSeen(nurse, hopCount = 1, viaAddress = "AA:01")
        now += BleMeshConstants.STALE_PEER_TIMEOUT_MS + 1
        assertFalse(peers.hasPath(nurse))
        assertEquals(-1, peers.hopsTo(nurse))
        peers.confirmPath(nurse, "AA:02")
        assertFalse(peers.hasPath(nurse))
    }
}
