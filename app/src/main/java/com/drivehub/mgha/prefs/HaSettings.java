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
    public static final String KEY_INTERVAL_MIN = "interval_min";
    public static final String KEY_WIFI_ONLY = "wifi_only";
    public static final String KEY_INSECURE = "allow_insecure_ssl";
    public static final String KEY_AUTOSTART = "autostart";
    public static final String KEY_WIFI_ON_BOOT = "wifi_on_boot";
    public static final String KEY_VERBOSE_LOG = "verbose_log";
    public static final String KEY_DEMO = "demo_mode";

    private static volatile boolean sVerboseLog;

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
        if (p.trim().isEmpty()) return "mg4";
        return p.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    /** Gönderim aralığı (dakika). En az 1; üst sınır yok. */
    public static int intervalMin(Context ctx) {
        return Math.max(1, prefs(ctx).getInt(KEY_INTERVAL_MIN, 1));
    }

    public static long intervalMs(Context ctx) {
        return intervalMin(ctx) * 60_000L;
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

    /** Boot / servis başlangıcında WiFi radyosunu aç. Varsayılan: araçta açık. */
    public static boolean wifiOnBoot(Context ctx) {
        return prefs(ctx).getBoolean(KEY_WIFI_ON_BOOT, !WifiHelper.isSim());
    }

    /** Ayrıntılı logcat (tick/ağ/HA). Varsayılan kapalı. */
    public static boolean verboseLog(Context ctx) {
        return prefs(ctx).getBoolean(KEY_VERBOSE_LOG, false);
    }

    public static boolean verboseLogCached() {
        return sVerboseLog;
    }

    public static void refreshVerboseCache(Context ctx) {
        sVerboseLog = verboseLog(ctx);
        com.drivehub.mgha.util.MghaLog.refresh(ctx);
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
                            int intervalMin, boolean wifiOnly, boolean insecure,
                            boolean autoStart, boolean wifiOnBoot, boolean verboseLog) {
        prefs(ctx).edit()
                .putString(KEY_URL, url == null ? "" : url.trim())
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_PREFIX, prefix)
                .putInt(KEY_INTERVAL_MIN, Math.max(1, intervalMin))
                .putBoolean(KEY_WIFI_ONLY, wifiOnly)
                .putBoolean(KEY_INSECURE, insecure)
                .putBoolean(KEY_AUTOSTART, autoStart)
                .putBoolean(KEY_WIFI_ON_BOOT, wifiOnBoot)
                .putBoolean(KEY_VERBOSE_LOG, verboseLog)
                .apply();
        refreshVerboseCache(ctx);
    }

    public static boolean applyJson(Context ctx, JSONObject o) {
        if (o == null) return false;
        String u = o.optString("url", "").trim();
        String t = o.optString("token", "").trim();
        if (u.isEmpty() || t.isEmpty()) return false;
        save(ctx, u, t, o.optString("prefix", "mg4"),
                o.optInt("interval", 1),
                o.optBoolean("wifiOnly", !WifiHelper.isSim()),
                o.optBoolean("insecure", false),
                o.optBoolean("autoStart", true),
                o.optBoolean("wifiOnBoot", !WifiHelper.isSim()),
                o.optBoolean("verboseLog", false));
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
