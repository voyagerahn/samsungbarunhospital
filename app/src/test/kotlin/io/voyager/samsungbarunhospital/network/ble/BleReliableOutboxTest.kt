package io.voyager.samsungbarunhospital.network.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReliableOutboxTest {

    private var now = 0L
    private fun outbox(timeoutMs: Long = 30 * 60_000L, max: Int = 50) = BleReliableOutbox(timeoutMs, max) { now }
    private val frame = listOf(byteArrayOf(1, 2, 3))
    private val nurse = "aaaaaaaaaaaaaaaa"

    @Test
    fun `sends immediately when reachable and stops once acknowledged`() {
        val box = outbox()
        box.add("m1", nurse, frame, "CALL:c1@$nurse", "호출")

        assertEquals(1, box.takeDue { true }.size)
        assertNotNull(box.ack("m1"))
        assertTrue(box.isEmpty())
        assertTrue(box.takeDue { true }.isEmpty())
    }

    @Test
    fun `keeps resending with growing gaps until acknowledged`() {
        val box = outbox()
        box.add("m1", nurse, frame, "CALL:c1@$nurse", "호출")

        assertEquals(1, box.takeDue { true }.size)       // t=0, 1번째
        assertTrue(box.takeDue { true }.isEmpty())        // 아직 1초 안 지남
        now += 1_000; assertEquals(1, box.takeDue { true }.size) // 2번째 (다음은 2초 뒤)
        now += 1_000; assertTrue(box.takeDue { true }.isEmpty())
        now += 1_000; assertEquals(1, box.takeDue { true }.size) // 3번째 (다음은 4초 뒤)
        now += 4_000; assertEquals(1, box.takeDue { true }.size) // 4번째 (다음은 8초 뒤)
        now += 8_000; assertEquals(1, box.takeDue { true }.size) // 5번째 (이후 10초 상한)
        now += 9_999; assertTrue(box.takeDue { true }.isEmpty())
        now += 1;     assertEquals(6, box.takeDue { true }.single().attempts)
    }

    @Test
    fun `waits without sending while the nurse is unreachable, then sends as soon as it is back`() {
        val box = outbox()
        box.add("m1", nurse, frame, "CALL:c1@$nurse", "호출")

        now += 5 * 60_000
        assertTrue(box.takeDue { false }.isEmpty())       // 끊겨 있는 동안은 보내지 않는다
        val sent = box.takeDue { true }.single()          // 다시 닿자마자 보낸다
        assertEquals(1, sent.attempts)
        assertTrue(sent.waitedUnreachable)
    }

    @Test
    fun `gives up after the delivery timeout`() {
        val box = outbox(timeoutMs = 60_000L)
        box.add("m1", nurse, frame, "CALL:c1@$nurse", "호출")

        now += 59_999; assertTrue(box.takeExpired().isEmpty())
        now += 1;      assertEquals("m1", box.takeExpired().single().messageIdHex)
        assertTrue(box.isEmpty())
        assertNull(box.ack("m1")) // 포기한 뒤 늦게 온 확인은 무시
    }

    @Test
    fun `cancelling withdraws the matching call and reports whether it ever went out`() {
        val box = outbox()
        box.add("m1", nurse, frame, "CALL:c1@$nurse", "호출")
        box.add("m2", nurse, frame, "CALL:c2@$nurse", "호출")
        box.takeDue { it == nurse }.size // 둘 다 한 번씩 나감

        box.add("m3", nurse, frame, "CALL:c3@$nurse", "호출") // 아직 안 나감
        val neverSent = box.removeWhere { it == "CALL:c3@$nurse" }.single()
        val alreadySent = box.removeWhere { it == "CALL:c1@$nurse" }.single()

        assertEquals(0, neverSent.attempts)
        assertEquals(1, alreadySent.attempts)
        assertEquals(1, box.size()) // c2만 남음
    }

    @Test
    fun `never holds more than the cap - oldest entries are dropped first`() {
        val box = outbox(max = 3)
        for (i in 1..3) assertTrue(box.add("m$i", nurse, frame, "CALL:c$i@$nurse", "호출").isEmpty())

        val dropped = box.add("m4", nurse, frame, "CALL:c4@$nurse", "호출")

        assertEquals("m1", dropped.single().messageIdHex)
        assertEquals(3, box.size())
        assertNull(box.ack("m1"))
        assertNotNull(box.ack("m4"))
    }

    @Test
    fun `sleeps until the next resend when reachable, and until expiry when not - never polls`() {
        val box = outbox(timeoutMs = 30 * 60_000L)
        assertNull(box.nextWakeDelayMs { true }) // 비어 있으면 깨어날 이유가 없다

        box.add("m1", nurse, frame, "CALL:c1@$nurse", "호출")
        assertEquals(0L, box.nextWakeDelayMs { true })        // 바로 보낼 차례
        box.takeDue { true }
        assertEquals(1_000L, box.nextWakeDelayMs { true })    // 다음 재전송까지
        // 닿을 수 없으면 닿게 되는 사건이 올 때까지 할 일이 없다 — 만료 시각까지 잔다.
        assertEquals(30 * 60_000L, box.nextWakeDelayMs { false })
    }

    @Test
    fun `a call expires long before a cancel added at the same time`() {
        // 호출은 늦게 도착하면 위험하므로 짧게, 취소·확인응답은 늦게라도 도착해야 하므로 길게.
        val box = outbox()
        box.add("call", "aa", listOf(ByteArray(1)), "CALL:1@aa", "호출", timeoutMs = 30_000L)
        box.add("cancel", "aa", listOf(ByteArray(1)), "CANCEL:2@aa", "취소")
        now += 30_000L
        val expired = box.takeExpired()
        assertEquals(listOf("call"), expired.map { it.messageIdHex })
        assertEquals(30_000L, expired.single().timeoutMs)
        assertEquals(1, box.size())
    }
}
