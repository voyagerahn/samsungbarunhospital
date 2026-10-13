package io.voyager.samsungbarunhospital.network.ble

import io.voyager.samsungbarunhospital.network.ble.BleConnectPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectPolicyTest {

    private val max = 8

    @Test
    fun `registered nurse is connected whenever there is room`() {
        assertEquals(Decision.CONNECT, BleConnectPolicy.decide(true, linkCount = 7, maxLinks = max, unlinkedNurseCount = 0, hasEvictable = false))
    }

    @Test
    fun `registered nurse takes over a non-nurse slot when full`() {
        assertEquals(Decision.CONNECT_AFTER_EVICTING, BleConnectPolicy.decide(true, linkCount = 8, maxLinks = max, unlinkedNurseCount = 0, hasEvictable = true))
    }

    @Test
    fun `registered nurse is skipped only when every slot is already a nurse`() {
        assertEquals(Decision.SKIP, BleConnectPolicy.decide(true, linkCount = 8, maxLinks = max, unlinkedNurseCount = 0, hasEvictable = false))
    }

    @Test
    fun `other tablets are linked for relaying while there is no path to the nurse`() {
        // 환자 → 환자 → 간호사 중계를 위해 환자 태블릿끼리의 링크가 필요하다.
        assertEquals(Decision.CONNECT, BleConnectPolicy.decide(false, linkCount = 3, maxLinks = max, unlinkedNurseCount = 0, hasEvictable = true, wantsRelayLink = true))
    }

    @Test
    fun `other tablets are not linked once the nurse is reachable`() {
        assertEquals(Decision.SKIP, BleConnectPolicy.decide(false, linkCount = 1, maxLinks = max, unlinkedNurseCount = 0, hasEvictable = true, wantsRelayLink = false))
    }

    @Test
    fun `other tablets leave room for nurses that are not linked yet`() {
        // 간호사 2명이 아직 연결 전 → 마지막 두 자리는 다른 태블릿에게 주지 않는다.
        assertEquals(Decision.CONNECT, BleConnectPolicy.decide(false, linkCount = 5, maxLinks = max, unlinkedNurseCount = 2, hasEvictable = true))
        assertEquals(Decision.SKIP, BleConnectPolicy.decide(false, linkCount = 6, maxLinks = max, unlinkedNurseCount = 2, hasEvictable = true))
    }

    @Test
    fun `one slot is always left for tablets that connect to this one`() {
        // 자리를 끝까지 채우면 광고가 꺼져 다른 태블릿이 이 태블릿을 찾을 수 없다(간호사 태블릿 자신도 마찬가지).
        assertEquals(Decision.CONNECT, BleConnectPolicy.decide(false, linkCount = 2, maxLinks = 4, unlinkedNurseCount = 0, hasEvictable = true))
        assertEquals(Decision.SKIP, BleConnectPolicy.decide(false, linkCount = 3, maxLinks = 4, unlinkedNurseCount = 0, hasEvictable = true))
        assertEquals(Decision.SKIP, BleConnectPolicy.decide(false, linkCount = 4, maxLinks = 4, unlinkedNurseCount = 0, hasEvictable = true))
    }

    @Test
    fun `a tablet with no link at all always connects`() {
        assertEquals(Decision.CONNECT, BleConnectPolicy.decide(false, linkCount = 0, maxLinks = 1, unlinkedNurseCount = 1, hasEvictable = false))
    }

    // ── 중계용 연결이 더 필요한가 ──

    private fun wants(hasNurse: Boolean = true, links: Int = 1, pending: Int = 0, path: Boolean = false, inMesh: Boolean = false, sinceMs: Long = 60_000) =
        BleConnectPolicy.wantsRelayLink(hasNurse, links, pending, path, inMesh, sinceMs, settleMs = 6_000)

    @Test
    fun `a tablet already heard through the mesh is not linked again`() {
        // 간호사와 떨어진 태블릿끼리 서로 자리를 다 채우지 않게 한다.
        assertFalse(wants(links = 1, inMesh = true))
        assertTrue(wants(links = 0, inMesh = true)) // 연결이 하나도 없으면 예외
    }

    @Test
    fun `a tablet without a path may use its last slot for a newly seen tablet`() {
        assertEquals(Decision.CONNECT, BleConnectPolicy.decide(false, linkCount = 3, maxLinks = 4, unlinkedNurseCount = 1, hasEvictable = true, expandsReach = true))
        assertEquals(Decision.SKIP, BleConnectPolicy.decide(false, linkCount = 4, maxLinks = 4, unlinkedNurseCount = 1, hasEvictable = true, expandsReach = true))
    }

    @Test
    fun `the nurse tablet itself links to whatever it finds`() {
        assertTrue(wants(hasNurse = false, path = false))
    }

    @Test
    fun `a patient tablet stops adding links once every nurse has a path`() {
        assertFalse(wants(path = true))
        assertFalse(wants(path = true, links = 0))
    }

    @Test
    fun `a patient tablet without a path keeps looking`() {
        assertTrue(wants(links = 0, sinceMs = 0))
        assertTrue(wants(links = 2, sinceMs = 60_000))
    }

    @Test
    fun `it waits for a fresh link to show whether it leads to the nurse`() {
        assertFalse(wants(links = 1, sinceMs = 1_000))
        assertTrue(wants(links = 1, sinceMs = 6_000))
    }

    @Test
    fun `only one relay connection is attempted at a time`() {
        assertFalse(wants(links = 0, pending = 1))
    }
}
