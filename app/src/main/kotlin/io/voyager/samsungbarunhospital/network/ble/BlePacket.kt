package io.voyager.samsungbarunhospital.network.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferUnderflowException

/**
 * Wire type of a [BlePacket]. Deliberately small compared to bitchat's MessageType: this
 * transport only ever carries the existing pipe-delimited call protocol as opaque payload
 * bytes, so ANNOUNCE (peer presence) and FRAGMENT (large-payload chunk) are the only
 * mesh-management types needed alongside the three call-flow types.
 */
enum class BlePacketType(val value: Byte) {
    CALL(0x01),
    CANCEL(0x02),
    CONFIRMED(0x03),
    ANNOUNCE(0x04),
    FRAGMENT(0x05),
    /**
     * 수신 확인. payload = 확인하는 패킷의 messageId(hex). CALL/CANCEL/CONFIRMED를 받은 기기가 보낸
     * 기기에게 돌려준다 — Wi-Fi(TCP) 경로의 "ACK" 응답과 같은 역할. v2.2.2에서 추가: 그 이전 버전은
     * 모르는 타입이라 패킷을 버리므로(decode가 null) 섞여 있어도 오동작은 없고, 확인만 안 돌아온다.
     */
    ACK(0x06);

    companion object {
        fun fromValue(v: Byte): BlePacketType? = values().find { it.value == v }
    }
}

/**
 * Minimal binary framing adapted from bitchat-android's BinaryProtocol
 * (app/src/main/java/com/bitchat/android/protocol/BinaryProtocol.kt), stripped of the
 * signature/compression/padding/gossip-route fields that only matter for its chat use case.
 *
 * Layout (big-endian): version(1) type(1) ttl(1) senderPeerId(8) recipientPeerId(8)
 * messageId(16) payloadLen(2) payload(payloadLen).
 */
data class BlePacket(
    val version: Byte = BleMeshConstants.VERSION,
    val type: BlePacketType,
    val ttl: Byte,
    val senderPeerId: ByteArray,
    val recipientPeerId: ByteArray,
    val messageId: ByteArray,
    val payload: ByteArray
) {
    init {
        require(senderPeerId.size == BleMeshConstants.PEER_ID_SIZE) { "senderPeerId must be ${BleMeshConstants.PEER_ID_SIZE} bytes" }
        require(recipientPeerId.size == BleMeshConstants.PEER_ID_SIZE) { "recipientPeerId must be ${BleMeshConstants.PEER_ID_SIZE} bytes" }
        require(messageId.size == BleMeshConstants.MESSAGE_ID_SIZE) { "messageId must be ${BleMeshConstants.MESSAGE_ID_SIZE} bytes" }
        require(payload.size <= MAX_PAYLOAD_SIZE) { "payload exceeds $MAX_PAYLOAD_SIZE bytes" }
    }

    val isBroadcast: Boolean
        get() = recipientPeerId.contentEquals(BleMeshConstants.BROADCAST_RECIPIENT)

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(version)
        buffer.put(type.value)
        buffer.put(ttl)
        buffer.put(senderPeerId)
        buffer.put(recipientPeerId)
        buffer.put(messageId)
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlePacket) return false
        return version == other.version &&
            type == other.type &&
            ttl == other.ttl &&
            senderPeerId.contentEquals(other.senderPeerId) &&
            recipientPeerId.contentEquals(other.recipientPeerId) &&
            messageId.contentEquals(other.messageId) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + type.hashCode()
        result = 31 * result + ttl
        result = 31 * result + senderPeerId.contentHashCode()
        result = 31 * result + recipientPeerId.contentHashCode()
        result = 31 * result + messageId.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val MAX_PAYLOAD_SIZE = 65535
        const val HEADER_SIZE = 1 + 1 + 1 + BleMeshConstants.PEER_ID_SIZE * 2 + BleMeshConstants.MESSAGE_ID_SIZE + 2

        /** Never throws — malformed or truncated bytes (e.g. from an unrelated nearby BLE device) yield null. */
        fun decode(bytes: ByteArray): BlePacket? {
            if (bytes.size < HEADER_SIZE) return null
            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val version = buffer.get()
                val typeByte = buffer.get()
                val type = BlePacketType.fromValue(typeByte) ?: return null
                val ttl = buffer.get()
                val senderPeerId = ByteArray(BleMeshConstants.PEER_ID_SIZE).also { buffer.get(it) }
                val recipientPeerId = ByteArray(BleMeshConstants.PEER_ID_SIZE).also { buffer.get(it) }
                val messageId = ByteArray(BleMeshConstants.MESSAGE_ID_SIZE).also { buffer.get(it) }
                val payloadLen = buffer.short.toInt() and 0xFFFF
                if (buffer.remaining() < payloadLen) return null
                val payload = ByteArray(payloadLen).also { buffer.get(it) }
                BlePacket(version, type, ttl, senderPeerId, recipientPeerId, messageId, payload)
            } catch (e: BufferUnderflowException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
