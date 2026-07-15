package io.voyager.samsungbarunhospital.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * IP/TCP로 한 번도 닿아본 적 없어(다른 네트워크) BLE 근처 기기 검색으로 직접 등록한 간호사
 * 태블릿. peerId만으로 식별되며, 호출은 항상 BLE 유니캐스트로만 나간다.
 */
public class BleOnlyNurse {
    public final String peerId; // 16자 hex
    public final String label;  // 등록 시점의 ANNOUNCE 이름(스냅샷) — 관리자 화면 표시용

    public BleOnlyNurse(String peerId, String label) {
        this.peerId = peerId;
        this.label = label;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("peerId", peerId);
        obj.put("label", label != null ? label : "");
        return obj;
    }

    public static BleOnlyNurse fromJson(JSONObject obj) {
        String peerId = obj.optString("peerId", "");
        String label = obj.optString("label", "");
        return new BleOnlyNurse(peerId, label.isEmpty() ? null : label);
    }
}
