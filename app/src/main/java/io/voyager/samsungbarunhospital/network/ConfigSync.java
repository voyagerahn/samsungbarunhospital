package io.voyager.samsungbarunhospital.network;

import android.content.Context;

import io.voyager.samsungbarunhospital.data.AppSettings;
import io.voyager.samsungbarunhospital.model.ButtonConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "서버 없이 서버처럼" — 간호사 태블릿의 호출 메시지 목록과 런처 버튼 설정을 환자 태블릿이
 * 직접 요청해서 읽어올 수 있게 하는 TCP 프로토콜 확장. 기존 CALL/CANCEL/CONFIRMED와 같은
 * 포트(9999)를 재사용하되, "ACK" 대신 실제 설정 데이터를 응답으로 돌려준다는 점만 다르다.
 *
 * 동기화 대상: 호출 메시지 목록, 버튼의 종류(앱/웹)와 대상(패키지명/URL), 기본 제공 아이콘명.
 * 동기화 제외: 태블릿 이름·관리자 비밀번호(기기별 고유 값), 관리자가 직접 업로드한 버튼 이미지
 * (`ButtonConfig.imagePath`는 로컬 파일 경로라 다른 기기로 전송해도 의미가 없음 — 받는 쪽은
 * 항상 drawableRes 또는 기본 아이콘으로 대체됨).
 */
public final class ConfigSync {

    public static final String REQUEST = "CONFIG_REQUEST";
    private static final String RESPONSE_PREFIX = "CONFIG|";

    /** payload 형식 버전. 필드가 바뀌면 올려서, 이 앱이 이해 못하는 미래 버전을 구분한다. */
    private static final int CURRENT_VERSION = 1;

    private ConfigSync() {}

    /** 이 기기(간호사 태블릿)가 CONFIG_REQUEST를 받았을 때 돌려줄 응답 페이로드. */
    public static String buildResponsePayload(Context ctx) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("version", CURRENT_VERSION);

            JSONArray messages = new JSONArray();
            for (String m : AppSettings.getCallMessages(ctx)) messages.put(m);
            obj.put("messages", messages);

            JSONArray buttons = new JSONArray();
            for (ButtonConfig c : AppSettings.getButtonConfigs(ctx)) {
                JSONObject b = new JSONObject();
                b.put("drawableRes", c.drawableRes != null ? c.drawableRes : "");
                b.put("type", c.type != null ? c.type : ButtonConfig.TYPE_WEB);
                b.put("target", c.target != null ? c.target : "");
                buttons.put(b);
            }
            obj.put("buttons", buttons);

            return RESPONSE_PREFIX + obj;
        } catch (Exception e) {
            return RESPONSE_PREFIX + "{}";
        }
    }

    public static final class RemoteConfig {
        public final List<String> messages;
        public final List<ButtonConfig> buttons;

        RemoteConfig(List<String> messages, List<ButtonConfig> buttons) {
            this.messages = messages;
            this.buttons = buttons;
        }
    }

    /**
     * @return null이면 CONFIG 응답이 아니거나, 파싱 실패했거나, 이 앱이 이해할 수 없는(더 높은)
     * 버전. version 필드가 없는 경우는 과거 형식으로 간주하고 최대한 읽어본다.
     */
    public static RemoteConfig parseResponsePayload(String payload) {
        if (payload == null || !payload.startsWith(RESPONSE_PREFIX)) return null;
        try {
            JSONObject obj = new JSONObject(payload.substring(RESPONSE_PREFIX.length()));

            int version = obj.optInt("version", 0);
            if (version > CURRENT_VERSION) return null; // 미래 버전, 이 앱은 이해 못함

            List<String> messages = new ArrayList<>();
            JSONArray msgArr = obj.optJSONArray("messages");
            if (msgArr != null) {
                for (int i = 0; i < msgArr.length(); i++) {
                    String m = msgArr.optString(i, "").trim();
                    if (!m.isEmpty()) messages.add(m);
                }
            }

            List<ButtonConfig> buttons = new ArrayList<>();
            JSONArray btnArr = obj.optJSONArray("buttons");
            if (btnArr != null) {
                for (int i = 0; i < btnArr.length(); i++) {
                    JSONObject b = btnArr.optJSONObject(i);
                    if (b == null) continue;
                    ButtonConfig c = new ButtonConfig();
                    String res = b.optString("drawableRes", "");
                    c.drawableRes = res.isEmpty() ? null : res;
                    c.type = b.optString("type", ButtonConfig.TYPE_WEB);
                    c.target = b.optString("target", "");
                    c.imagePath = null; // 항상 제외 — 클래스 주석 참고
                    buttons.add(c);
                }
            }

            return new RemoteConfig(messages, buttons);
        } catch (Exception e) {
            return null;
        }
    }
}
