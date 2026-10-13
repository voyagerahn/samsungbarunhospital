package io.voyager.samsungbarunhospital.network.ble

import java.util.UUID

/**
 * Tunable parameters for the BLE mesh transport.
 *
 * TTL/dedup/fragmentation values are adapted from bitchat-android
 * (https://github.com/permissionlesstech/bitchat-android, app/src/main/java/com/bitchat/android/mesh/)
 * but re-tuned for a ward-scale nurse-call deployment rather than an open chat mesh:
 * undelivered messages are retried for CallQueue's 30-minute expiry instead of bitchat's 12h
 * store-forward window, and TTL/dedup window are unchanged since ward density is comparable.
 */
object BleMeshConstants {
    const val VERSION: Byte = 1
    const val DEFAULT_TTL: Byte = 5
    const val PEER_ID_SIZE = 8
    const val MESSAGE_ID_SIZE = 16

    val BROADCAST_RECIPIENT: ByteArray = ByteArray(PEER_ID_SIZE) { 0xFF.toByte() }

    const val DEDUP_WINDOW_MS = 5 * 60 * 1000L
    const val DEDUP_CACHE_MAX = 500

    const val FRAGMENT_THRESHOLD_BYTES = 400
    const val FRAGMENT_CHUNK_BYTES = 350
    const val FRAGMENT_REASSEMBLY_TIMEOUT_MS = 30 * 1000L

    // 호출·취소·확인응답은 받는 기기의 수신 확인(ACK)이 올 때까지 다시 보낸다(BleReliableOutbox).
    // 이 시간이 지나도 확인이 없으면 포기한다.
    //
    // 취소·확인응답은 길게 둔다 — 간호사 쪽 호출 만료(CallQueue.EXPIRE_MS, 30분)와 같은 값. 늦게라도
    // 도착해야 간호사 화면의 끝난 호출이 지워지고 환자 화면이 "호출 완료"로 바뀐다.
    const val DELIVERY_TIMEOUT_MS = 30 * 60 * 1000L
    // 호출은 짧게 둔다(사용자 결정, 2026-10-14). 예전에는 호출도 30분 동안 보관했다가 연결되면 보냈는데,
    // 한참 전에 누른 호출이 지금 울리면 간호사는 방금 누른 것으로 받아들이고, 환자는 그동안 도움이 오는
    // 줄 알고 기다리게 된다. 현장에서는 "전달 실패"를 보면 환자가 다시 누르므로, 빨리 실패를 알리는 쪽이
    // 안전하다. 10초는 사용자가 정한 값 — 수신 확인이 사라졌을 때의 재전송 네 번(0·1·3·7초 시점)을 덮는다.
    const val CALL_DELIVERY_TIMEOUT_MS = 10 * 1000L
    // 재전송 간격: 1초, 2초, 4초, 8초, 이후 10초마다.
    const val RESEND_BASE_DELAY_MS = 1_000L
    const val RESEND_MAX_DELAY_MS = 10_000L
    // 확인을 기다리는 메시지 수 상한. 간호사와 끊긴 채 벨을 계속 눌러도 메모리와 재전송량이 무한정
    // 늘지 않게 한다 — 넘치면 가장 오래된 것부터 포기한다.
    const val OUTBOX_MAX_ENTRIES = 50
    // 재전송된 호출·취소·확인응답을 중계할 때, 같은 메시지를 이 간격보다 자주 중계하지 않는다.
    const val RELAY_REPEAT_MIN_INTERVAL_MS = 700L
    // 이미 처리한 호출·취소·확인응답의 messageId를 기억하는 시간/개수. 재전송이 일반 중복 검사 창
    // (DEDUP_WINDOW_MS, 5분)보다 오래 이어져도 같은 메시지를 두 번 처리하지 않도록 따로 둔다.
    const val DELIVERED_MEMORY_MS = 40 * 60 * 1000L
    const val DELIVERED_MEMORY_MAX = 500

    const val STALE_PEER_TIMEOUT_MS = 60 * 1000L
    const val ANNOUNCE_INTERVAL_MS = 30 * 1000L

