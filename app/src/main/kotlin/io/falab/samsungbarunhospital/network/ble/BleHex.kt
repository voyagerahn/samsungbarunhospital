package io.falab.samsungbarunhospital.network.ble

/** Lower-case hex encoding shared by peerId/messageId handling across the BLE mesh module. */
fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append(String.format("%02x", b))
    return sb.toString()
}

/** Returns null (rather than throwing or producing garbage) for odd-length or non-hex input. */
fun String.hexToByteArray(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) + lo).toByte()
    }
    return out
}
