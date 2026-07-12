package io.falab.samsungbarunhospital.network.ble

import java.nio.ByteBuffer

/**
 * Splits an oversized [BlePacket] into FRAGMENT packets and reassembles them on the receiving
 * side. Adapted from bitchat-android's FragmentManager/FragmentingPacketSender
 * (app/src/main/java/com/bitchat/android/mesh/FragmentManager.kt,
 * FragmentingPacketSender.kt). Nurse-call messages are almost always short, but this stays in
 * as a safety margin for long custom call text and for MTU variance across OEM BLE stacks.
 *
 * Fragment payload layout (big-endian): fragmentId(4) fragIndex(1) fragCount(1)
 * originalType(1) originalMessageId(16) chunk(rest).
 */
object BleFragmentation {

    private const val ENVELOPE_HEADER_SIZE = 4 + 1 + 1 + 1 + BleMeshConstants.MESSAGE_ID_SIZE

    fun shouldFragment(packet: BlePacket): Boolean =
        packet.encode().size > BleMeshConstants.FRAGMENT_THRESHOLD_BYTES

    /** Returns [packet] unchanged (as a single-element list) if it doesn't need fragmenting. */
    fun fragment(packet: BlePacket): List<BlePacket> {
        if (!shouldFragment(packet)) return listOf(packet)

        val fragmentId = randomBytes(4)
        val chunks = packet.payload.toList().chunked(BleMeshConstants.FRAGMENT_CHUNK_BYTES)
        val fragCount = chunks.size
        require(fragCount in 1..255) { "payload too large to fragment into <=255 chunks" }

        return chunks.mapIndexed { index, chunkList ->
            val chunk = chunkList.toByteArray()
            val envelope = ByteBuffer.allocate(ENVELOPE_HEADER_SIZE + chunk.size).apply {
                put(fragmentId)
                put(index.toByte())
                put(fragCount.toByte())
                put(packet.type.value)
                put(packet.messageId)
                put(chunk)
            }.array()

            BlePacket(
                type = BlePacketType.FRAGMENT,
                ttl = packet.ttl,
                senderPeerId = packet.senderPeerId,
                recipientPeerId = packet.recipientPeerId,
                messageId = randomBytes(BleMeshConstants.MESSAGE_ID_SIZE), // per-fragment dedup id
                payload = envelope
            )
        }
    }

    private data class Envelope(
        val fragmentId: ByteArray,
        val fragIndex: Int,
        val fragCount: Int,
        val originalType: BlePacketType,
        val originalMessageId: ByteArray,
        val chunk: ByteArray
    )

    private fun parseEnvelope(bytes: ByteArray): Envelope? {
        if (bytes.size < ENVELOPE_HEADER_SIZE) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes)
            val fragmentId = ByteArray(4).also { buffer.get(it) }
            val fragIndex = buffer.get().toInt() and 0xFF
            val fragCount = buffer.get().toInt() and 0xFF
            val originalType = BlePacketType.fromValue(buffer.get()) ?: return null
            val originalMessageId = ByteArray(BleMeshConstants.MESSAGE_ID_SIZE).also { buffer.get(it) }
            val chunk = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (fragCount <= 0 || fragIndex !in 0 until fragCount) return null
            Envelope(fragmentId, fragIndex, fragCount, originalType, originalMessageId, chunk)
        } catch (e: Exception) {
            null
        }
    }

    /** Stateful reassembly buffer — one instance per BLE mesh session. */
    class Reassembler(
        private val timeoutMs: Long = BleMeshConstants.FRAGMENT_REASSEMBLY_TIMEOUT_MS,
        private val clock: () -> Long = System::currentTimeMillis
    ) {
        private class PartialMessage(val fragCount: Int, val createdAt: Long) {
            val chunks: Array<ByteArray?> = arrayOfNulls(fragCount)
            var receivedCount = 0
        }

        private val partials = HashMap<String, PartialMessage>()

        /**
         * @param fragmentPacket a packet of type FRAGMENT received off the wire.
         * @return the fully reassembled original packet once every fragment has arrived, else null.
         * Never throws — malformed fragments are silently dropped.
         */
        @Synchronized
        fun handleFragment(fragmentPacket: BlePacket): BlePacket? {
            purgeExpired()
            val envelope = parseEnvelope(fragmentPacket.payload) ?: return null
            val key = fragmentPacket.senderPeerId.toHex() + ":" + envelope.fragmentId.toHex()

            val partial = partials.getOrPut(key) { PartialMessage(envelope.fragCount, clock()) }
            if (partial.fragCount != envelope.fragCount) return null

            if (partial.chunks[envelope.fragIndex] == null) {
                partial.chunks[envelope.fragIndex] = envelope.chunk
                partial.receivedCount++
            }
            if (partial.receivedCount < partial.fragCount) return null

            partials.remove(key)
            val totalSize = partial.chunks.sumOf { it!!.size }
            val payload = ByteArray(totalSize)
            var offset = 0
            for (c in partial.chunks) {
                c!!.copyInto(payload, offset)
                offset += c.size
            }

            return BlePacket(
                type = envelope.originalType,
                ttl = 0, // already fully local at this point; not meant to be relayed further
                senderPeerId = fragmentPacket.senderPeerId,
                recipientPeerId = fragmentPacket.recipientPeerId,
                messageId = envelope.originalMessageId,
                payload = payload
            )
        }

        @Synchronized
        fun pendingCount(): Int = partials.size

        private fun purgeExpired() {
            val cutoff = clock() - timeoutMs
            partials.entries.removeIf { it.value.createdAt < cutoff }
        }
    }
}