    // 앱이 스스로 두는 상한. **실제 한도는 기기의 블루투스 칩이 정하며 이보다 작을 수 있다** — 현장
    // 태블릿에서 4대 연결 상태로 광고 시작이 HCI 0x0C(Command Disallowed, 안드로이드에는 오류코드 12로
    // 올라옴)로 계속 거부된 사례가 있다(2026-10-13). 칩이 가득 차면 새 연결도 광고도 안 되므로, 이 값에
    // 못 미쳐도 "더 못 받는 상태"가 될 수 있다는 전제로 동작해야 한다(BleGattServerManager의 거부 처리,
    // BleMeshManager.trimDuplicateLinks 참고).
    const val MAX_CONNECTIONS = 8

    const val GATT_MTU_REQUEST = 517
    const val SCAN_RESTART_BACKOFF_BASE_MS = 3_000L
    const val SCAN_RESTART_BACKOFF_MAX_MS = 30_000L
    // Android 7+는 30분 넘게 계속 도는 스캔을 "opportunistic"으로 강등해 결과를 주지 않는다 — 한 번 시작하고
    // 내버려두면 앱 시작 30분 뒤부터는 끊긴 상대를 다시 찾지 못한다(재부팅 전까지 연결 0대). 그래서 그 한계
    // 안에서 스캔을 새로 시작한다. **플랫폼 제약 때문에 남겨둔 유일한 "시계 기반" 동작**이고, 그 밖의 복구는
    // 전부 사건(연결 끊김·연결됨·블루투스 켜짐) 기준이다. startScan은 30초에 5회 제한이 있어 충분히 길어야 한다.
    const val SCAN_RENEW_INTERVAL_MS = 10 * 60 * 1000L
    // 연결이 끊긴 순간에는 스캔을 바로 새로 시작한다(그때가 상대를 다시 찾아야 하는 순간). 단, 방금 시작한
    // 스캔이면 그대로 둔다.
    const val SCAN_RENEW_ON_LINK_LOST_MIN_AGE_MS = 60 * 1000L

    // 연결 실패 직후 다음 스캔 결과에서 곧바로 재시도하면 초당 여러 번 connectGatt가 반복되어
    // 블루투스 스택이 불안정해진다. 주소별로 5s, 10s, 20s … 최대 60s 간격을 둔다.
    const val CONNECT_RETRY_BACKOFF_BASE_MS = 5_000L
    const val CONNECT_RETRY_BACKOFF_MAX_MS = 60_000L
    // 등록된 간호사 태블릿은 끊겨도 계속 붙어야 하므로 재시도 간격 상한을 짧게 둔다.
    const val CONNECT_RETRY_BACKOFF_MAX_NURSE_MS = 15_000L

    // 중계용 연결을 하나 맺은 뒤, 그 연결로 간호사까지 길이 생겼는지 알게 될 때까지 다음 연결을 걸지 않고
    // 기다리는 시간. 연결 직후 상대가 아는 기기들의 소식(ANNOUNCE)을 넘겨주므로 보통 1~2초면 알 수 있다.
    const val LINK_SETTLE_MS = 6_000L
    // 새로 연결된 상대에게 넘겨줄 다른 기기 소식의 최대 나이 — ANNOUNCE 주기보다 조금 길게. 더 오래된 것은
    // 그 기기가 이미 사라졌을 수 있으므로 넘기지 않는다.
    const val ANNOUNCE_SYNC_MAX_AGE_MS = 35_000L


    const val ADVERTISE_RESTART_BACKOFF_BASE_MS = 3_000L
    const val ADVERTISE_RESTART_BACKOFF_MAX_MS = 30_000L

    // Newly generated UUIDs, deliberately distinct from bitchat's own service UUID so this
    // app's GATT server is never mistaken for (or noisily probed by) a nearby bitchat client.
    val SERVICE_UUID: UUID = UUID.fromString("08a15799-f1a9-441c-a850-c14d32a2803b")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("1b41d4a6-3dd8-4885-afb4-bcea2b00eedd")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
