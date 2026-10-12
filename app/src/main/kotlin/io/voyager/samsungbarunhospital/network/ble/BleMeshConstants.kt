package io.voyager.samsungbarunhospital.network.ble

import java.util.UUID

/**
 * Tunable parameters for the BLE mesh transport.
 *
 * TTL/dedup/fragmentation values are adapted from bitchat-android
 * (https://github.com/permissionlesstech/bitchat-android, app/src/main/java/com/bitchat/android/mesh/)
 * but re-tuned for a ward-scale nurse-call deployment rather than an open chat mesh:
 * store-forward timeout matches CallQueue's 30-minute expiry instead of bitchat's 12h,
 * and TTL/dedup window are unchanged since ward density is comparable to bitchat's assumptions.
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

    // Matches CallQueue.EXPIRE_MS (30 minutes) — a CONFIRMED reply older than this is
    // no longer meaningful to the patient tablet's UI.
    const val STORE_FORWARD_TIMEOUT_MS = 30 * 60 * 1000L
    const val STORE_FORWARD_MAX_PER_PEER = 20

    // 연결된 기기가 0대라 실제로 못 나간 CALL/CANCEL을 보관하는 시간. 그 안에 받는 기기가 다시
    // 감지되면 자동 재전송한다. 환자 화면에는 그동안 "대기 중"으로 남아 있고, 간호사 쪽은 callId로
    // 중복을 거르므로 늦게 도착해도 안전하다. 너무 길면 한참 지난 호출이 뒤늦게 울리므로 10분.
    const val PENDING_CALL_TIMEOUT_MS = 10 * 60 * 1000L

    const val STALE_PEER_TIMEOUT_MS = 60 * 1000L
    const val ANNOUNCE_INTERVAL_MS = 30 * 1000L

    // Ward-scale cap, not a hard BLE stack limit (most Android stacks tolerate more) — keeps
    // resource usage predictable without bitchat's RSSI-based eviction logic.
    const val MAX_CONNECTIONS = 8

    const val GATT_MTU_REQUEST = 517
    const val SCAN_RESTART_BACKOFF_BASE_MS = 3_000L
    const val SCAN_RESTART_BACKOFF_MAX_MS = 30_000L
    // Android 7+는 30분 넘게 계속 도는 스캔을 "opportunistic"으로 강등해 결과를 주지 않는다 —
    // 한 번 시작하고 내버려두면 앱 시작 30분 뒤부터는 끊긴 상대를 다시 찾지 못한다(재부팅 전까지
    // 연결 0대). 그 전에 주기적으로 스캔을 새로 시작한다. 연결이 하나도 없을 때는 더 자주 —
    // 일부 기기에서 스캔이 조용히 멈추는 경우의 자가 복구. (startScan은 30초에 5회 제한이 있어
    // 이 값들은 그보다 충분히 길어야 한다.)
    const val SCAN_HEALTH_CHECK_INTERVAL_MS = 60 * 1000L
    const val SCAN_REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    const val SCAN_REFRESH_WHEN_ALONE_MS = 2 * 60 * 1000L

    // 연결 실패 직후 다음 스캔 결과에서 곧바로 재시도하면 초당 여러 번 connectGatt가 반복되어
    // 블루투스 스택이 불안정해진다. 주소별로 5s, 10s, 20s … 최대 60s 간격을 둔다.
    const val CONNECT_RETRY_BACKOFF_BASE_MS = 5_000L
    const val CONNECT_RETRY_BACKOFF_MAX_MS = 60_000L

    // 연결이 하나도 없는 채로 이 시간이 지나면 광고를 새로 시작한다(광고가 조용히 멈춘 경우 대비).
    const val ADVERTISE_REFRESH_WHEN_ALONE_MS = 5 * 60 * 1000L

    const val ADVERTISE_RESTART_BACKOFF_BASE_MS = 3_000L
    const val ADVERTISE_RESTART_BACKOFF_MAX_MS = 30_000L

    // Newly generated UUIDs, deliberately distinct from bitchat's own service UUID so this
    // app's GATT server is never mistaken for (or noisily probed by) a nearby bitchat client.
    val SERVICE_UUID: UUID = UUID.fromString("08a15799-f1a9-441c-a850-c14d32a2803b")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("1b41d4a6-3dd8-4885-afb4-bcea2b00eedd")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
