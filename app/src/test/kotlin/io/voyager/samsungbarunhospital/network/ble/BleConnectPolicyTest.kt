package io.voyager.samsungbarunhospital.network.ble

import io.voyager.samsungbarunhospital.network.ble.BleConnectPolicy.Decision
import org.junit.Assert.assertEquals
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
    fun `other tablets are still linked for relaying toward the nurse`() {
        // 환자 → 환자 → 간호사 중계를 위해 환자 태블릿끼리의 링크는 유지된다.
        assertEquals(Decision.CONNECT, BleConnectPolicy.decide(false, linkCount = 3, maxLinks = max, unlinkedNurseCount = 0, hasEvictable = true))
    }

    @Test
    fun `other tablets leave room for nurses that are not linked yet`() {
        // 간호사 1명이 아직 연결 전 → 마지막 한 자리는 다른 태블릿에게 주지 않는다.
        assertEquals(Decision.CONNECT, BleConnectPolicy.decide(false, linkCount = 6, maxLinks = max, unlinkedNurseCount = 1, hasEvictable = true))
        assertEquals(Decision.SKIP, BleConnectPolicy.decide(false, linkCount = 7, maxLinks = max, unlinkedNurseCount = 1, hasEvictable = true))
    }

    @Test
    fun `a tablet with no registered nurse (the nurse tablet itself) links until full`() {
        assertEquals(Decision.CONNECT, BleConnectPolicy.decide(false, linkCount = 7, maxLinks = max, unlinkedNurseCount = 0, hasEvictable = true))
        assertEquals(Decision.SKIP, BleConnectPolicy.decide(false, linkCount = 8, maxLinks = max, unlinkedNurseCount = 0, hasEvictable = true))
    }
}
