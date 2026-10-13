package io.voyager.samsungbarunhospital.network.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLinkLimitTest {

    @Test
    fun `limit is the app maximum until the chip says it is full`() {
        assertEquals(8, BleLinkLimit.effective(saturatedAt = -1, appMax = 8))
        assertEquals(4, BleLinkLimit.effective(saturatedAt = 4, appMax = 8))
    }

    @Test
    fun `a refusal with four links means the chip is full`() {
        // 현장 사례: 4대 연결 상태에서 광고가 계속 거부됨
        assertTrue(BleLinkLimit.isLimitSignal(linkCount = 4, explicitLimit = false, appMax = 8))
    }

    @Test
    fun `a refusal with only one or two links is not treated as full`() {
        // 연결 시도 중 같은 일시적 상태에서도 거부될 수 있다 — 이걸 한도로 보면 그 기기는 1~2대만 받게 된다.
        assertFalse(BleLinkLimit.isLimitSignal(linkCount = 1, explicitLimit = false, appMax = 8))
        assertFalse(BleLinkLimit.isLimitSignal(linkCount = 2, explicitLimit = false, appMax = 8))
    }

    @Test
    fun `an explicit connection-limit status is trusted from two links`() {
        assertTrue(BleLinkLimit.isLimitSignal(linkCount = 2, explicitLimit = true, appMax = 8))
        assertFalse(BleLinkLimit.isLimitSignal(linkCount = 1, explicitLimit = true, appMax = 8))
    }

    @Test
    fun `status codes are explained`() {
        assertTrue(BleStatusText.hci(12)!!.contains("허용되지 않는"))
        assertEquals("동시 연결 한도 초과", BleStatusText.hci(9))
        assertNull(BleStatusText.hci(200))
        assertTrue(BleStatusText.gattStatus(133).startsWith("연결 실패"))
    }
}
