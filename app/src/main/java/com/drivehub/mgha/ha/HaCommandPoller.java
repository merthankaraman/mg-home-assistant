package com.drivehub.mgha.ha;

import android.content.Context;

import com.drivehub.mgha.hardware.VehicleReader;
import com.drivehub.mgha.net.WifiHelper;
import com.drivehub.mgha.prefs.HaSettings;
import com.drivehub.mgha.util.MghaLog;

/**
 * HA'daki komut varlıklarını okur ve araca uygular.
 * Feedback: İngilizce anahtar + isteğe bağlı arg (HA yerelleştirir).
 */
public final class HaCommandPoller {
    private static final String TAG = "MGHA_POLL";
    private static final long VERIFY_DELAY_MS = 900L;
    /** Push sonrası HA'nın switch/number senkronu için bekleme. */
    private static final long COMMANDS_POLL_DELAY_MS = 3_000L;

    /** Komut poll'u açık mı (push + gecikme sonrası). */
    private static boolean sCommandsBaselined;
    private static boolean sBaselinePending;
    private static long sBaselinePendingAtMs;
    private static boolean sPendingCharging;
    private static Boolean sPendingHvac;

    private static Boolean sLastHvacOn;
    private static Boolean sLastCharging;
    private static Integer sLastChargeLimitPct;
    private static Integer sLastHvacTempC;
    private static Integer sLastHvacFanLevel;
    private static Integer sLastMediaVolume;
    private static Integer sLastIntervalNormal;
    private static Integer sLastIntervalCharging;

    public static final class CommandFeedback {
        public final String status; // idle | ok | fail
        public final String command;
        /** Stable English key for HA i18n, e.g. on, write_failed, verify_mismatch. */
        public final String detailKey;
        /** Optional argument (value, "actual/expected", …). */
        public final String detailArg;
        public final long atMs;
        public final long seq;

        CommandFeedback(String status, String command, String detailKey, String detailArg,
                        long atMs, long seq) {
            this.status = status;
            this.command = command;
            this.detailKey = detailKey;
            this.detailArg = detailArg;
            this.atMs = atMs;
            this.seq = seq;
        }
    }

    private static volatile CommandFeedback sFeedback =
            new CommandFeedback("idle", "", "", null, 0L, 0L);
    private static long sFeedbackSeq;

    public static CommandFeedback lastFeedback() {
        return sFeedback;
    }

    private static void noteFeedback(String status, String command, String detailKey) {
        noteFeedback(status, command, detailKey, null);
    }

    private static void noteFeedback(String status, String command, String detailKey, String detailArg) {
        sFeedbackSeq++;
        sFeedback = new CommandFeedback(status, command, detailKey, detailArg,
                System.currentTimeMillis(), sFeedbackSeq);
        MghaLog.i(TAG, "feedback " + status + " [" + command + "] " + detailKey
                + (detailArg != null ? (" arg=" + detailArg) : ""));
    }

