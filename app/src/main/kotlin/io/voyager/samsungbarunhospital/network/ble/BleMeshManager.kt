package io.voyager.samsungbarunhospital.network.ble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single facade the (Java) call-flow code talks to. Wires the GATT transport
 * ([BleGattServerManager]/[BleGattClientManager]) to the pure relay/dedup/fragmentation/
 * store-forward logic, and exposes only what MainActivity/CallQueueActivity/
 * AlertReceiverService need: start/stop, broadcastCall/broadcastCancel/sendConfirmed, and a
 * listener for incoming payload strings (the existing "TabletName|Message|CallId" wire format,
 * unchanged from TcpSender/AlertReceiverService).
 *
 * All public functions are no-ops when [getMode] resolves to [BleMode.UNSUPPORTED] — callers
 * don't need to guard every call site themselves, just check [isSupported] once for UI purposes.
 */
object BleMeshManager {

    interface ReceiveListener {
        fun onPayloadReceived(payload: String, fromPeerId: String)
    }

    @Volatile private var receiveListener: ReceiveListener? = null
    @Volatile private var started = false
    @Volatile private var mode: BleMode = BleMode.UNSUPPORTED
    @Volatile private var lastActivityAtMs: Long = 0L

    private var myPeerId: ByteArray = ByteArray(BleMeshConstants.PEER_ID_SIZE)

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectionManager = BleConnectionManager()
    private val dedupCache = BleDedupCache()
    private val reassembler = BleFragmentation.Reassembler()
    private val storeForward = BleStoreForwardCache()
    private var peerManager: BlePeerManager? = null

    private var serverManager: BleGattServerManager? = null
    private var clientManager: BleGattClientManager? = null
    private var announceJob: Job? = null

    @JvmStatic
    fun setReceiveListener(listener: ReceiveListener?) {
        receiveListener = listener
    }

    @JvmStatic
    fun getMode(context: Context): BleMode =
        BleModeResolver.resolveMode(android.os.Build.VERSION.SDK_INT, BlePermissions.hasAll(context))

    @JvmStatic
    fun isSupported(context: Context): Boolean = getMode(context) != BleMode.UNSUPPORTED

    /** For a lightweight admin-facing diagnostic panel — not used by the call flow itself. */
    @JvmStatic
    fun isRunning(): Boolean = started

    @JvmStatic
    fun getConnectionCount(): Int = connectionManager.count()

    /** Epoch ms of the last valid packet seen from another device, or 0 if none yet this session. */
    @JvmStatic
    fun getLastActivityAtMs(): Long = lastActivityAtMs

    @Synchronized
    @JvmStatic
    fun start(context: Context, myPeerIdHex: String) {
        if (started) return
        val resolvedMode = getMode(context)
        if (resolvedMode == BleMode.UNSUPPORTED) return

        mode = resolvedMode
        myPeerId = myPeerIdHex.hexToByteArray() ?: randomBytes(BleMeshConstants.PEER_ID_SIZE)
        val appContext = context.applicationContext

        val transportDelegate = object : BleTransportDelegate {
            override fun onRawPacketReceived(bytes: ByteArray, fromAddress: String) = handleRawBytes(bytes, fromAddress)
            override fun onDeviceConnected(address: String) {}
            override fun onDeviceDisconnected(address: String) {}
        }

        val peers = BlePeerManager(mainScope) { peerIdHex -> onPeerCameOnline(peerIdHex) }
        peers.startCleanup()
        peerManager = peers

        val client = BleGattClientManager(appContext, mainScope, connectionManager, transportDelegate)
        client.start()
        clientManager = client

        if (mode == BleMode.FULL) {
            val server = BleGattServerManager(appContext, mainScope, connectionManager, transportDelegate, myPeerId)
            server.start()
            serverManager = server
        }

        startAnnounceLoop()
        started = true
    }

    @Synchronized
    @JvmStatic
    fun stop() {
        if (!started) return
        started = false
        announceJob?.cancel()
        announceJob = null
        peerManager?.stopCleanup()
        peerManager?.clear()
        peerManager = null
        clientManager?.stop()
        clientManager = null
        serverManager?.stop()
        serverManager = null
        connectionManager.clear()
        dedupCache.clear()
        lastActivityAtMs = 0L
    }

