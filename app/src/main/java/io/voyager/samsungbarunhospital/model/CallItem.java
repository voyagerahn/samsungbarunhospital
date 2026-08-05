package io.voyager.samsungbarunhospital.model;

import java.util.UUID;

public class CallItem {
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_DONE    = "DONE";

    public final String id;          // 고유 ID — UUID로 중복 방지
    public final String tabletName;
    public final String message;
    public final String senderIp;       // TCP로 수신한 경우 환자 태블릿 IP, BLE 수신 시 null
    public final String senderPeerId;   // BLE로 수신한 경우 발신자 peerId(16자 hex), TCP 수신 시 null
    public final long   timestamp;
    public volatile String status;

    public CallItem(String tabletName, String message, String senderIp) {
        this(UUID.randomUUID().toString(), tabletName, message, senderIp, null);
    }

    /** 환자가 생성한 UUID를 그대로 사용하는 생성자 — 취소 시 1:1 매칭 보장 */
    public CallItem(String id, String tabletName, String message, String senderIp) {
        this(id, tabletName, message, senderIp, null);
    }

    public CallItem(String id, String tabletName, String message, String senderIp, String senderPeerId) {
        this.id          = id;
        this.tabletName  = tabletName;
        this.message     = message;
        this.senderIp    = senderIp;
        this.senderPeerId = senderPeerId;
        this.timestamp   = System.currentTimeMillis();
        this.status      = STATUS_WAITING;
    }
}
