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
    /** @deprecated eski tek aralık; {@link #KEY_INTERVAL_NORMAL_MIN} */
    public static final String KEY_INTERVAL_MIN = "interval_min";
    public static final String KEY_INTERVAL_NORMAL_MIN = "interval_normal_min";
    public static final String KEY_INTERVAL_CHARGING_SEC = "interval_charging_sec";
    /** @deprecated dakika cinsinden eski şarj aralığı */
    private static final String KEY_INTERVAL_CHARGING_MIN_LEGACY = "interval_charging_min";
    public static final String KEY_WIFI_ONLY = "wifi_only";
    public static final String KEY_INSECURE = "allow_insecure_ssl";
    public static final String KEY_AUTOSTART = "autostart";
    public static final String KEY_WIFI_ON_BOOT = "wifi_on_boot";
    public static final String KEY_VERBOSE_LOG = "verbose_log";
    public static final String KEY_DEMO = "demo_mode";
    public static final String KEY_POLL_ENABLED = "poll_enabled";
    public static final String KEY_POLL_INTERVAL_SEC = "poll_interval_sec";
    public static final String KEY_VEHICLE_LAST_RUN_MS = "vehicle_last_run_ms";
    public static final String KEY_REFRESH_HANDLED_MS = "refresh_handled_ms";

    /** HA komut poll aralığı alt sınırı (sn). */
    public static final int MIN_POLL_INTERVAL_SEC = 1;
    public static final int MIN_PUSH_INTERVAL_MIN = 1;
    public static final int MIN_PUSH_INTERVAL_CHARGING_SEC = 1;
    public static final int DEFAULT_INTERVAL_NORMAL_MIN = 10;
    public static final int DEFAULT_INTERVAL_CHARGING_SEC = 60;

    public enum PushMode {
        NORMAL,
        CHARGING
    }

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

    /** Normal (park/idle) push aralığı (dakika). */
    public static int intervalNormalMin(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.contains(KEY_INTERVAL_NORMAL_MIN)) {
            return Math.max(MIN_PUSH_INTERVAL_MIN, p.getInt(KEY_INTERVAL_NORMAL_MIN, DEFAULT_INTERVAL_NORMAL_MIN));
        }
        if (p.contains(KEY_INTERVAL_MIN)) {
            return Math.max(MIN_PUSH_INTERVAL_MIN, p.getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_NORMAL_MIN));
        }
        return DEFAULT_INTERVAL_NORMAL_MIN;
    }

    /** Şarj sırasında push aralığı (saniye). */
    public static int intervalChargingSec(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.contains(KEY_INTERVAL_CHARGING_SEC)) {
            return Math.max(MIN_PUSH_INTERVAL_CHARGING_SEC,
                    p.getInt(KEY_INTERVAL_CHARGING_SEC, DEFAULT_INTERVAL_CHARGING_SEC));
        }
        // Eski dakika kaydı → saniye
        if (p.contains(KEY_INTERVAL_CHARGING_MIN_LEGACY)) {
            int min = Math.max(1, p.getInt(KEY_INTERVAL_CHARGING_MIN_LEGACY, 1));
            return Math.max(MIN_PUSH_INTERVAL_CHARGING_SEC, min * 60);
        }
        return DEFAULT_INTERVAL_CHARGING_SEC;
    }

    /** @deprecated {@link #intervalNormalMin(Context)} kullan. */
    public static int intervalMin(Context ctx) {
        return intervalNormalMin(ctx);
    }

    public static PushMode pushMode(boolean charging) {
        return charging ? PushMode.CHARGING : PushMode.NORMAL;
    }

    public static long intervalMsForMode(Context ctx, PushMode mode) {
        if (mode == PushMode.CHARGING) {
            return intervalChargingSec(ctx) * 1000L;
        }
        return intervalNormalMin(ctx) * 60_000L;
    }

    public static long intervalMs(Context ctx) {
        return intervalNormalMin(ctx) * 60_000L;
    }

    public static void setIntervalNormalMin(Context ctx, int min) {
        prefs(ctx).edit()
                .putInt(KEY_INTERVAL_NORMAL_MIN, Math.max(MIN_PUSH_INTERVAL_MIN, min))
                .apply();
    }

    public static void setIntervalChargingSec(Context ctx, int sec) {
        prefs(ctx).edit()
                .putInt(KEY_INTERVAL_CHARGING_SEC, Math.max(MIN_PUSH_INTERVAL_CHARGING_SEC, sec))
                .apply();
    }

    /** HA komut poll (sn). En az {@link #MIN_POLL_INTERVAL_SEC}; varsayılan 30. */
    public static int pollIntervalSec(Context ctx) {
        return Math.max(MIN_POLL_INTERVAL_SEC, prefs(ctx).getInt(KEY_POLL_INTERVAL_SEC, 30));
    }

    public static long pollIntervalMs(Context ctx) {
        return pollIntervalSec(ctx) * 1000L;
    }

    public static boolean pollEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_POLL_ENABLED, false);
    }

    public static long vehicleLastRunMs(Context ctx) {
        return prefs(ctx).getLong(KEY_VEHICLE_LAST_RUN_MS, 0L);
    }

    public static void setVehicleLastRunMs(Context ctx, long ms) {
        if (ms <= 0) return;
        prefs(ctx).edit().putLong(KEY_VEHICLE_LAST_RUN_MS, ms).apply();
    }

    public static long refreshHandledMs(Context ctx) {
        return prefs(ctx).getLong(KEY_REFRESH_HANDLED_MS, 0L);
    }

    public static void setRefreshHandledMs(Context ctx, long ms) {
        prefs(ctx).edit().putLong(KEY_REFRESH_HANDLED_MS, Math.max(0L, ms)).apply();
    }

    public static boolean wifiOnly(Context ctx) {
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

    public static boolean wifiOnBoot(Context ctx) {
        return prefs(ctx).getBoolean(KEY_WIFI_ON_BOOT, !WifiHelper.isSim());
    }

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
                            int intervalNormalMin, int intervalChargingSec,
                            boolean wifiOnly, boolean insecure,
                            boolean autoStart, boolean wifiOnBoot, boolean verboseLog,
                            boolean pollEnabled, int pollIntervalSec) {
        prefs(ctx).edit()
                .putString(KEY_URL, url == null ? "" : url.trim())
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_PREFIX, prefix)
                .putInt(KEY_INTERVAL_NORMAL_MIN, Math.max(MIN_PUSH_INTERVAL_MIN, intervalNormalMin))
                .putInt(KEY_INTERVAL_CHARGING_SEC, Math.max(MIN_PUSH_INTERVAL_CHARGING_SEC, intervalChargingSec))
                .putInt(KEY_INTERVAL_MIN, Math.max(MIN_PUSH_INTERVAL_MIN, intervalNormalMin))
                .putBoolean(KEY_WIFI_ONLY, wifiOnly)
                .putBoolean(KEY_INSECURE, insecure)
                .putBoolean(KEY_AUTOSTART, autoStart)
                .putBoolean(KEY_WIFI_ON_BOOT, wifiOnBoot)
                .putBoolean(KEY_VERBOSE_LOG, verboseLog)
                .putBoolean(KEY_POLL_ENABLED, pollEnabled)
                .putInt(KEY_POLL_INTERVAL_SEC, Math.max(MIN_POLL_INTERVAL_SEC, pollIntervalSec))
                .apply();
        refreshVerboseCache(ctx);
    }

    public static boolean applyJson(Context ctx, JSONObject o) {
        if (o == null) return false;
        String u = o.optString("url", "").trim();
        String t = o.optString("token", "").trim();
        if (u.isEmpty() || t.isEmpty()) return false;
        int legacy = o.optInt("interval", DEFAULT_INTERVAL_NORMAL_MIN);
        int chargingSec = o.has("intervalChargingSec")
                ? o.optInt("intervalChargingSec", DEFAULT_INTERVAL_CHARGING_SEC)
                : o.optInt("intervalCharging", 1) * 60; // eski dakika alanı
        save(ctx, u, t, o.optString("prefix", "mg4"),
                o.optInt("intervalNormal", legacy),
                chargingSec,
                o.optBoolean("wifiOnly", !WifiHelper.isSim()),
                o.optBoolean("insecure", false),
                o.optBoolean("autoStart", true),
                o.optBoolean("wifiOnBoot", !WifiHelper.isSim()),
                o.optBoolean("verboseLog", false),
                o.optBoolean("pollEnabled", false),
                o.optInt("pollIntervalSec", 30));
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
