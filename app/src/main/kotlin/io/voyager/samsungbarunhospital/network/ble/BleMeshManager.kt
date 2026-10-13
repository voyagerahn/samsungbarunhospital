package io.voyager.samsungbarunhospital.network.ble

import android.content.Context
import io.voyager.samsungbarunhospital.data.AppSettings
import io.voyager.samsungbarunhospital.data.DiagLog
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
    // 수신 확인(ACK)을 기다리는 보낸 메시지들. stop()에서 비우지 않는다 — 블루투스를 껐다 켜거나
    // 연결이 끊겼다 돌아온 뒤에도 이어서 보내야 하기 때문.
    private val outbox = BleReliableOutbox()
    private val outboxLock = Any()
    private var outboxJob: Job? = null

    // 중계한 호출·취소·확인응답의 messageId → 마지막으로 중계한 시각 (재전송분 중계의 빈도 제한용).
    private val relayedAt = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > 200
    }

    // 이미 처리한(화면에 반영한) 호출·취소·확인응답의 messageId → 처리 시각. 같은 메시지가 재전송으로
    // 다시 오면 ACK만 돌려주고 두 번 처리하지 않는다.
    private val delivered = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > BleMeshConstants.DELIVERED_MEMORY_MAX
    }

    /** 보낸 호출이 간호사 태블릿에 도착했다는 확인을 받았을 때 알림(메인 스레드 아님 — UI는 호출부에서 넘길 것). */
    interface DeliveryListener {
        /** @param callId 전달이 확인된 호출의 id */
        fun onCallDelivered(callId: String)
        /** 끝내 전달을 확인받지 못하고 포기한 호출(기한 초과 등) — Wi-Fi 경로의 "전달 실패"에 해당. */
        fun onCallUndelivered(callId: String)
    }

    @Volatile private var deliveryListener: DeliveryListener? = null

    @JvmStatic
    fun setDeliveryListener(listener: DeliveryListener?) {
        deliveryListener = listener
    }
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

    /** 지금 실제로 데이터를 내보낼 수 있는 연결 수 — 0이면 보낸 호출은 다시 연결될 때까지 기다렸다 나간다. */
    @JvmStatic
    fun getWritableConnectionCount(): Int = connectionManager.writableCount()

    // ── 연결 수 한도 (BleLinkLimit / BleConnectionManager의 포화 상태) ─────────

    /** 지금 적용 중인 한도 — 진단 표시용. */
    @JvmStatic
    fun getMaxLinks(): Int = connectionManager.maxLinks

    /** 칩이 알려준 한도(지금 포화 상태일 때만). 아니면 0 — 진단 표시용. */
    @JvmStatic
    fun getObservedLinkLimit(): Int = connectionManager.saturatedAt().coerceAtLeast(0)

    /** 칩이 알려준 한도만큼 지금 다 차 있는지 — "더 추가할 수 없음" 안내용. */
    @JvmStatic
    fun isLinkLimitReached(): Boolean = connectionManager.isSaturated()

    /** 연결 중 이 태블릿이 건 것의 수(나머지는 상대가 걸어온 것) — 진단용. */
    @JvmStatic
    fun getOutgoingConnectionCount(): Int = connectionManager.all().count { it.isClient }

    /** "다른 태블릿이 이 태블릿을 찾을 수 있게 하기"(광고)의 마지막 실패 코드. 정상이면 0. */
    @JvmStatic
    fun getAdvertiseErrorCode(): Int = serverManager?.advertiseErrorCode() ?: 0

    /** 주변 기기 검색이 연속으로 몇 번 실패했는지(정상이면 0). */
    @JvmStatic
    fun getScanFailureStreak(): Int = clientManager?.scanFailureStreak() ?: 0

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
        if (!isBluetoothEnabled(context)) {
            DiagLog.log("BLE상태", "블루투스가 꺼져 있어 BLE를 시작하지 못함 (켜지면 자동 시작)")
            return
        }

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
            override fun onDeviceConnected(address: String) {
                // 링크가 준비되자마자 ANNOUNCE를 보낸다(bitchat과 동일). 30초 주기만 기다리면, 다시
                // 연결된 뒤에도 서로 누구인지 알고 보관해둔 호출을 내보내기까지 최대 30초가 걸린다.
                mainScope.launch {
                    delay(200)
                    sendPacket(buildAnnouncePacket(), excludeAddress = null)
                }
                serverManager?.onLinkGained()
                wakeOutbox() // 기다리던 메시지가 이제 나갈 수 있을지도 모른다
            }
            override fun onDeviceDisconnected(address: String) {
                // 복구는 시계가 아니라 이 사건이 계기다: 끊긴 순간 다시 찾고(스캔), 다시 찾을 수 있게 한다(광고).
                clientManager?.onLinkLost()
                serverManager?.onLinkLost()
            }
        }

        val peers = BlePeerManager(mainScope) { peerIdHex -> onPeerCameOnline(peerIdHex) }
        peers.startCleanup()
        peerManager = peers

        val client = BleGattClientManager(
            appContext, mainScope, connectionManager, transportDelegate,
            registeredNurses = { registeredNursePeerIds() },
            evictServerLink = { address -> serverManager?.disconnectDevice(address) }
        )
        client.start()
        clientManager = client

        if (mode == BleMode.FULL) {
            val server = BleGattServerManager(appContext, mainScope, connectionManager, transportDelegate, myPeerId)
            server.start()
            serverManager = server
        }

        startAnnounceLoop()
        started = true
        ensureOutboxLoop() // 꺼져 있는 동안 쌓인 메시지가 있으면 이어서 보낸다
    }

    @Synchronized
    @JvmStatic
    fun stop() {
        if (!started) return
        started = false
        announceJob?.cancel()
        announceJob = null
        synchronized(outboxLock) {
            outboxJob?.cancel()
            outboxJob = null
        }
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
    // 목적지까지 닿음 — 환자 → 환자 → 간호사), addressedToMe가 아닌 기기는 자기 화면에 표시하지
    // 않고 그냥 넘긴다 — 그래서 최종 도착지는 항상 지정한 기기 하나뿐이고, 근처의 다른 환자 태블릿이
    // "나한테 온 호출"로 착각해 알림을 띄우는 일이 없다(bitchat의 recipientID/isPacketAddressedToMe).
    //
    // 블루투스 경로는 Wi-Fi(TCP) 경로와 "통신 수단만 다르고 동작은 같아야" 한다(사용자 요구사항).
    // TCP는 받는 쪽이 "ACK"를 돌려줘서 전달 여부를 아는데, 블루투스에도 같은 수신 확인(ACK 패킷)을
    // 두고 확인이 올 때까지 다시 보낸다 — sendUnicast / BleReliableOutbox 참고.

    /**
     * 호출을 보낼 간호사 태블릿의 BLE peerId를 모르면 호출 못함 — peerId는 같은 Wi-Fi에서 TCP로
     * 학습하거나, 관리자 화면에서 근처 BLE 기기를 직접 골라(IP에 "블루투스 연결" 또는 BLE 전용
     * 간호사 등록) 얻는다.
     */
    @JvmStatic
    fun sendCallToPeer(recipientPeerIdHex: String, payload: String) =
        sendUnicast(BlePacketType.CALL, recipientPeerIdHex, payload)

    @JvmStatic
    fun sendCancelToPeer(recipientPeerIdHex: String, payload: String) =
        sendUnicast(BlePacketType.CANCEL, recipientPeerIdHex, payload)

    /** Sends a unicast CONFIRMED reply (kept and resent later if the patient tablet isn't reachable right now). */
    @JvmStatic
    fun sendConfirmed(recipientPeerIdHex: String, payload: String) =
        sendUnicast(BlePacketType.CONFIRMED, recipientPeerIdHex, payload)

    /**
     * 호출·취소·확인응답을 [recipientPeerIdHex]에게 "확인받을 때까지" 보낸다:
     * - 닿을 수 있으면(직접 링크가 있거나 중계를 거쳐 최근에 감지됨) 바로 보내고, ACK가 올 때까지
     *   간격을 늘려가며 다시 보낸다.
     * - 닿을 수 없으면 버리지 않고 기다렸다가, 다시 닿는 순간 보낸다([pumpOutbox]가 1초마다 살핀다).
     *   예전에는 그대로 버려서, 연결이 끊긴 사이의 호출은 환자 화면에만 남고 간호사에게는 영영 안 갔다.
     * - CANCEL은, 같은 호출이 아직 한 번도 못 나갔으면 그 호출만 거두고 끝낸다(둘 다 안 보냄).
     *   나간 적이 있으면 호출 재전송을 멈추고 CANCEL을 같은 방식으로 확인받을 때까지 보낸다.
     *
     * hexToByteArray()는 짝수 길이만 보장할 뿐 정확한 바이트 수는 보장하지 않으므로, 그 뒤 크기도
     * 검증한다 — 안 그러면 BlePacket 생성자의 require()가 던지는 예외를 호출부가 그대로 맞는다.
     */
    private fun sendUnicast(type: BlePacketType, recipientPeerIdHex: String, payload: String) {
        val what = describe(type, payload)
        val recipientHex = recipientPeerIdHex.lowercase()
        val to = recipientHex.take(8)
        val recipient = recipientHex.hexToByteArray()
        if (recipient == null || recipient.size != BleMeshConstants.PEER_ID_SIZE) {
            DiagLog.log("BLE송신", "$what 못 보냄: 받는 기기 ID 형식 오류($to)")
            return
        }
        val callId = callIdOf(type, payload)
        if (type == BlePacketType.CANCEL && callId != null) {
            val pendingCalls = outbox.removeWhere { it == outboxKey(BlePacketType.CALL, callId, recipientHex) }
            if (pendingCalls.isNotEmpty() && pendingCalls.all { it.attempts == 0 }) {
                DiagLog.log("BLE송신", "$what → $to 호출이 아직 못 나간 상태에서 취소됨 — 호출을 거두고 둘 다 보내지 않음")
                return
            }
        }
        val packet = BlePacket(
            type = type,
            ttl = BleMeshConstants.DEFAULT_TTL,
            senderPeerId = myPeerId,
            recipientPeerId = recipient,
            messageId = randomBytes(BleMeshConstants.MESSAGE_ID_SIZE),
            payload = payload.toByteArray(Charsets.UTF_8)
        )
        // 조각까지 한 번만 만들어 두고 재전송 때 똑같은 바이트를 다시 쓴다(BleReliableOutbox 주석 참고).
        val frames = BleFragmentation.fragment(packet).map { it.encode() }
        val dropped = outbox.add(packet.messageId.toHex(), recipientHex, frames, outboxKey(type, callId, recipientHex), what)
        for (entry in dropped) giveUp(entry, "확인을 기다리는 메시지가 너무 많아 가장 오래된 것부터 포기")
        wakeOutbox()

        // 기록은 실패 이벤트만 남긴다.
        if (!started) {
            DiagLog.log("BLE송신", "$what → $to 지금은 못 나감: BLE가 실행 중이 아님 — 블루투스가 켜지고 다시 연결되면 자동 전송")
        } else if (!isReachable(recipientHex)) {
            val reason = if (connectionManager.writableCount() == 0) "블루투스로 연결된 기기가 0대" else "받는 기기와 연결돼 있지 않고 근처에서도 감지되지 않음"
            DiagLog.log("BLE송신", "$what → $to 지금은 못 나감: $reason — 다시 연결되면 자동 전송 (최대 ${BleMeshConstants.DELIVERY_TIMEOUT_MS / 60000}분)")
        }
        ensureOutboxLoop()
    }

    /** 호출 하나를 가리키는 표식. callId가 없는 메시지(CONFIRMED)는 서로 구분할 필요가 없어 종류만 쓴다. */
    private fun outboxKey(type: BlePacketType, callId: String?, recipientHex: String) =
        "${type.name}:${callId ?: "-"}@$recipientHex"

    /**
     * [callId] 호출을 더 보낼 필요가 없을 때(간호사가 확인함) 재전송을 멈춘다. ACK를 못 받은 채로 간호사의
     * 확인응답만 도착한 경우에도, 이미 처리된 호출을 계속 다시 보내지 않게 한다.
     */
    @JvmStatic
    fun stopCall(callId: String) {
        outbox.removeWhere { it.startsWith("${BlePacketType.CALL.name}:$callId@") }
    }

    /** [peerIdHex]에게 지금 패킷이 닿을 수 있는지 — 직접 링크가 있거나, (중계 경유로) 최근에 감지됐고 내보낼 링크가 있음. */
    private fun isReachable(peerIdHex: String): Boolean =
        connectionManager.isPeerLinked(peerIdHex) ||
            (peerManager?.isOnline(peerIdHex) == true && connectionManager.writableCount() > 0)

    // 재전송 루프를 깨우는 신호. 받는 기기에 닿을 수 있게 되는 사건(연결됨·누구인지 확인됨·다시 감지됨)이나
    // 새 메시지가 들어왔을 때 보낸다.
    private val outboxSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private fun wakeOutbox() {
        outboxSignal.trySend(Unit)
    }

    /**
     * 확인을 기다리는 메시지가 있는 동안에만 도는 재전송 루프. 주기적으로 깨어나 살피지 않는다 — 다음에 할 일이
     * 있는 시각(다음 재전송 또는 만료)까지 자고, 그 전에 상황이 바뀌면 [wakeOutbox]로 깨운다. 받는 기기에
     * 닿을 수 없는 동안은 닿게 되는 사건이 올 때까지 아무것도 하지 않는다. 목록이 비면 스스로 끝나고, BLE가
     * 꺼지면([stop]) 멈췄다가 다시 켜질 때([start]) 남은 것이 있으면 이어서 돈다.
     */
    private fun ensureOutboxLoop() {
        synchronized(outboxLock) {
            if (outboxJob != null || !started || outbox.isEmpty()) return
            outboxJob = mainScope.launch {
                while (true) {
                    pumpOutbox()
                    val waitMs = synchronized(outboxLock) {
                        val next = if (started) outbox.nextWakeDelayMs { isReachable(it) } else null
                        if (next == null) outboxJob = null
                        next
                    } ?: return@launch
                    kotlinx.coroutines.withTimeoutOrNull(waitMs.coerceAtLeast(50L)) { outboxSignal.receive() }
                }
            }
        }
    }

    private fun giveUp(entry: BleReliableOutbox.Entry, reason: String) {
        DiagLog.log("BLE송신", "${entry.what} → ${entry.recipientHex.take(8)} 전달 실패: $reason (${entry.attempts}회 보냄)")
        callIdOfKey(entry.key)?.let { deliveryListener?.onCallUndelivered(it) }
    }

    /** 호출(CALL) 항목의 표식에서 callId를 꺼낸다. 호출이 아니면 null. */
    private fun callIdOfKey(key: String): String? {
        if (!key.startsWith("${BlePacketType.CALL.name}:")) return null
        return key.substringAfter(':').substringBefore('@').takeIf { it != "-" }
    }

    /** 기한이 지난 것은 정리하고, 지금 보낼 차례인 것을 내보낸다. (재전송 루프에서만 부른다) */
    private suspend fun pumpOutbox() {
        run {
            for (expired in outbox.takeExpired()) {
                giveUp(expired, "${BleMeshConstants.DELIVERY_TIMEOUT_MS / 60000}분 동안 수신 확인을 받지 못해 중단")
            }
            if (!started) return
            for (entry in outbox.takeDue { isReachable(it) }) {
                for (frame in entry.frames) {
                    connectionManager.broadcast(frame, serverManager?.getGattServer(), serverManager?.getCharacteristic(), excludeAddress = null)
                }
                // 계속 확인이 안 오는 경우를 기록으로 남긴다(처음 몇 번은 정상 범위라 생략).
                if (entry.attempts == 6 || (entry.attempts > 6 && entry.attempts % 30 == 0)) {
                    DiagLog.log("BLE송신", "${entry.what} → ${entry.recipientHex.take(8)} ${entry.attempts}회 보냈지만 아직 수신 확인 없음 — 계속 재전송 중")
                }
            }
        }
    }

    /** 받은 호출·취소·확인응답에 대한 수신 확인을 보낸 기기에게 돌려준다. */
    private suspend fun sendAck(toPeerId: ByteArray, ackedMessageId: ByteArray) {
        val ack = BlePacket(
            type = BlePacketType.ACK,
            ttl = BleMeshConstants.DEFAULT_TTL,
            senderPeerId = myPeerId,
            recipientPeerId = toPeerId,
            messageId = randomBytes(BleMeshConstants.MESSAGE_ID_SIZE),
            payload = ackedMessageId.toHex().toByteArray(Charsets.UTF_8)
        )
        sendPacket(ack, excludeAddress = null)
    }

    private fun onAckReceived(ackedMessageIdHex: String) {
        val entry = outbox.ack(ackedMessageIdHex) ?: return
        // 한 번에 안 된 경우만 기록한다(정상 전달은 기록하지 않는 원칙) — 끊겼다가 복구된 흔적이 된다.
        if (entry.waitedUnreachable || entry.attempts > 3) {
            DiagLog.log("BLE송신", "${entry.what} → ${entry.recipientHex.take(8)} 전달 확인됨 (${entry.attempts}회 보냄" +
                (if (entry.waitedUnreachable) ", 연결이 돌아올 때까지 기다렸음" else "") + ")")
        }
        callIdOfKey(entry.key)?.let { deliveryListener?.onCallDelivered(it) }
    }

    /**
     * 같은 상대와 주고받을 수 있는 링크가 둘 이상이면 하나만 남긴다. 연결 자리는 기기의 블루투스 칩이
     * 정한 만큼뿐이라(현장 태블릿은 4대에서 가득 참) 한 쌍이 두 자리를 쓰면 그만큼 다른 태블릿이 못 붙는다.
     * 스캔 단계에서 대부분 걸러지지만, 두 기기가 동시에 서로에게 걸면 둘 다 성립한다.
     * - 우리가 건 링크가 여러 개(상대의 광고 주소가 바뀌어 또 연결된 경우): 가장 새것만 남긴다.
     * - 우리가 건 것 하나 + 상대가 건 것: 양쪽이 같은 결론을 내도록 peerId가 작은 쪽이 자기가 건 링크를 끊는다.
     */
    private fun trimDuplicateLinks(peerIdHex: String) {
        val links = connectionManager.writableLinksTo(peerIdHex)
        if (links.size < 2) return
        val mine = links.filter { it.isClient }.sortedBy { it.connectedAt }
        val toDrop = when {
            mine.size >= 2 -> mine.dropLast(1)
            mine.size == 1 && myPeerId.toHex() < peerIdHex -> mine
            else -> emptyList()
        }
        toDrop.forEach { clientManager?.disconnect(it.address) }
    }

    private suspend fun relay(packet: BlePacket, fromAddress: String) {
        val relayed = BlePacketRelay.decideRelay(packet, myPeerId) ?: return
        connectionManager.broadcast(relayed.encode(), serverManager?.getGattServer(), serverManager?.getCharacteristic(), excludeAddress = fromAddress)
    }

    /** 같은 메시지를 너무 자주 중계하지 않도록 — 마지막 중계 후 충분히 지났으면 true(그리고 시각 갱신). */
    private fun mayRelayAgain(messageIdHex: String): Boolean = synchronized(relayedAt) {
        val now = System.currentTimeMillis()
        val last = relayedAt[messageIdHex]
        if (last != null && now - last < BleMeshConstants.RELAY_REPEAT_MIN_INTERVAL_MS) return false
        relayedAt[messageIdHex] = now
        true
    }

    /** 이미 처리한 메시지인지 확인하고, 아니면 처리한 것으로 기록한다. @return 처음 보는 메시지면 true */
    private fun markDeliveredIfNew(messageIdHex: String): Boolean = synchronized(delivered) {
        val now = System.currentTimeMillis()
        delivered.entries.removeAll { now - it.value > BleMeshConstants.DELIVERED_MEMORY_MS }
        if (delivered.containsKey(messageIdHex)) return false
        delivered[messageIdHex] = now
        true
    }

    private fun isControl(type: BlePacketType) =
        type == BlePacketType.CALL || type == BlePacketType.CANCEL || type == BlePacketType.CONFIRMED

    /** payload에서 callId만 뽑는다 — CALL은 "이름|메시지|callId", CANCEL은 "CANCEL|callId". */
    private fun callIdOf(type: BlePacketType, payload: String): String? {
        val parts = payload.split("|")
        return when (type) {
            BlePacketType.CALL -> parts.getOrNull(2)
            BlePacketType.CANCEL -> parts.getOrNull(1)
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
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
        val senderHex = packet.senderPeerId.toHex()

        // 중계되지 않고(TTL이 그대로) 도착한 ANNOUNCE는 "이 링크 건너편이 이 기기"라는 뜻이다. 그 사실을
        // 링크에 기록해 둬야 같은 상대에게 중복으로 연결하지 않고, 등록된 간호사와 직접 연결돼 있는지
        // 판단할 수 있다(bitchat-android의 DirectLinkAnnouncementPolicy). 중복 검사보다 먼저 한다 —
        // 같은 ANNOUNCE가 두 링크로 오면 나중 것은 중복으로 버려지는데, 그 링크도 기록은 해야 한다.
        if (packet.type == BlePacketType.ANNOUNCE && packet.ttl == BleMeshConstants.DEFAULT_TTL) {
            connectionManager.bindPeer(fromAddress, senderHex)
            trimDuplicateLinks(senderHex)
            wakeOutbox() // 이 링크가 누구와의 것인지 알게 됐다 — 그 기기를 기다리던 메시지를 보낼 수 있다
        }
        // 나에게 온 호출·취소·확인응답이면, 이미 받은 것의 재전송이더라도 수신 확인은 매번 돌려준다 —
        // 앞서 보낸 확인이 중간에 사라졌을 수 있고, 확인을 못 받은 상대는 계속 다시 보내기 때문.
        // 단, 이 기기가 아직 처리할 준비가 안 됐으면(수신 처리기 미등록) 확인을 보내지 않는다 — "받았다"고
        // 답해놓고 버리면 상대는 전달된 줄 알고 멈춘다. 답하지 않으면 준비될 때까지 계속 다시 보내준다.
        // 확인 전송은 따로 띄운다: 끊긴 링크의 쓰기 대기(최대 2초) 때문에 알람 표시가 늦어지면 안 된다.
        val forMe = !packet.isBroadcast && packet.recipientPeerId.contentEquals(myPeerId)
        val listener = receiveListener
        if (forMe && isControl(packet.type) && listener != null) {
            mainScope.launch { sendAck(packet.senderPeerId, packet.messageId) }
        }

        val messageIdHex = packet.messageId.toHex()
        if (dedupCache.isDuplicate(messageIdHex)) {
            // 이미 본 패킷은 원래 여기서 버린다(중계 폭주·순환 방지). 다만 호출·취소·확인응답의 재전송은
            // 계속 중계해야 한다 — 안 그러면 환자 → 환자 → 간호사 경로에서 첫 번째가 중간에 사라졌을 때,
            // 보낸 쪽이 아무리 다시 보내도 중간 태블릿이 "이미 본 것"이라며 막아 간호사에게 닿지 못한다.
            // 순환은 TTL(홉마다 감소)과 빈도 제한으로 막는다.
            if (!forMe && isControl(packet.type) && mayRelayAgain(messageIdHex)) relay(packet, fromAddress)
            // 나에게 온 것의 재전송: 처음 왔을 때 처리하지 못했다면(수신 처리기 미등록 등) 지금 처리한다.
            // 이미 처리한 것이면 deliverControl이 걸러낸다.
            if (forMe && isControl(packet.type)) deliverControl(packet, senderHex, listener, logIfUnready = false)
            return
        }

        lastActivityAtMs = System.currentTimeMillis()
        peerManager?.markSeen(senderHex)

        if (isControl(packet.type)) mayRelayAgain(messageIdHex) // 첫 중계 시각 기록
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
        val payloadText = String(effective.payload, Charsets.UTF_8)
        if (!addressedToMe) return // 다른 기기 앞 — 중계만 하고 표시 안 함(정상)

        if (effective.type == BlePacketType.ACK) {
            onAckReceived(payloadText.trim())
            return
        }
        if (!isControl(effective.type)) return
        // 조각으로 나뉘어 온 경우는 위(조각 단위)에서 확인을 못 보냈으니 여기서 보낸다.
        if (packet.type == BlePacketType.FRAGMENT && listener != null) {
            mainScope.launch { sendAck(effective.senderPeerId, effective.messageId) }
        }
        deliverControl(effective, senderHex, listener, logIfUnready = true)
    }

    /** 나에게 온 호출·취소·확인응답을 화면 쪽(수신 처리기)으로 넘긴다 — 같은 메시지는 한 번만. */
    private fun deliverControl(effective: BlePacket, senderHex: String, listener: ReceiveListener?, logIfUnready: Boolean) {
        val payloadText = String(effective.payload, Charsets.UTF_8)
        if (listener == null) {
            if (logIfUnready) {
                DiagLog.log("BLE수신", "${describe(effective.type, payloadText)} 받았지만 아직 처리 못함: 수신 처리기 미등록 — 수신 확인을 보내지 않았으므로 상대가 다시 보냄 (보낸 기기 ${senderHex.take(8)})")
            }
            return
        }
        // 일반 중복 검사 창(5분)을 넘겨 다시 온 재전송도 한 번만 처리한다.
        if (!markDeliveredIfNew(effective.messageId.toHex())) return
        listener.onPayloadReceived(payloadText, senderHex)
    }

    private fun onPeerCameOnline(peerIdHex: String) {
        wakeOutbox() // (중계를 거쳐서라도) 다시 감지됐다 — 그 기기를 기다리던 메시지를 보낼 수 있다
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

    /** 로그용 한 줄 요약 — 호출 메시지 내용(환자 요청 문구)은 남기지 않고 종류와 callId 앞 8자만. */
    private fun describe(type: BlePacketType, payload: String): String {
        val parts = payload.split("|")
        return when (type) {
            BlePacketType.CALL -> "호출 #${parts.getOrNull(2)?.take(8) ?: "-"}"
            BlePacketType.CANCEL -> "취소 #${parts.getOrNull(1)?.take(8) ?: "-"}"
            BlePacketType.CONFIRMED -> "확인응답"
            BlePacketType.ACK -> "수신확인"
            else -> type.name
        }
    }

    // ── 관리자 "진단" 탭 자가진단용 조회 ──────────────────────────────────

    /** 실제로 광고(다른 기기가 이 기기를 찾을 수 있는 상태)가 켜져 있는지. */
    @JvmStatic
    fun isAdvertising(): Boolean = serverManager?.isAdvertising() == true

    /**
     * 하드웨어가 BLE 광고를 지원하는지. 블루투스가 꺼져 있거나 어댑터 자체가 없으면 판단할 수 없어
     * null — "하드웨어 미지원"으로 잘못 단정하면 현장에서 엉뚱한 원인을 쫓게 된다.
     */
    @JvmStatic
    fun isAdvertisingHardwareSupported(context: Context): Boolean? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val adapter = manager?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.isMultipleAdvertisementSupported
    }

    @JvmStatic
    fun isPeerOnline(peerIdHex: String): Boolean = peerManager?.isOnline(peerIdHex.lowercase()) == true

    /** [peerIdHex]와 직접(다른 기기를 거치지 않고) 주고받을 수 있는 링크가 있는지. */
    @JvmStatic
    fun isPeerDirectlyLinked(peerIdHex: String): Boolean = connectionManager.isPeerLinked(peerIdHex.lowercase())

    /** [peerIdHex]에게 지금 호출이 바로 나갈 수 있는지(아니면 다시 닿을 때까지 기다렸다 나간다). */
    @JvmStatic
    fun isPeerReachable(peerIdHex: String): Boolean = isReachable(peerIdHex.lowercase())

    /** 수신 확인을 기다리며 재전송 중인 메시지 수(진단용). */
    @JvmStatic
    fun getPendingDeliveryCount(): Int = outbox.size()

    // 이 기기에 등록된 간호사 태블릿들의 peerId. 스캔 결과마다 참조되므로(초당 여러 번) 설정 저장소를
    // 매번 읽지 않고 잠깐 캐시한다 — 관리자가 등록을 바꾸면 수 초 안에 반영된다.
    private const val NURSE_CACHE_MS = 5_000L
    @Volatile private var nurseCache: Set<String> = emptySet()
    @Volatile private var nurseCachedAt = 0L

    private fun registeredNursePeerIds(): Set<String> {
        val ctx = appContext ?: return emptySet()
        val now = System.currentTimeMillis()
        if (now - nurseCachedAt < NURSE_CACHE_MS) return nurseCache
        val ids = HashSet<String>()
        for (nurse in AppSettings.getBleOnlyNurses(ctx)) ids.add(nurse.peerId.lowercase())
        for (ip in AppSettings.getNurseIps(ctx)) AppSettings.getNursePeerId(ctx, ip)?.let { ids.add(it.lowercase()) }
        nurseCache = ids
        nurseCachedAt = now
        return ids
    }

    @JvmStatic
    fun getMyPeerIdHex(): String = myPeerId.toHex()

    /** peerId(hex)와 마지막으로 들은 이름(없으면 null). 관리자 화면의 "근처 기기 검색" 목록용. */
    class DiscoveredPeer(@JvmField val peerId: String, @JvmField val name: String?)

    @JvmStatic
    fun getDiscoveredPeers(): List<DiscoveredPeer> {
        val peers = peerManager ?: return emptyList()
        return peers.listOnline().map { DiscoveredPeer(it.peerIdHex, it.name) }
    }
}
