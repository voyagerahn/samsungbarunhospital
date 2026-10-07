package io.voyager.samsungbarunhospital.network.ble

import android.content.Context
import io.voyager.samsungbarunhospital.data.AppSettings
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
 * AlertReceiverService need: start/stop, sendCallToPeer/sendCancelToPeer/sendConfirmed
 * (all unicast to a known peerId, never a blind broadcast — see those functions' kdoc for why),
 * and a listener for incoming payload strings (the existing "TabletName|Message|CallId" wire
 * format, unchanged from TcpSender/AlertReceiverService).
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
    @Volatile private var appContext: Context? = null

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

    /** 권한과 별개로 블루투스 자체가 켜져 있는지 — 꺼져 있으면 BLE로는 아무것도 주고받을 수 없다. */
    @JvmStatic
    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

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
        // 블루투스가 꺼진 상태로 "실행 중" 표시만 남기지 않는다 — BleMeshService가 블루투스가
        // 켜지는 순간(ACTION_STATE_CHANGED) 다시 start()를 부른다.
        if (!isBluetoothEnabled(context)) return

        mode = resolvedMode
        myPeerId = myPeerIdHex.hexToByteArray() ?: randomBytes(BleMeshConstants.PEER_ID_SIZE)
        val appContext = context.applicationContext
        this.appContext = appContext

        val transportDelegate = object : BleTransportDelegate {
            override fun onRawPacketReceived(bytes: ByteArray, fromAddress: String) {
                // GATT 콜백 스레드(Binder)에서 곧장 호출되므로, dedup/relay(추가 GATT
                // write)/재조립/CallQueue 갱신 같은 무거운 처리를 여기서 동기 실행하면 그
                // 스레드를 오래 점유해 다른 GATT 콜백이 지연될 수 있다. 코루틴으로 넘겨서
                // 콜백 스레드는 즉시 반환하게 한다.
                mainScope.launch { handleRawBytes(bytes, fromAddress) }
            }
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

    // Java(@JvmStatic) 호출부는 suspend 함수를 직접 부를 수 없으므로, 여기서 mainScope에
    // launch만 하고 즉시 반환한다 — 실제 GATT write와 그 완료 대기는 코루틴 안에서 일어난다.
    //
    // CALL/CANCEL/CONFIRMED 전부 recipientPeerId를 BROADCAST가 아니라 특정 peerId로 지정해서
    // 보낸다(유니캐스트). 중간에 있는 기기들은 여전히 flood로 중계하지만(그래야 물리적으로 먼
    // 목적지까지 닿음), addressedToMe가 아닌 기기는 자기 화면에 표시하지 않고 그냥 넘긴다 —
    // 그래서 근처의 다른 환자 태블릿이 "나한테 온 호출"로 착각해 자기 화면에 알림을 띄우는
    // 일이 없다(bitchat의 recipientID/isPacketAddressedToMe와 같은 패턴).

    /**
     * 호출을 보낼 간호사 태블릿의 BLE peerId를 모르면 호출 못함 — peerId는 같은 Wi-Fi에서 TCP로
     * 학습하거나, 관리자 화면에서 근처 BLE 기기를 직접 골라(IP에 "블루투스 연결" 또는 BLE 전용
     * 간호사 등록) 얻는다.
     */
    @JvmStatic
    fun sendCallToPeer(recipientPeerIdHex: String, payload: String) =
        sendUnicast(BlePacketType.CALL, recipientPeerIdHex, payload, cacheIfOffline = false)

    @JvmStatic
    fun sendCancelToPeer(recipientPeerIdHex: String, payload: String) =
        sendUnicast(BlePacketType.CANCEL, recipientPeerIdHex, payload, cacheIfOffline = false)

    /** Sends a unicast CONFIRMED reply, or caches it via [BleStoreForwardCache] if the recipient is currently offline. */
    @JvmStatic
    fun sendConfirmed(recipientPeerIdHex: String, payload: String) =
        sendUnicast(BlePacketType.CONFIRMED, recipientPeerIdHex, payload, cacheIfOffline = true)

    /**
     * @param cacheIfOffline CONFIRMED만 true — CALL/CANCEL은 store-forward 대상이 아니다(그
     * 순간 도달 못하면 흘려보냄, CLAUDE.md의 "알려진 한계" 참고). hexToByteArray()는 짝수 길이만
     * 보장할 뿐 정확한 바이트 수는 보장하지 않으므로, 그 뒤 크기도 검증한다 — 안 그러면 아래
     * BlePacket 생성자의 require()가 던지는 예외를 "조용한 no-op"을 기대하는 호출부가 그대로 맞는다.
     */
    private fun sendUnicast(type: BlePacketType, recipientPeerIdHex: String, payload: String, cacheIfOffline: Boolean) {
        if (!started) return
        val recipient = recipientPeerIdHex.hexToByteArray() ?: return
        if (recipient.size != BleMeshConstants.PEER_ID_SIZE) return
        val packet = BlePacket(
            type = type,
            ttl = BleMeshConstants.DEFAULT_TTL,
            senderPeerId = myPeerId,
            recipientPeerId = recipient,
            messageId = randomBytes(BleMeshConstants.MESSAGE_ID_SIZE),
            payload = payload.toByteArray(Charsets.UTF_8)
        )
        if (cacheIfOffline && peerManager?.isOnline(recipientPeerIdHex) != true) {
            storeForward.offer(packet)
        } else {
            mainScope.launch { sendPacket(packet, excludeAddress = null) }
        }
    }

    /**
     * Fragments are sent one at a time, each awaiting the previous fragment's GATT write/notify
     * ack (see [BleConnectionManager.broadcast]) — Android only allows one outstanding write per
     * link, so firing them all at once risks silent loss on some OEM stacks.
     */
    private suspend fun sendPacket(packet: BlePacket, excludeAddress: String?) {
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
    private suspend fun handleRawBytes(bytes: ByteArray, fromAddress: String) {
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

        if (effective.type == BlePacketType.ANNOUNCE) {
            // 관리자 화면의 "근처 기기 검색"이 peerId만으로는 사람이 알아볼 수 없으니, ANNOUNCE에
            // 실려 온 태블릿 이름을 같이 기억해둔다(등록 화면에서 표시용).
            val name = String(effective.payload, Charsets.UTF_8).trim()
            if (name.isNotEmpty()) peerManager?.updateName(senderHex, name)
            return
        }

        val addressedToMe = effective.isBroadcast || effective.recipientPeerId.contentEquals(myPeerId)
        if (addressedToMe) {
            receiveListener?.onPayloadReceived(String(effective.payload, Charsets.UTF_8), senderHex)
        }
    }

    private fun onPeerCameOnline(peerIdHex: String) {
        // BlePeerManager.markSeen()이 이 콜백을 동기 호출하므로, 여기서 직접 suspend
        // sendPacket을 부르는 대신 코루틴으로 넘긴다.
        mainScope.launch {
            for (packet in storeForward.flushFor(peerIdHex)) {
                sendPacket(packet, excludeAddress = null)
            }
        }
    }

    private fun buildAnnouncePacket(): BlePacket {
        // 태블릿 이름을 실어 보낸다 — "근처 기기 검색" 화면에서 peerId 대신 이 이름으로
        // 사람이 알아볼 수 있게. 매번 새로 읽어서 관리자가 이름을 바꾸면 다음 ANNOUNCE부터 반영됨.
        val name = appContext?.let { AppSettings.getTabletName(it) } ?: ""
        return BlePacket(
            type = BlePacketType.ANNOUNCE,
            ttl = BleMeshConstants.DEFAULT_TTL,
            senderPeerId = myPeerId,
            recipientPeerId = BleMeshConstants.BROADCAST_RECIPIENT,
            messageId = randomBytes(BleMeshConstants.MESSAGE_ID_SIZE),
            payload = name.toByteArray(Charsets.UTF_8)
        )
    }

    private fun startAnnounceLoop() {
        announceJob?.cancel()
        announceJob = mainScope.launch {
            while (isActive) {
                sendPacket(buildAnnouncePacket(), excludeAddress = null)
                delay(BleMeshConstants.ANNOUNCE_INTERVAL_MS)
            }
        }
    }

    /** 관리자가 "근처 기기 검색"을 열었을 때 즉시 한 번 더 알려서, 30초 주기를 기다리지 않고도
     *  검색 목록이 빨리 채워지게 한다. */
    @JvmStatic
    fun triggerAnnounceNow() {
        if (!started) return
        mainScope.launch { sendPacket(buildAnnouncePacket(), excludeAddress = null) }
    }

    /** peerId(hex)와 마지막으로 들은 이름(없으면 null). 관리자 화면의 "근처 기기 검색" 목록용. */
    class DiscoveredPeer(@JvmField val peerId: String, @JvmField val name: String?)

    @JvmStatic
    fun getDiscoveredPeers(): List<DiscoveredPeer> {
        val peers = peerManager ?: return emptyList()
        return peers.listOnline().map { DiscoveredPeer(it.peerIdHex, it.name) }
    }
}