    private static void sleepVerify() {
        try {
            Thread.sleep(VERIFY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return true ise hemen full push yapılmalı */
    public static boolean poll(Context ctx) {
        if (!HaSettings.isConfigured(ctx)) return false;
        if (!WifiHelper.canSend(ctx, HaSettings.wifiOnly(ctx))) return false;

        String prefix = HaSettings.prefix(ctx);
        HomeAssistantClient client = new HomeAssistantClient(
                ctx,
                HaSettings.url(ctx),
                HaSettings.token(ctx),
                HaSettings.allowInsecureSsl(ctx));

        long seqBefore = sFeedbackSeq;
        boolean forcePush = pollRefresh(ctx, client, prefix);
        pollIntervals(ctx, client, prefix);

        if (HaSettings.pollEnabled(ctx) && ensureCommandsBaselined()) {
            pollHvac(ctx, client, prefix);
            pollHvacTemp(ctx, client, prefix);
            pollHvacFan(ctx, client, prefix);
            pollMediaVolume(ctx, client, prefix);
            pollChargeLimit(ctx, client, prefix);
            pollCharging(ctx, client, prefix);
        }
        return forcePush || sFeedbackSeq != seqBefore;
    }

    private static boolean pollRefresh(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "button." + prefix + "_refresh";
        Long pressedAt = client.getButtonPressedAtMs(entity);
        if (pressedAt == null || pressedAt <= 0) {
            return false;
        }
        long handled = HaSettings.refreshHandledMs(ctx);
        if (pressedAt <= handled) {
            return false;
        }
        HaSettings.setRefreshHandledMs(ctx, pressedAt);
        noteFeedback("ok", "refresh", "requested");
        return true;
    }

    private static void pollIntervals(Context ctx, HomeAssistantClient client, String prefix) {
        pollIntervalNormal(ctx, client, "number." + prefix + "_interval_normal");
        pollIntervalCharging(ctx, client, "number." + prefix + "_interval_charging");
    }

    private static void pollIntervalNormal(Context ctx, HomeAssistantClient client, String entity) {
        Integer min = client.getNumberState(entity);
        if (min == null) return;
        if (sLastIntervalNormal != null && sLastIntervalNormal.equals(min)) return;
        if (min < HaSettings.MIN_PUSH_INTERVAL_MIN) {
            sLastIntervalNormal = min;
            noteFeedback("fail", "interval_normal", "invalid", String.valueOf(min));
            return;
        }
        int current = HaSettings.intervalNormalMin(ctx);
        if (min == current) {
            sLastIntervalNormal = min;
            return;
        }
        HaSettings.setIntervalNormalMin(ctx, min);
        sLastIntervalNormal = min;
        noteFeedback("ok", "interval_normal", "set_min", String.valueOf(min));
    }

    private static void pollIntervalCharging(Context ctx, HomeAssistantClient client, String entity) {
        Integer sec = client.getNumberState(entity);
        if (sec == null) return;
        if (sLastIntervalCharging != null && sLastIntervalCharging.equals(sec)) return;
        if (sec < HaSettings.MIN_PUSH_INTERVAL_CHARGING_SEC) {
            sLastIntervalCharging = sec;
            noteFeedback("fail", "interval_charging", "invalid", String.valueOf(sec));
            return;
        }
        int current = HaSettings.intervalChargingSec(ctx);
        if (sec == current) {
            sLastIntervalCharging = sec;
            return;
        }
        HaSettings.setIntervalChargingSec(ctx, sec);
        sLastIntervalCharging = sec;
        noteFeedback("ok", "interval_charging", "set_sec", String.valueOf(sec));
    }

    private static void pollHvac(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "switch." + prefix + "_hvac";
        Boolean hvacOn = client.getSwitchState(entity);
        if (hvacOn == null) return;
        Boolean carNow = VehicleReader.readHvacPowerOn();
        if (carNow != null && hvacOn.equals(carNow)) {
            sLastHvacOn = hvacOn;
            return;
        }
        // Aynı HA hedefini tekrar deneme (fail olsa bile spam push olmasın)
        if (sLastHvacOn != null && sLastHvacOn.equals(hvacOn)) return;
        String wantKey = hvacOn ? "on" : "off";
        sLastHvacOn = hvacOn;
        if (!VehicleReader.setHvacPower(hvacOn)) {
            noteFeedback("fail", "hvac", "write_failed", wantKey);
            return;
        }
        sleepVerify();
        Boolean now = VehicleReader.readHvacPowerOn();
        if (now == null) {
            noteFeedback("fail", "hvac", "verify_unread");
            return;
        }
        if (now != hvacOn) {
            noteFeedback("fail", "hvac", "verify_mismatch",
                    (now ? "on" : "off") + "/" + wantKey);
            return;
        }
        noteFeedback("ok", "hvac", wantKey);
    }

    private static void pollCharging(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "switch." + prefix + "_charging";
        Boolean want = client.getSwitchState(entity);
        if (want == null) return;
        boolean carNow = VehicleReader.readIsCharging();
        if (want.equals(carNow)) {
            sLastCharging = want;
            return;
        }
        if (sLastCharging != null && sLastCharging.equals(want)) return;
        String wantKey = want ? "start" : "stop";
        sLastCharging = want;
        if (!VehicleReader.setChargingControl(want)) {
            noteFeedback("fail", "charging", "write_failed", wantKey);
            return;
        }
        sleepVerify();
        boolean now = VehicleReader.readIsCharging();
        if (now != want) {
            noteFeedback("fail", "charging", "verify_mismatch",
                    (now ? "on" : "off") + "/" + wantKey);
            return;
        }
        noteFeedback("ok", "charging", wantKey);
    }

    private static void pollHvacTemp(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "number." + prefix + "_hvac_temperature";
        Integer tempC = client.getNumberState(entity);
        if (tempC == null) return;
        if (sLastHvacTempC != null && sLastHvacTempC.equals(tempC)) return;
        if (tempC < 16 || tempC > 30) {
            sLastHvacTempC = tempC;
            noteFeedback("fail", "hvac_temp", "invalid", tempC + "C");
            return;
        }
        sLastHvacTempC = tempC;
        if (!VehicleReader.setHvacTemperature(tempC)) {
            noteFeedback("fail", "hvac_temp", "write_failed", tempC + "C");
            return;
        }
        sleepVerify();
        int now = VehicleReader.readHvacTemperatureC();
        if (now < 0) {
            noteFeedback("fail", "hvac_temp", "verify_unread");
            return;
        }
        if (now != tempC) {
            noteFeedback("fail", "hvac_temp", "verify_mismatch", now + "C/" + tempC + "C");
            return;
        }
        noteFeedback("ok", "hvac_temp", "set", tempC + "C");
    }

    private static void pollHvacFan(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "number." + prefix + "_hvac_fan";
        Integer level = client.getNumberState(entity);
        if (level == null) return;
        if (sLastHvacFanLevel != null && sLastHvacFanLevel.equals(level)) return;
        if (level < VehicleReader.HVAC_FAN_MIN || level > VehicleReader.HVAC_FAN_AUTO
                || (level > 11 && level < VehicleReader.HVAC_FAN_AUTO)) {
            sLastHvacFanLevel = level;
            noteFeedback("fail", "hvac_fan", "invalid", String.valueOf(level));
            return;
        }
        sLastHvacFanLevel = level;
        if (!VehicleReader.setHvacFanSpeed(level)) {
            noteFeedback("fail", "hvac_fan", "write_failed", String.valueOf(level));
            return;
        }
        sleepVerify();
        int now = VehicleReader.readHvacFanLevel();
        if (now < 0) {
            noteFeedback("fail", "hvac_fan", "verify_unread");
            return;
        }
        if (now != level) {
            noteFeedback("fail", "hvac_fan", "verify_mismatch", now + "/" + level);
            return;
        }
        if (level == VehicleReader.HVAC_FAN_AUTO) {
            noteFeedback("ok", "hvac_fan", "auto");
        } else {
            noteFeedback("ok", "hvac_fan", "set", String.valueOf(level));
        }
    }

    private static void pollMediaVolume(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "number." + prefix + "_media_volume";
        Integer level = client.getNumberState(entity);
        if (level == null) return;
        if (sLastMediaVolume != null && sLastMediaVolume.equals(level)) return;
        if (level < 0 || level > 32) {
            sLastMediaVolume = level;
            noteFeedback("fail", "media_volume", "invalid", String.valueOf(level));
            return;
        }
        sLastMediaVolume = level;
        if (!VehicleReader.setMediaVolumeLevel(level)) {
            noteFeedback("fail", "media_volume", "write_failed", String.valueOf(level));
            return;
        }
        sleepVerify();
        int now = VehicleReader.readMediaVolumeLevel();
        if (now < 0) {
            noteFeedback("fail", "media_volume", "verify_unread");
            return;
        }
        if (now != level) {
            noteFeedback("fail", "media_volume", "verify_mismatch", now + "/" + level);
            return;
        }
        noteFeedback("ok", "media_volume", "set", String.valueOf(level));
    }

    private static void pollChargeLimit(Context ctx, HomeAssistantClient client, String prefix) {
        String entity = "number." + prefix + "_charge_limit";
        Integer pct = client.getNumberState(entity);
        if (pct == null) return;
        if (sLastChargeLimitPct != null && sLastChargeLimitPct.equals(pct)) return;
        if (VehicleReader.chargeLimitPercentToStep(pct) < 0) {
            sLastChargeLimitPct = pct;
            noteFeedback("fail", "charge_limit", "invalid", pct + "%");
            return;
        }
        sLastChargeLimitPct = pct;
        if (!VehicleReader.setChargeLimitPercent(pct)) {
            noteFeedback("fail", "charge_limit", "write_failed", pct + "%");
            return;
        }
        sleepVerify();
        int now = VehicleReader.readChargeLimitPercent();
        if (now < 0) {
            noteFeedback("fail", "charge_limit", "verify_unread");
            return;
        }
        if (now != pct) {
            noteFeedback("fail", "charge_limit", "verify_mismatch", now + "%/" + pct + "%");
            return;
        }
        noteFeedback("ok", "charge_limit", "set", pct + "%");
    }

    public static void noteHvacFromCar(boolean on) {
        // Baseline sonrası poll "son işlenen HA hedefi" tutar; push ile ezme.
        if (sCommandsBaselined) return;
        sLastHvacOn = on;
    }

    /** Başarılı push sonrası: HA senkronu için {@link #COMMANDS_POLL_DELAY_MS} beklenir. */
    public static void scheduleCommandsBaseline(boolean charging, Boolean hvacOn) {
        if (sCommandsBaselined) return;
        if (!sBaselinePending) {
            sBaselinePending = true;
            sBaselinePendingAtMs = System.currentTimeMillis();
            MghaLog.i(TAG, "komut poll " + (COMMANDS_POLL_DELAY_MS / 1000L)
                    + "s bekleniyor (HA senkron)");
        }
        sPendingCharging = charging;
        sPendingHvac = hvacOn;
    }

    private static boolean ensureCommandsBaselined() {
        if (sCommandsBaselined) return true;
        if (!sBaselinePending) return false;
        long elapsed = System.currentTimeMillis() - sBaselinePendingAtMs;
        if (elapsed < COMMANDS_POLL_DELAY_MS) return false;
        sCommandsBaselined = true;
        sBaselinePending = false;
        sLastCharging = sPendingCharging;
        if (sPendingHvac != null) {
            sLastHvacOn = sPendingHvac;
        }
        MghaLog.i(TAG, "komut poll açıldı (" + elapsed + "ms)");
        return true;
    }

    /** Araç uyandığında / servis başında: önce push, sonra komut. */
    public static void resetCommandsBaseline() {
        sCommandsBaselined = false;
        sBaselinePending = false;
        sBaselinePendingAtMs = 0L;
        sPendingHvac = null;
        sLastHvacOn = null;
        sLastCharging = null;
        sLastChargeLimitPct = null;
        sLastHvacTempC = null;
        sLastHvacFanLevel = null;
        sLastMediaVolume = null;
    }

    public static void noteChargeLimitFromCar(int pct) {
        if (sCommandsBaselined) return;
        if (pct >= 40 && pct <= 100 && pct % 10 == 0) {
            sLastChargeLimitPct = pct;
        }
    }

    public static void noteHvacTempFromCar(int tempC) {
        if (sCommandsBaselined) return;
        if (tempC >= 16 && tempC <= 30) {
            sLastHvacTempC = tempC;
        }
    }

    public static void noteHvacFanFromCar(int level) {
        if (sCommandsBaselined) return;
        if (level >= VehicleReader.HVAC_FAN_MIN && level <= VehicleReader.HVAC_FAN_MAX_MANUAL) {
            sLastHvacFanLevel = level;
        } else if (level == VehicleReader.HVAC_FAN_AUTO) {
            sLastHvacFanLevel = level;
        }
    }

    public static void noteMediaVolumeFromCar(int level) {
        if (sCommandsBaselined) return;
        if (level >= 0 && level <= 32) {
            sLastMediaVolume = level;
        }
    }

    public static void noteIntervalsFromCar(int normalMin, int chargingSec) {
        if (sCommandsBaselined) return;
        sLastIntervalNormal = normalMin;
        sLastIntervalCharging = chargingSec;
    }

    public static void resetCache() {
        resetCommandsBaseline();
        sLastIntervalNormal = null;
        sLastIntervalCharging = null;
    }
}
