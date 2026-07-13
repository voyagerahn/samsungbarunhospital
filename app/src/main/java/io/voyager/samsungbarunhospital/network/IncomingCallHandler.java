package io.voyager.samsungbarunhospital.network;

import io.voyager.samsungbarunhospital.data.CallQueue;
import io.voyager.samsungbarunhospital.model.CallItem;

/**
 * Parses an incoming call-protocol payload ("TabletName|Message|CallId", "CANCEL|id",
 * "CONFIRMED|TabletName|Message") and applies it to {@link CallQueue}. Shared by both
 * AlertReceiverService (TCP) and the BLE mesh receive path so the two transports can't drift
 * apart in how they interpret the same wire format.
 */
public final class IncomingCallHandler {

    public enum Kind { NEW_CALL, CANCEL, CONFIRMED, IGNORED }

    public static final class Result {
        public final Kind kind;
        public final String confirmedTabletName; // only set for Kind.CONFIRMED
        public final String confirmedMessage;    // only set for Kind.CONFIRMED

        private Result(Kind kind, String confirmedTabletName, String confirmedMessage) {
            this.kind = kind;
            this.confirmedTabletName = confirmedTabletName;
            this.confirmedMessage = confirmedMessage;
        }
    }

    private static final Result IGNORED = new Result(Kind.IGNORED, null, null);

    private IncomingCallHandler() {}

    /**
     * @param fromIp     TCP 수신 시 발신 IP, BLE 수신 시 null
     * @param fromPeerId BLE 수신 시 발신자 peerId(16자 hex), TCP 수신 시 null
     */
    public static Result parseAndApply(String payload, String fromIp, String fromPeerId) {
        if (payload == null || payload.isEmpty()) return IGNORED;

        // 환자 호출 취소: "CANCEL|<callId>"
        if (payload.startsWith("CANCEL|")) {
            String[] parts = payload.split("\\|", 2);
            if (parts.length == 2) {
                CallQueue.getInstance().cancelById(parts[1].trim());
            }
            return new Result(Kind.CANCEL, null, null);
        }

        // 간호사 확인 응답: "CONFIRMED|<TabletName>|<Message>" (환자 태블릿이 수신)
        if (payload.startsWith("CONFIRMED|")) {
            String[] parts = payload.split("\\|", 3);
            if (parts.length == 3) {
                return new Result(Kind.CONFIRMED, parts[1].trim(), parts[2].trim());
            }
            return IGNORED;
        }

        // 환자 호출: "TabletName|Message|CallId" 또는 "TabletName|Message" (구버전 호환)
        String tabletName, message, callId;
        String[] parts = payload.split("\\|", 3);
        if (parts.length >= 3) {
            tabletName = parts[0].trim();
            message    = parts[1].trim();
            callId     = parts[2].trim();
        } else if (parts.length == 2) {
            tabletName = parts[0].trim();
            message    = parts[1].trim();
            callId     = null;
        } else {
            tabletName = (fromIp != null ? fromIp : fromPeerId);
            message    = payload;
            callId     = null;
        }

        String id = (callId != null && !callId.isEmpty()) ? callId : java.util.UUID.randomUUID().toString();
        CallItem item = new CallItem(id, tabletName, message, fromIp, fromPeerId);

        boolean added = CallQueue.getInstance().add(item);
        return added ? new Result(Kind.NEW_CALL, null, null) : IGNORED;
    }
}
