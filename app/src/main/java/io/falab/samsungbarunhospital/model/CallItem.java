package io.falab.samsungbarunhospital.model;

import java.util.UUID;

public class CallItem {
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_DONE    = "DONE";

    public final String id;          // 고유 ID — UUID로 중복 방지
    public final String tabletName;
    public final String message;
    public final String senderIp;   // 환자 태블릿 IP (확인 응답 전송용)
    public final long   timestamp;
    public volatile String status;

    public CallItem(String tabletName, String message, String senderIp) {
        this(UUID.randomUUID().toString(), tabletName, message, senderIp);
    }

    /** 환자가 생성한 UUID를 그대로 사용하는 생성자 — 취소 시 1:1 매칭 보장 */
    public CallItem(String id, String tabletName, String message, String senderIp) {
        this.id          = id;
        this.tabletName  = tabletName;
        this.message     = message;
        this.senderIp    = senderIp;
        this.timestamp   = System.currentTimeMillis();
        this.status      = STATUS_WAITING;
    }
}
