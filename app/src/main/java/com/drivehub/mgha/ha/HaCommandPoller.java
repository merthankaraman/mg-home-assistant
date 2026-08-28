package com.drivehub.mgha.ha;

import android.content.Context;

import com.drivehub.mgha.hardware.VehicleReader;
import com.drivehub.mgha.net.WifiHelper;
import com.drivehub.mgha.prefs.HaSettings;
import com.drivehub.mgha.util.MghaLog;

/**
 * HA'daki komut varlıklarını okur ve araca uygular (poll modu açıkken).
 */
public final class HaCommandPoller {
    private static final String TAG = "MGHA_POLL";

    private static Boolean sLastHvacOn;
    private static Integer sLastChargeLimitPct;
    private static Integer sLastHvacTempC;
    private static Integer sLastHvacFanLevel;
    private static Integer sLastMediaVolume;

    private HaCommandPoller() {}

    public static void poll(Context ctx) {
        if (!HaSettings.pollEnabled(ctx)) return;
        if (!HaSettings.isConfigured(ctx)) return;
        if (!WifiHelper.canSend(ctx, HaSettings.wifiOnly(ctx))) return;

        String prefix = HaSettings.prefix(ctx);
        HomeAssistantClient client = new HomeAssistantClient(
                ctx,
                HaSettings.url(ctx),
                HaSettings.token(ctx),
                HaSettings.allowInsecureSsl(ctx));

        pollHvac(ctx, client, prefix);
        pollHvacTemp(ctx, client, prefix);
        pollHvacFan(ctx, client, prefix);
        pollMediaVolume(ctx, client, prefix);
        pollChargeLimit(ctx, client, prefix);
    }

    private static void pollHvac(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "switch." + prefix + "_hvac";
        Boolean hvacOn = client.getSwitchState(entity);
        if (hvacOn == null) {
            MghaLog.w(TAG, "poll okunamadı: " + entity);
            return;
        }
        if (sLastHvacOn != null && sLastHvacOn.equals(hvacOn)) {
            return;
        }
        if (!VehicleReader.setHvacPower(hvacOn)) {
            MghaLog.w(TAG, "klima yazılamadı → " + (hvacOn ? "aç" : "kapat"));
            return;
        }
        sLastHvacOn = hvacOn;
        MghaLog.i(TAG, "klima " + (hvacOn ? "açıldı" : "kapatıldı") + " ← " + entity);
    }

    private static void pollHvacTemp(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "number." + prefix + "_hvac_temperature";
        Integer tempC = client.getNumberState(entity);
        if (tempC == null) {
            MghaLog.w(TAG, "poll okunamadı: " + entity);
            return;
        }
        if (tempC < 16 || tempC > 30) {
            MghaLog.w(TAG, "geçersiz klima °C: " + tempC + " (16–30)");
            return;
        }
        if (sLastHvacTempC != null && sLastHvacTempC.equals(tempC)) {
            return;
        }
        if (!VehicleReader.setHvacTemperature(tempC)) {
            MghaLog.w(TAG, "klima °C yazılamadı → " + tempC);
            return;
        }
        sLastHvacTempC = tempC;
        MghaLog.i(TAG, "klima " + tempC + "°C ← " + entity);
    }

    private static void pollHvacFan(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "number." + prefix + "_hvac_fan";
        Integer level = client.getNumberState(entity);
        if (level == null) {
            MghaLog.w(TAG, "poll okunamadı: " + entity);
            return;
        }
        if (level < VehicleReader.HVAC_FAN_MIN || level > VehicleReader.HVAC_FAN_AUTO) {
            MghaLog.w(TAG, "geçersiz klima fan: " + level + " (1–11, 12=oto)");
            return;
        }
        if (level > 11 && level < VehicleReader.HVAC_FAN_AUTO) {
            MghaLog.w(TAG, "geçersiz klima fan: " + level + " (1–11, 12=oto)");
            return;
        }
        if (sLastHvacFanLevel != null && sLastHvacFanLevel.equals(level)) {
            return;
        }
        if (!VehicleReader.setHvacFanSpeed(level)) {
            MghaLog.w(TAG, "klima fan yazılamadı → " + level);
            return;
        }
        sLastHvacFanLevel = level;
        MghaLog.i(TAG, "klima fan " + level + " ← " + entity);
    }

    private static void pollMediaVolume(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "number." + prefix + "_media_volume";
        Integer level = client.getNumberState(entity);
        if (level == null) {
            MghaLog.w(TAG, "poll okunamadı: " + entity);
            return;
        }
        if (level < 0 || level > 32) {
            MghaLog.w(TAG, "geçersiz medya sesi: " + level + " (0–32)");
            return;
        }
        if (sLastMediaVolume != null && sLastMediaVolume.equals(level)) {
            return;
        }
        if (!VehicleReader.setMediaVolumeLevel(level)) {
            MghaLog.w(TAG, "medya sesi yazılamadı → " + level);
            return;
        }
        sLastMediaVolume = level;
        MghaLog.i(TAG, "medya sesi " + level + " ← " + entity);
    }

    private static void pollChargeLimit(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "number." + prefix + "_charge_limit";
        Integer pct = client.getNumberState(entity);
        if (pct == null) {
            MghaLog.w(TAG, "poll okunamadı: " + entity);
            return;
        }
        if (VehicleReader.chargeLimitPercentToStep(pct) < 0) {
            MghaLog.w(TAG, "geçersiz şarj sınırı: " + pct + " (40–100, 10'ar)");
            return;
        }
        if (sLastChargeLimitPct != null && sLastChargeLimitPct.equals(pct)) {
            return;
        }
        if (!VehicleReader.setChargeLimitPercent(pct)) {
            MghaLog.w(TAG, "şarj sınırı yazılamadı → %" + pct);
            return;
        }
        sLastChargeLimitPct = pct;
        MghaLog.i(TAG, "şarj sınırı %" + pct + " ← " + entity);
    }

    /** Push sonrası araba değeri; poll aynı değeri tekrar yazmasın. */
    public static void noteHvacFromCar(boolean on) {
        sLastHvacOn = on;
    }

    /** Push sonrası araba değeri; poll aynı değeri tekrar yazmasın. */
    public static void noteChargeLimitFromCar(int pct) {
        if (pct >= 40 && pct <= 100 && pct % 10 == 0) {
            sLastChargeLimitPct = pct;
        }
    }

    public static void noteHvacTempFromCar(int tempC) {
        if (tempC >= 16 && tempC <= 30) {
            sLastHvacTempC = tempC;
        }
    }

    public static void noteHvacFanFromCar(int level) {
        if (level >= VehicleReader.HVAC_FAN_MIN && level <= VehicleReader.HVAC_FAN_MAX_MANUAL) {
            sLastHvacFanLevel = level;
        } else if (level == VehicleReader.HVAC_FAN_AUTO) {
            sLastHvacFanLevel = level;
        }
    }

    public static void noteMediaVolumeFromCar(int level) {
        if (level >= 0 && level <= 32) {
            sLastMediaVolume = level;
        }
    }

    public static void resetCache() {
        sLastHvacOn = null;
        sLastChargeLimitPct = null;
        sLastHvacTempC = null;
        sLastHvacFanLevel = null;
        sLastMediaVolume = null;
    }
}
