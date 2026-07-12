package io.falab.samsungbarunhospital.data;

import android.content.Context;
import android.content.SharedPreferences;

import io.falab.samsungbarunhospital.model.ButtonConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppSettings {

    private static final String PREFS               = "app_settings";
    private static final String KEY_TABLET_NAME     = "tablet_name";
    private static final String KEY_MESSAGES        = "call_messages";
    private static final String KEY_PASSWORD        = "admin_password";
    private static final String KEY_BUTTON_CONFIGS  = "button_configs";
    private static final String KEY_NURSE_IPS        = "nurse_ips";
    private static final String KEY_BLE_PEER_ID      = "ble_peer_id";
    private static final String KEY_ALARM_VOLUME     = "alarm_volume";
    private static final String KEY_FULLSCREEN_PRIMARY = "fullscreen_primary";

    // 간호사 화면 글씨 크기 (sp)
    private static final String KEY_LEFT_ROOM_SIZE  = "left_room_size";
    private static final String KEY_LEFT_MSG_SIZE   = "left_msg_size";
    private static final String KEY_LEFT_TIME_SIZE  = "left_time_size";
    private static final String KEY_LIST_ROOM_SIZE  = "list_room_size";
    private static final String KEY_LIST_MSG_SIZE   = "list_msg_size";
    private static final String KEY_LIST_TIME_SIZE  = "list_time_size";

    private static final String DEFAULT_PASSWORD  = "0819";
    private static final int    DEFAULT_ALARM_VOL = 80; // 기본 80%

    public static final int MAX_BUTTONS = 6;

    private static final List<String> DEFAULT_MESSAGES = Arrays.asList(
            "너무 아파요",
            "주사약이 다 들어갔어요",
            "잠이 안와요",
            "화장실 도움이 필요해요",
            "일반 호출"
    );

    // ── 알람 음량 (0~100%) ──────────────────────────────────────────────────
    public static int getAlarmVolume(Context ctx) {
        return prefs(ctx).getInt(KEY_ALARM_VOLUME, DEFAULT_ALARM_VOL);
    }

    public static void saveAlarmVolume(Context ctx, int percent) {
        prefs(ctx).edit().putInt(KEY_ALARM_VOLUME, Math.max(0, Math.min(100, percent))).apply();
    }

    // ── 최우선 호출 패널 전체화면 모드 (좌측 칸을 화면 전체로 표시) ───────────
    public static boolean isFullScreenPrimary(Context ctx) {
        return prefs(ctx).getBoolean(KEY_FULLSCREEN_PRIMARY, false);
    }

    public static void setFullScreenPrimary(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_FULLSCREEN_PRIMARY, enabled).apply();
    }

    // ── 간호사 화면 글씨 크기 (sp) ──────────────────────────────────────────
    // 범위: UI가 깨지지 않는 최소~최대 sp 값
    public static final int LEFT_ROOM_MIN = 40,  LEFT_ROOM_MAX = 120, LEFT_ROOM_DEF = 90;
    public static final int LEFT_MSG_MIN  = 20,  LEFT_MSG_MAX  = 80,  LEFT_MSG_DEF  = 50;
    public static final int LEFT_TIME_MIN = 12,  LEFT_TIME_MAX = 44,  LEFT_TIME_DEF = 28;
    public static final int LIST_ROOM_MIN = 14,  LIST_ROOM_MAX = 44,  LIST_ROOM_DEF = 26;
    public static final int LIST_MSG_MIN  = 14,  LIST_MSG_MAX  = 44,  LIST_MSG_DEF  = 26;
    public static final int LIST_TIME_MIN = 10,  LIST_TIME_MAX = 26,  LIST_TIME_DEF = 16;

    public static int getLeftRoomSize(Context ctx)  { return clamp(prefs(ctx).getInt(KEY_LEFT_ROOM_SIZE, LEFT_ROOM_DEF), LEFT_ROOM_MIN, LEFT_ROOM_MAX); }
    public static int getLeftMsgSize(Context ctx)   { return clamp(prefs(ctx).getInt(KEY_LEFT_MSG_SIZE,  LEFT_MSG_DEF),  LEFT_MSG_MIN,  LEFT_MSG_MAX);  }
    public static int getLeftTimeSize(Context ctx)  { return clamp(prefs(ctx).getInt(KEY_LEFT_TIME_SIZE, LEFT_TIME_DEF), LEFT_TIME_MIN, LEFT_TIME_MAX); }
    public static int getListRoomSize(Context ctx)  { return clamp(prefs(ctx).getInt(KEY_LIST_ROOM_SIZE, LIST_ROOM_DEF), LIST_ROOM_MIN, LIST_ROOM_MAX); }
    public static int getListMsgSize(Context ctx)   { return clamp(prefs(ctx).getInt(KEY_LIST_MSG_SIZE,  LIST_MSG_DEF),  LIST_MSG_MIN,  LIST_MSG_MAX);  }
    public static int getListTimeSize(Context ctx)  { return clamp(prefs(ctx).getInt(KEY_LIST_TIME_SIZE, LIST_TIME_DEF), LIST_TIME_MIN, LIST_TIME_MAX); }

    public static void saveFontSizes(Context ctx,
            int leftRoom, int leftMsg, int leftTime,
            int listRoom, int listMsg, int listTime) {
        prefs(ctx).edit()
                .putInt(KEY_LEFT_ROOM_SIZE, clamp(leftRoom, LEFT_ROOM_MIN, LEFT_ROOM_MAX))
                .putInt(KEY_LEFT_MSG_SIZE,  clamp(leftMsg,  LEFT_MSG_MIN,  LEFT_MSG_MAX))
                .putInt(KEY_LEFT_TIME_SIZE, clamp(leftTime, LEFT_TIME_MIN, LEFT_TIME_MAX))
                .putInt(KEY_LIST_ROOM_SIZE, clamp(listRoom, LIST_ROOM_MIN, LIST_ROOM_MAX))
                .putInt(KEY_LIST_MSG_SIZE,  clamp(listMsg,  LIST_MSG_MIN,  LIST_MSG_MAX))
                .putInt(KEY_LIST_TIME_SIZE, clamp(listTime, LIST_TIME_MIN, LIST_TIME_MAX))
                .apply();
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    // ── 비밀번호 ─────────────────────────────────────────────────────────────
    public static String getPassword(Context ctx) {
        return prefs(ctx).getString(KEY_PASSWORD, DEFAULT_PASSWORD);
    }

    // ── 태블릿 이름 ──────────────────────────────────────────────────────────
    public static String getTabletName(Context ctx) {
        return prefs(ctx).getString(KEY_TABLET_NAME, "환자 태블릿");
    }

    // ── BLE mesh peerId (기기별 영구 8바이트 랜덤 식별자, 16자 hex) ────────────
    public static synchronized String getOrCreateBlePeerId(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        String existing = prefs.getString(KEY_BLE_PEER_ID, null);
        if (existing != null && existing.length() == 16) {
            return existing;
        }
        byte[] id = new byte[8];
        new java.security.SecureRandom().nextBytes(id);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : id) sb.append(String.format("%02x", b));
        String hex = sb.toString();
        prefs.edit().putString(KEY_BLE_PEER_ID, hex).apply();
        return hex;
    }

    // ── 간호사 IP 목록 ───────────────────────────────────────────────────────
    public static List<String> getNurseIps(Context ctx) {
        String json = prefs(ctx).getString(KEY_NURSE_IPS, null);
        if (json == null) return new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String ip = arr.getString(i).trim();
                if (!ip.isEmpty()) list.add(ip);
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void saveNurseIps(Context ctx, List<String> ips) {
        try {
            JSONArray arr = new JSONArray();
            for (String ip : ips) arr.put(ip);
            prefs(ctx).edit().putString(KEY_NURSE_IPS, arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── 호출 메시지 ──────────────────────────────────────────────────────────
    public static List<String> getCallMessages(Context ctx) {
        String json = prefs(ctx).getString(KEY_MESSAGES, null);
        if (json == null) return new ArrayList<>(DEFAULT_MESSAGES);
        try {
            JSONArray arr = new JSONArray(json);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String msg = arr.getString(i).trim();
                if (!msg.isEmpty()) list.add(msg);
            }
            return list.isEmpty() ? new ArrayList<>(DEFAULT_MESSAGES) : list;
        } catch (Exception e) {
            return new ArrayList<>(DEFAULT_MESSAGES);
        }
    }

    // ── 앱 버튼 설정 ─────────────────────────────────────────────────────────
    public static List<ButtonConfig> getButtonConfigs(Context ctx) {
        String json = prefs(ctx).getString(KEY_BUTTON_CONFIGS, null);
        if (json == null) return getDefaultButtonConfigs();
        try {
            JSONArray arr = new JSONArray(json);
            List<ButtonConfig> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(ButtonConfig.fromJson(arr.getJSONObject(i)));
            }
            return list.isEmpty() ? getDefaultButtonConfigs() : list;
        } catch (Exception e) {
            return getDefaultButtonConfigs();
        }
    }

    public static void saveButtonConfigs(Context ctx, List<ButtonConfig> configs) {
        try {
            JSONArray arr = new JSONArray();
            for (ButtonConfig c : configs) arr.put(c.toJson());
            prefs(ctx).edit().putString(KEY_BUTTON_CONFIGS, arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<ButtonConfig> getDefaultButtonConfigs() {
        // Android 9 (API 28) 이상: Wavve 앱 실행 / 미만: Wavve 웹 연결
        boolean wavveApp = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P;
        String  wavveType   = wavveApp ? ButtonConfig.TYPE_APP : ButtonConfig.TYPE_WEB;
        String  wavveTarget = wavveApp ? "com.movienet.touch.wavveon" : "https://www.wavve.com";

        List<ButtonConfig> list = new ArrayList<>();
        list.add(new ButtonConfig(null, "youtube",            ButtonConfig.TYPE_APP, "com.google.android.youtube"));
        list.add(new ButtonConfig(null, "wavve_tv",           wavveType,             wavveTarget));
        list.add(new ButtonConfig(null, "home",               ButtonConfig.TYPE_WEB, "http://samsungbarunhospital.com?device=pc"));
        list.add(new ButtonConfig(null, "www",                ButtonConfig.TYPE_WEB, "https://www.naver.com"));
        list.add(new ButtonConfig(null, "blog",               ButtonConfig.TYPE_WEB, "https://blog.naver.com/samsungbarunhosp"));
        list.add(new ButtonConfig(null, "medical_cerificate", ButtonConfig.TYPE_WEB, "https://docs.google.com/forms/d/e/1FAIpQLSe-_scHDukGeaFu0hdPvTikb_oOKXrSIrVmZklvtg7IoWZFtA/viewform?pli=1"));
        return list;
    }

    // ── 기본 설정 저장 (태블릿 이름·메시지·비밀번호) ─────────────────────────
    public static void save(Context ctx, String tabletName, List<String> messages, String password) {
        try {
            JSONArray arr = new JSONArray();
            for (String m : messages) arr.put(m);
            prefs(ctx).edit()
                    .putString(KEY_TABLET_NAME, tabletName)
                    .putString(KEY_MESSAGES,    arr.toString())
                    .putString(KEY_PASSWORD,    password.isEmpty() ? DEFAULT_PASSWORD : password)
                    .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
