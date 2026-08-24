package com.drivehub.mgha.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import com.drivehub.mgha.net.WifiHelper;

import org.json.JSONObject;

public final class HaSettings {
    private static final String PREF = "mg_ha";

    public static final String KEY_URL = "ha_url";
    public static final String KEY_TOKEN = "ha_token";
    public static final String KEY_PREFIX = "entity_prefix";
    public static final String KEY_INTERVAL = "interval_sec";
    public static final String KEY_WIFI_ONLY = "wifi_only";
    public static final String KEY_INSECURE = "allow_insecure_ssl";
    public static final String KEY_AUTOSTART = "autostart";
    public static final String KEY_DEMO = "demo_mode";

    private HaSettings() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String url(Context ctx) {
        return prefs(ctx).getString(KEY_URL, "").trim();
    }

    public static String token(Context ctx) {
        return prefs(ctx).getString(KEY_TOKEN, "").trim();
    }

    public static String prefix(Context ctx) {
        String p = prefs(ctx).getString(KEY_PREFIX, "mg4");
        if (p == null || p.trim().isEmpty()) return "mg4";
        return p.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    public static int intervalSec(Context ctx) {
        int v = prefs(ctx).getInt(KEY_INTERVAL, 30);
        if (v < 5) return 5;
        if (v > 300) return 300;
        return v;
    }

    public static boolean wifiOnly(Context ctx) {
        // Sim / telefonda WiFi şartı yok; kayıtlı true olsa bile yok sayılır (canSend).
        if (!prefs(ctx).contains(KEY_WIFI_ONLY)) {
            return !WifiHelper.isSim();
        }
        return prefs(ctx).getBoolean(KEY_WIFI_ONLY, !WifiHelper.isSim());
    }

    public static boolean allowInsecureSsl(Context ctx) {
        return prefs(ctx).getBoolean(KEY_INSECURE, false);
    }

    public static boolean autoStart(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTOSTART, true);
    }

    public static boolean demoMode(Context ctx) {
        return prefs(ctx).getBoolean(KEY_DEMO, false);
    }

    public static void setDemoMode(Context ctx, boolean demo) {
        prefs(ctx).edit().putBoolean(KEY_DEMO, demo).apply();
    }

    public static boolean isConfigured(Context ctx) {
        return !url(ctx).isEmpty() && !token(ctx).isEmpty();
    }

    public static void save(Context ctx, String url, String token, String prefix,
                            int intervalSec, boolean wifiOnly, boolean insecure, boolean autoStart) {
        prefs(ctx).edit()
                .putString(KEY_URL, url == null ? "" : url.trim())
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_PREFIX, prefix)
                .putInt(KEY_INTERVAL, intervalSec)
                .putBoolean(KEY_WIFI_ONLY, wifiOnly)
                .putBoolean(KEY_INSECURE, insecure)
                .putBoolean(KEY_AUTOSTART, autoStart)
                .apply();
    }

    public static JSONObject toJson(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("url", url(ctx));
            o.put("token", token(ctx));
            o.put("prefix", prefix(ctx));
            o.put("interval", intervalSec(ctx));
            o.put("wifiOnly", wifiOnly(ctx));
            o.put("insecure", allowInsecureSsl(ctx));
            o.put("autoStart", autoStart(ctx));
        } catch (Exception ignored) {}
        return o;
    }

    public static boolean applyJson(Context ctx, JSONObject o) {
        if (o == null) return false;
        String u = o.optString("url", "").trim();
        String t = o.optString("token", "").trim();
        if (u.isEmpty() || t.isEmpty()) return false;
        save(ctx, u, t, o.optString("prefix", "mg4"),
                o.optInt("interval", 30),
                o.optBoolean("wifiOnly", !WifiHelper.isSim()),
                o.optBoolean("insecure", false),
                o.optBoolean("autoStart", true));
        return true;
    }

    public static JSONObject parseConfig(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(s);
            if (o.optString("url").trim().isEmpty()) return null;
            if (o.optString("token").trim().isEmpty()) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }
}
