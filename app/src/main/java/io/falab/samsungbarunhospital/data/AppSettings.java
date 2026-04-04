package io.falab.samsungbarunhospital.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppSettings {

    private static final String PREFS = "app_settings";
    private static final String KEY_NURSE_IP = "nurse_ip";
    private static final String KEY_TABLET_NAME = "tablet_name";
    private static final String KEY_MESSAGES = "call_messages";
    private static final String KEY_PASSWORD = "admin_password";
    private static final String DEFAULT_PASSWORD = "0819";

    private static final List<String> DEFAULT_MESSAGES = Arrays.asList(
            "너무 아파요",
            "주사약이 다 들어갔어요",
            "잠이 안와요",
            "화장실 도움이 필요해요",
            "일반 호출"
    );

    public static String getPassword(Context ctx) {
        return prefs(ctx).getString(KEY_PASSWORD, DEFAULT_PASSWORD);
    }

    public static String getNurseIp(Context ctx) {
        return prefs(ctx).getString(KEY_NURSE_IP, "192.168.1.100");
    }

    public static String getTabletName(Context ctx) {
        return prefs(ctx).getString(KEY_TABLET_NAME, "환자 태블릿");
    }

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

    public static void save(Context ctx, String nurseIp, String tabletName, List<String> messages, String password) {
        try {
            JSONArray arr = new JSONArray();
            for (String m : messages) arr.put(m);
            prefs(ctx).edit()
                    .putString(KEY_NURSE_IP, nurseIp)
                    .putString(KEY_TABLET_NAME, tabletName)
                    .putString(KEY_MESSAGES, arr.toString())
                    .putString(KEY_PASSWORD, password.isEmpty() ? DEFAULT_PASSWORD : password)
                    .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
