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

    private static final String DEFAULT_PASSWORD = "0819";

    public static final int MAX_BUTTONS = 6;

    private static final List<String> DEFAULT_MESSAGES = Arrays.asList(
            "너무 아파요",
            "주사약이 다 들어갔어요",
            "잠이 안와요",
            "화장실 도움이 필요해요",
            "일반 호출"
    );

    // ── 비밀번호 ─────────────────────────────────────────────────────────────
    public static String getPassword(Context ctx) {
        return prefs(ctx).getString(KEY_PASSWORD, DEFAULT_PASSWORD);
    }

    // ── 태블릿 이름 ──────────────────────────────────────────────────────────
    public static String getTabletName(Context ctx) {
        return prefs(ctx).getString(KEY_TABLET_NAME, "환자 태블릿");
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
