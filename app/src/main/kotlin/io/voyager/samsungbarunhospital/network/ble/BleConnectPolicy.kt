package io.voyager.samsungbarunhospital.network.ble

/**
 * 스캔으로 발견한 기기에 연결할지 정하는 규칙 (순수 함수 — 안드로이드 의존 없음, 단위테스트 대상).
 *
 * 요구사항(사용자 확정): 호출의 최종 도착지는 항상 "설정에서 등록한 간호사 태블릿"이다. 거리가 멀어
 * 블루투스가 약하면 환자 → 환자 → 간호사처럼 다른 태블릿을 거쳐서라도 가야 하고, 사용자는 간호사 태블릿만
 * 등록하면 나머지(누구를 거쳐 갈지)는 앱이 알아서 해야 한다. 간호사 1대에 블루투스 환자 태블릿이 20대 넘게
 * 붙는 규모가 목표인데, 기기 하나가 동시에 맺을 수 있는 연결은 몇 개(현장 태블릿은 4개)뿐이다.
 *
 * 그래서 "보이는 대로 다 연결"하지 않고, **간호사까지 길이 생길 만큼만** 연결한다:
 * - 등록된 간호사 → 자리가 있으면 항상 직접 연결, 없으면 간호사가 아닌 링크를 하나 끊고 연결.
 * - 그 외 태블릿(중계용) → 등록된 간호사까지 길이 아직 없을 때만 연결한다([wantsRelayLink]). 길이 생기면
 *   더 걸지 않고, 남은 자리는 다른 태블릿이 걸어올 수 있게 비워 둔다. 이렇게 하면 간호사 가까이의 태블릿들이
 *   간호사와 직접 이어지고, 먼 태블릿은 그 태블릿들에, 더 먼 태블릿은 다시 그 태블릿들에 붙는 나무 모양이
 *   저절로 만들어진다 — 자리를 다 쓴 태블릿은 광고가 꺼져 보이지 않으므로, 새 태블릿은 자연히 자리가 남은
 *   태블릿에 붙는다.
 * - 길이 없는 동안에도, 이미 그물 안에서 소식이 들리는 태블릿에는 또 걸지 않는다. 그런 연결은 닿는 범위를
 *   넓히지 못하고 자리만 쓴다 — 간호사와 떨어진 태블릿끼리 서로 자리를 다 채워 버리면, 정작 간호사 쪽으로
 *   이어 줄 태블릿이 나타났을 때 연결할 자리가 없다.
 * - 스스로 거는 연결은 한도보다 하나 적게만 쓴다([RESERVED_FOR_INCOMING]). 자리를 끝까지 채우면 다른
 *   태블릿이 이 태블릿을 찾을 수 없게 되기 때문. 단, 길이 없는 태블릿이 처음 보는(닿는 범위를 넓혀 줄)
 *   태블릿을 발견했을 때는 그 자리까지 쓴다 — 길을 찾는 쪽이 먼저다.
 *
 * 간호사를 하나도 등록하지 않은 기기(간호사 태블릿 자신)는 찾아갈 곳이 없으므로 발견되는 대로 연결한다.
 */
object BleConnectPolicy {

    enum class Decision { CONNECT, CONNECT_AFTER_EVICTING, SKIP }

    /** 다른 태블릿이 걸어올 수 있게 비워 두는 자리 수. */
    const val RESERVED_FOR_INCOMING = 1

    /**
     * @param isRegisteredNurse 발견한 기기가 등록된 간호사로 확인됐는지(모르거나 아니면 false)
     * @param linkCount 현재 링크 수(연결 중 포함)
     * @param maxLinks 링크 상한
     * @param unlinkedNurseCount 아직 링크가 없는 등록된 간호사 수(지금 발견한 기기는 제외)
     * @param hasEvictable 끊어도 되는(등록된 간호사가 아닌) 링크가 있는지
     * @param wantsRelayLink 간호사가 아닌 태블릿과의 연결이 지금 더 필요한지([wantsRelayLink])
     * @param expandsReach 간호사까지 길이 없는 기기가, 아직 그물 안에서 소식이 들리지 않는 기기를 발견했는지
     */
    fun decide(
        isRegisteredNurse: Boolean,
        linkCount: Int,
        maxLinks: Int,
        unlinkedNurseCount: Int,
        hasEvictable: Boolean,
        wantsRelayLink: Boolean = true,
        expandsReach: Boolean = false
    ): Decision {
        if (linkCount >= maxLinks) {
            return if (isRegisteredNurse && hasEvictable) Decision.CONNECT_AFTER_EVICTING else Decision.SKIP
        }
        if (isRegisteredNurse) return Decision.CONNECT
        if (!wantsRelayLink) return Decision.SKIP
        if (linkCount == 0) return Decision.CONNECT // 연결이 하나도 없으면 남길 자리를 따질 때가 아니다
        // 닿는 범위를 넓혀 줄 기기에는 남겨둔 자리까지 쓴다(간호사가 나중에 보이면 다른 링크를 끊고 연결하므로
        // 간호사 몫을 따로 남길 필요는 없다).
        val reserved = if (expandsReach) 0 else maxOf(unlinkedNurseCount, RESERVED_FOR_INCOMING)
        return if (linkCount < maxLinks - reserved) Decision.CONNECT else Decision.SKIP
    }

    /**
     * 간호사가 아닌 태블릿(중계용)과의 연결이 지금 더 필요한지.
     *
     * @param hasRegisteredNurse 이 기기에 등록된 간호사가 있는지(없으면 간호사 태블릿 자신)
     * @param writableLinks 지금 주고받을 수 있는 링크 수
     * @param pendingAttempts 진행 중인 연결 시도 수
     * @param allNursesHavePath 등록된 간호사 모두에게 (직접이든 거쳐서든) 길이 확인된 상태인지
     * @param candidateInMesh 발견한 기기가 이미 다른 태블릿을 거쳐 소식이 들리는 기기인지
     * @param msSinceLinkGained 마지막으로 링크가 맺어진 뒤 지난 시간
     */
    fun wantsRelayLink(
        hasRegisteredNurse: Boolean,
        writableLinks: Int,
        pendingAttempts: Int,
        allNursesHavePath: Boolean,
        candidateInMesh: Boolean,
        msSinceLinkGained: Long,
        settleMs: Long = BleMeshConstants.LINK_SETTLE_MS
    ): Boolean {
        if (!hasRegisteredNurse) return true
        if (allNursesHavePath) return false
        // 한 번에 하나씩: 방금 건 연결이 간호사까지 이어지는 길일 수 있다. 맺어진 직후 상대가 자기가 아는
        // 기기들의 소식을 넘겨주므로(BleMeshManager.sendKnownAnnouncesTo) 그걸 들을 때까지 잠깐 기다린다.
        if (pendingAttempts > 0) return false
        if (writableLinks == 0) return true
        if (candidateInMesh) return false // 이미 닿는 기기 — 연결해도 닿는 범위가 넓어지지 않는다
        return msSinceLinkGained >= settleMs
    }
}
