package io.falab.samsungbarunhospital.model;

public class CallItem {
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_DONE    = "DONE";

    public final String id;          // 고유 ID
    public final String tabletName;
    public final String message;
    public final String senderIp;   // 환자 태블릿 IP (확인 응답 전송용)
    public final long   timestamp;
    public volatile String status;

    public CallItem(String tabletName, String message, String senderIp) {
        this.tabletName  = tabletName;
        this.message     = message;
        this.senderIp    = senderIp;
        this.timestamp   = System.currentTimeMillis();
        this.id          = tabletName + "_" + this.timestamp;
        this.status      = STATUS_WAITING;
    }
}