    @JvmStatic
    fun broadcastCall(payload: String) = broadcast(BlePacketType.CALL, payload)

    @JvmStatic
    fun broadcastCancel(payload: String) = broadcast(BlePacketType.CANCEL, payload)

    /** Sends a unicast CONFIRMED reply, or caches it via [BleStoreForwardCache] if the recipient is currently offline. */
    @JvmStatic
    fun sendConfirmed(recipientPeerIdHex: String, payload: String) {
        if (!started) return
        val recipient = recipientPeerIdHex.hexToByteArray() ?: return
        val packet = BlePacket(
            type = BlePacketType.CONFIRMED,
            ttl = BleMeshConstants.DEFAULT_TTL,
            senderPeerId = myPeerId,
            recipientPeerId = recipient,
            messageId = randomBytes(BleMeshConstants.MESSAGE_ID_SIZE),
            payload = payload.toByteArray(Charsets.UTF_8)
        )
        if (peerManager?.isOnline(recipientPeerIdHex) == true) {
            sendPacket(packet, excludeAddress = null)
        } else {
            storeForward.offer(packet)
        }
    }

    private fun broadcast(type: BlePacketType, payload: String) {
        if (!started) return
        val packet = BlePacket(
            type = type,
            ttl = BleMeshConstants.DEFAULT_TTL,
            senderPeerId = myPeerId,
            recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
            messageId = randomBytes(BleMeshConstants.MESSAGE_ID_SIZE),
            payload = payload.toByteArray(Charsets.UTF_8)
        )
        sendPacket(packet, excludeAddress = null)
    }

    private fun sendPacket(packet: BlePacket, excludeAddress: String?) {
        for (fragment in BleFragmentation.fragment(packet)) {
            connectionManager.broadcast(
                fragment.encode(),
                serverManager?.getGattServer(),
                serverManager?.getCharacteristic(),
                excludeAddress
            )
        }
    }

    /**
     * Relay decision happens on the raw wire packet (which may be one FRAGMENT chunk of a larger
     * message) *before* reassembly, so every hop keeps flooding fragments onward even before it
     * personally has enough pieces to reassemble the whole message.
     */
    private fun handleRawBytes(bytes: ByteArray, fromAddress: String) {
        val packet = BlePacket.decode(bytes) ?: return
        if (packet.senderPeerId.contentEquals(myPeerId)) return // our own broadcast, echoed back by a relay
        if (dedupCache.isDuplicate(packet.messageId.toHex())) return

        lastActivityAtMs = System.currentTimeMillis()
        val senderHex = packet.senderPeerId.toHex()
        peerManager?.markSeen(senderHex)

        BlePacketRelay.decideRelay(packet, myPeerId)?.let { relayed ->
            connectionManager.broadcast(
                relayed.encode(),
                serverManager?.getGattServer(),
                serverManager?.getCharacteristic(),
                excludeAddress = fromAddress
            )
        }

        val effective = if (packet.type == BlePacketType.FRAGMENT) {
            reassembler.handleFragment(packet) ?: return
        } else {
            packet
        }

        if (effective.type == BlePacketType.ANNOUNCE) return

        val addressedToMe = effective.isBroadcast || effective.recipientPeerId.contentEquals(myPeerId)
        if (addressedToMe) {
            receiveListener?.onPayloadReceived(String(effective.payload, Charsets.UTF_8), senderHex)
        }
    }

    private fun onPeerCameOnline(peerIdHex: String) {
        for (packet in storeForward.flushFor(peerIdHex)) {
            sendPacket(packet, excludeAddress = null)
        }
    }

    private fun startAnnounceLoop() {
        announceJob?.cancel()
        announceJob = mainScope.launch {
            while (isActive) {
                val announce = BlePacket(
                    type = BlePacketType.ANNOUNCE,
                    ttl = BleMeshConstants.DEFAULT_TTL,
                    senderPeerId = myPeerId,
                    recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
                    messageId = randomBytes(BleMeshConstants.MESSAGE_ID_SIZE),
                    payload = ByteArray(0)
                )
                sendPacket(announce, excludeAddress = null)
                delay(BleMeshConstants.ANNOUNCE_INTERVAL_MS)
            }
        }
    }
}
