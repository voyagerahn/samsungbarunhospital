package io.falab.samsungbarunhospital.model;

public class CallItem {
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_DONE    = "DONE";

    public final String id;          // 중복 방지용 고유 ID
    public final String tabletName;
    public final String message;
    public final long   timestamp;
    public volatile String status;

    public CallItem(String tabletName, String message) {
        this.tabletName  = tabletName;
        this.message     = message;
        this.timestamp   = System.currentTimeMillis();
        this.id          = tabletName + "_" + this.timestamp;
        this.status      = STATUS_WAITING;
    }
}
