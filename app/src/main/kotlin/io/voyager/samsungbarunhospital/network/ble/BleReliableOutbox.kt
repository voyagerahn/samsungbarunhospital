package io.voyager.samsungbarunhospital.network.ble

/**
 * 받는 기기의 수신 확인(ACK)이 올 때까지 다시 보내야 하는 패킷 목록 (순수 로직 — 안드로이드 의존 없음).
 *
 * 왜 필요한가: Wi-Fi(TCP) 경로는 간호사 태블릿이 "ACK"를 돌려줘서 전달 여부를 알 수 있는데, 블루투스
 * 경로에는 그게 없어서 "보냈지만 도착했는지 모르는" 상태였다. 요구사항은 "블루투스는 IP와 똑같이
 * 동작하고 통신 수단만 바뀐다" + "연결이 끊겨도 등록한 간호사 태블릿으로 계속 보낸다"이므로:
 *
 * - 호출·취소·확인응답을 보낼 때 여기 넣어두고, ACK가 올 때까지 간격을 늘려가며 다시 보낸다.
 * - 받는 기기에 닿을 수 없는 동안은 보내지 않고 기다렸다가, 닿는 순간 보낸다.
 * - [timeoutMs]가 지나도 ACK가 없으면 포기한다(간호사 쪽 호출 만료 30분과 같은 값).
 *
 * 다시 보낼 때 매번 **똑같은 바이트(같은 messageId)**를 보낸다. 받는 쪽은 messageId로 중복을 걸러 한 번만
 * 처리하고, 중복으로 받은 것에도 ACK는 다시 돌려준다 — 그래야 ACK가 중간에 사라져도 다음 재전송에서
 * 확인이 완료된다.
 *
 * 한 번에 한 스레드에서만 부른다는 가정 없이 쓸 수 있게 전부 @Synchronized.
 */
class BleReliableOutbox(
    private val timeoutMs: Long = BleMeshConstants.DELIVERY_TIMEOUT_MS,
    private val maxEntries: Int = BleMeshConstants.OUTBOX_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    class Entry internal constructor(
        /** ACK가 가리키는 값 — 보낸 패킷의 messageId(hex). */
        val messageIdHex: String,
        val recipientHex: String,
        /** 실제로 내보낼 바이트(조각이 여러 개면 순서대로). 재전송마다 그대로 다시 쓴다. */
        val frames: List<ByteArray>,
        /** 같은 논리 메시지를 찾기 위한 표식(예: "CALL:<callId>@<recipient>"). */
        val key: String,
        /** 기록용 한 줄 설명(예: "호출 #1a2b3c4d"). */
        val what: String,
        val expiresAt: Long
    ) {
        /** 지금까지 내보낸 횟수. 0이면 아직 한 번도 못 나간 것. */
        var attempts = 0
            internal set
        internal var nextSendAt = 0L
        /** 닿을 수 없어서 기다린 적이 있는지(기록용). */
        var waitedUnreachable = false
            internal set
    }

    private val entries = LinkedHashMap<String, Entry>() // key = messageIdHex

    /**
     * @return 자리를 만들려고 밀려난(포기된) 가장 오래된 항목들 — 상한 [maxEntries]을 넘지 않게 한다.
     */
    @Synchronized
    fun add(messageIdHex: String, recipientHex: String, frames: List<ByteArray>, key: String, what: String): List<Entry> {
        entries[messageIdHex] = Entry(messageIdHex, recipientHex, frames, key, what, clock() + timeoutMs)
        val dropped = ArrayList<Entry>()
        val it = entries.values.iterator()
        while (entries.size > maxEntries && it.hasNext()) {
            dropped.add(it.next())
            it.remove()
        }
        return dropped
    }

    /** ACK 수신 — 해당 항목을 끝내고 돌려준다(없으면 null: 이미 끝났거나 우리 것이 아님). */
    @Synchronized
    fun ack(messageIdHex: String): Entry? = entries.remove(messageIdHex)

    /** [matches]에 해당하는 항목을 모두 빼서 돌려준다(취소·간호사 확인으로 더 보낼 필요가 없을 때). */
    @Synchronized
    fun removeWhere(matches: (key: String) -> Boolean): List<Entry> {
        val removed = entries.values.filter { matches(it.key) }
        removed.forEach { entries.remove(it.messageIdHex) }
        return removed
    }

    /** 기한이 지난 항목을 빼서 돌려준다. */
    @Synchronized
    fun takeExpired(): List<Entry> {
        val now = clock()
        val expired = entries.values.filter { it.expiresAt <= now }
        expired.forEach { entries.remove(it.messageIdHex) }
        return expired
    }

    /**
     * 지금 보내야 할 항목들. 돌려준 항목은 "한 번 보낸 것"으로 세고 다음 전송 시각을 뒤로 민다
     * (1초, 2초, 4초, 8초, 이후 10초 간격). 닿을 수 없는 항목은 건드리지 않고 남겨둔다.
     */
    @Synchronized
    fun takeDue(isReachable: (recipientHex: String) -> Boolean): List<Entry> {
        val now = clock()
        val due = ArrayList<Entry>()
        for (entry in entries.values) {
            if (entry.nextSendAt > now) continue
            if (!isReachable(entry.recipientHex)) {
                entry.waitedUnreachable = true
                continue
            }
            entry.attempts++
            entry.nextSendAt = now + retryDelayMs(entry.attempts)
            due.add(entry)
        }
        return due
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    @Synchronized
    fun size(): Int = entries.size

    private fun retryDelayMs(attempts: Int): Long =
        (BleMeshConstants.RESEND_BASE_DELAY_MS shl (attempts - 1).coerceAtMost(10))
            .coerceAtMost(BleMeshConstants.RESEND_MAX_DELAY_MS)
}
