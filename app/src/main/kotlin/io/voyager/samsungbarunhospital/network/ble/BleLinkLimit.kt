package io.voyager.samsungbarunhospital.network.ble

/**
 * 이 기기가 동시에 유지할 블루투스 링크 수의 한도를 정한다 (순수 로직 — 단위테스트 대상).
 *
 * 실제 한도는 기기의 블루투스 칩이 정하는데, 안드로이드에는 그 값을 미리 읽는 방법이 없다. 그래서 고정값으로
 * 가정하지 않고 **칩이 알려준 값을 배운다**:
 * - 칩이 가득 찼다는 신호를 주면 그 순간의 링크 수가 이 기기의 한도다.
 * - 배운 한도는 블루투스가 다시 시작될 때까지 기억한다(BleConnectionManager). 링크가 하나 줄었다고 잊으면,
 *   자리가 날 때마다 한도까지 다시 채우려다 거부당하는 일을 되풀이하고, 꽉 찬 기기는 다른 태블릿이 찾을 수
 *   없으므로(광고 불가) 새 태블릿이 붙을 곳이 없어진다 — 태블릿이 20대 넘는 규모에서 문제가 된다.
 * - 한도라고 본 수만큼 연결된 상태에서도 광고가 켜지면 그 판단이 틀렸던 것이므로 잊는다.
 * - 신호가 없는 동안은 앱 상한([BleMeshConstants.MAX_CONNECTIONS])까지 시도한다.
 *
 * 예전에는 8로 고정 가정했는데, 현장 태블릿이 4대에서 가득 찬 뒤에도 앱은 계속 더 연결하려 했다.
 */
object BleLinkLimit {

    /** @param saturatedAt 칩이 알려준 한도(-1 = 아직 모름) */
    fun effective(saturatedAt: Int, appMax: Int = BleMeshConstants.MAX_CONNECTIONS): Int =
        if (saturatedAt > 0) saturatedAt.coerceAtMost(appMax) else appMax

    /**
     * 칩의 거부 신호를 "한도가 찼다"로 볼지.
     *
     * @param linkCount 신호를 받은 순간의 링크 수
     * @param explicitLimit 신호가 "연결 한도 초과"(HCI 0x09)처럼 원인이 분명한 것인지. 광고 거부(0x0C)는
     *   한도가 찼을 때 주로 나지만 다른 이유(연결 시도 중 등)로도 날 수 있어서, 링크가 적을 때의 거부는
     *   한도로 보지 않는다 — 1대 연결 상태의 일시적 거부를 한도로 보면 그 기기는 1대만 받게 된다.
     */
    fun isLimitSignal(
        linkCount: Int,
        explicitLimit: Boolean,
        appMax: Int = BleMeshConstants.MAX_CONNECTIONS
    ): Boolean = linkCount >= (if (explicitLimit) 2 else 3) && linkCount < appMax
}
