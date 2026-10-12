package io.voyager.samsungbarunhospital.network.ble

/**
 * 스캔으로 발견한 기기에 연결할지 정하는 규칙 (순수 함수 — 안드로이드 의존 없음, 단위테스트 대상).
 *
 * 요구사항(사용자 확정): 호출의 최종 도착지는 항상 "설정에서 등록한 간호사 태블릿"이다. 거리가 멀어
 * 블루투스가 약하면 환자 → 환자 → 간호사처럼 다른 태블릿을 거쳐서라도 가야 하므로 환자 태블릿끼리의
 * 링크(중계용)는 유지한다. 다만 연결 자리는 기기당 [BleMeshConstants.MAX_CONNECTIONS]개뿐이라, 예전처럼
 * 먼저 발견된 순서대로 채우면 주변 환자 태블릿이 자리를 다 차지해 정작 간호사와는 직접 연결하지 못할 수
 * 있었다. 그래서:
 *
 * - 등록된 간호사 → 자리가 있으면 항상 연결, 없으면 간호사가 아닌 링크를 하나 끊고 연결.
 * - 그 외 기기(중계용, 정체를 모르는 기기 포함) → 아직 연결 못 한 간호사 수만큼 자리를 남겨두고 연결.
 *
 * 간호사를 하나도 등록하지 않은 기기(간호사 태블릿 자신)는 남겨둘 자리가 0이므로 예전처럼 자리가 찰
 * 때까지 발견되는 대로 연결한다.
 */
object BleConnectPolicy {

    enum class Decision { CONNECT, CONNECT_AFTER_EVICTING, SKIP }

    /**
     * @param isRegisteredNurse 발견한 기기가 등록된 간호사로 확인됐는지(모르거나 아니면 false)
     * @param linkCount 현재 링크 수(연결 중 포함)
     * @param maxLinks 링크 상한
     * @param unlinkedNurseCount 아직 링크가 없는 등록된 간호사 수(지금 발견한 기기는 제외)
     * @param hasEvictable 끊어도 되는(등록된 간호사가 아닌) 링크가 있는지
     */
    fun decide(
        isRegisteredNurse: Boolean,
        linkCount: Int,
        maxLinks: Int,
        unlinkedNurseCount: Int,
        hasEvictable: Boolean
    ): Decision {
        if (linkCount >= maxLinks) {
            return if (isRegisteredNurse && hasEvictable) Decision.CONNECT_AFTER_EVICTING else Decision.SKIP
        }
        if (isRegisteredNurse) return Decision.CONNECT
        val roomForOthers = maxLinks - unlinkedNurseCount.coerceAtLeast(0)
        return if (linkCount < roomForOthers) Decision.CONNECT else Decision.SKIP
    }
}
