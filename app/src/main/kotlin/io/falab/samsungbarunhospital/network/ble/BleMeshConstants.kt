package io.falab.samsungbarunhospital.network.ble

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

    const val STALE_PEER_TIMEOUT_MS = 60 * 1000L
    const val ANNOUNCE_INTERVAL_MS = 30 * 1000L

    // Newly generated UUIDs, deliberately distinct from bitchat's own service UUID so this
    // app's GATT server is never mistaken for (or noisily probed by) a nearby bitchat client.
    val SERVICE_UUID: UUID = UUID.fromString("08a15799-f1a9-441c-a850-c14d32a2803b")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("1b41d4a6-3dd8-4885-afb4-bcea2b00eedd")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
