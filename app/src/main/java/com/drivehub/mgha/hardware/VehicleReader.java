package com.drivehub.mgha.hardware;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.drivehub.mgha.util.MghaLog;

import androidx.core.content.ContextCompat;

import com.drivehub.mgha.net.WifiHelper;
import com.drivehub.mgha.prefs.HaSettings;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MG4 EH32 CarPropertyManager (yansıma) okuyucu — DriveHub Dort ile aynı property ID'leri.
 */
public final class VehicleReader {
    private static final String TAG = "MGHA_HW";

    private static final int AREA_GLOBAL = 0x01000000;
    private static final int AREA_HVAC = 0x75; // HVAC_ALL
    private static final int AREA_HVAC_LEFT = 0x31; // sürücü (getDrvTemp zone)

    private static final int PROP_SPEED = 0x11600207;
    private static final int PROP_SOC = 0x2160F404;
    private static final int PROP_RANGE = 0x2140F41C;
    private static final int PROP_BATT_VOLT = 0x2160F406;
    private static final int PROP_CHR_AMP_ACT = 0x2160F407;
    private static final int PROP_CHR_AMP_EXP = 0x2160F40A;
    private static final int PROP_AC_AMP = 0x2160F43C;
    private static final int PROP_AC_VOLT = 0x2160F43D;
    private static final int PROP_CHG_STATUS = 0x2140F409;
    /**
     * Hedef / limit SOC — getChargingCloseSoc.
     * Ham değer 1..7 basamak; yüzde = 40 + (n - 1) * 10 → 1=%40 … 7=%100.
     */
    private static final int PROP_CHARGE_LIMIT_SOC = 0x2140F40C;
    /** Şarj başlat/durdur — getChargingControlSwitch / setChargingControlSwitch. */
    private static final int PROP_CHARGE_CONTROL = 0x2140F412;
    /** EV sistemi READY — getEngineState; 1 = çalışıyor. */
    private static final int PROP_ENGINE_STATE = 0x2140157C;
    /** Tahmini kalan şarj süresi (dakika) — getPredictChargingTime. */
    private static final int PROP_CHARGE_REMAIN_MIN = 0x2140F417;
    private static final int PROP_TOTAL_MILEAGE = 0x21401566;
    private static final int PROP_TIRE_PRESSURE_FL = 0x21401553;
    private static final int PROP_TIRE_PRESSURE_FR = 0x21401554;
    private static final int PROP_TIRE_PRESSURE_RL = 0x21401555;
    private static final int PROP_TIRE_PRESSURE_RR = 0x21401556;
    /** Klima ana switch — CPM (area HVAC_ALL). */
    private static final int PROP_HVAC_POWER = 0x15402503;
    /** Otomatik klima — getAutoStatus / setAutoStatus (area HVAC_ALL). */
    private static final int PROP_HVAC_AUTO = 0x15402502;
    /** Fan hızı — getAirVolumeLevel / setAirVolumeLevel (area HVAC_ALL), 1–11. */
    private static final int PROP_HVAC_FAN = 0x1540250D;
    public static final int HVAC_FAN_MIN = 1;
    public static final int HVAC_FAN_MAX_MANUAL = 11;
    /** HA/poll için otomatik fan temsili; CPM'de ayrı {@link #PROP_HVAC_AUTO}. */
    public static final int HVAC_FAN_AUTO = 12;
    /** Sürücü hedef °C — getDrvTemp / setDrvTemp (area HVAC_LEFT, float). */
    private static final int PROP_DRV_TEMP = 0x1560250B;
    /** Dış ortam °C — getOutCarTemp (area HVAC_ALL). */
    private static final int PROP_OUT_CAR_TEMP = 0x15602511;
    private static final float OUT_CAR_TEMP_INVALID = -10000f;
    /** MG4 multimedya ses adımı (ekran 0–32). */
    private static final int MEDIA_VOLUME_MAX = 32;
    /** {@link android.media.AudioAttributes#USAGE_MEDIA} */
    private static final int AUDIO_USAGE_MEDIA = 1;

    private static final String SAIC_MAP_PACKAGE = "com.saicmotor.adapterservice";
    private static final String SAIC_MAP_SERVICE_CLASS = SAIC_MAP_PACKAGE + ".services.MapService";

    private static final ConcurrentHashMap<Integer, Object> sBmsCache = new ConcurrentHashMap<>();

    private static Context sAppContext;
    private static Object sCar;
    private static Object sCarPropertyManager;
    private static Object sCarAudioManager;
    private static int sMediaVolumeGroupId = -1;
    private static boolean sCarBindAttempted;
    private static boolean sMapBindAttempted;
    private static IBinder sSaicMapBinder;
    private static volatile Location sCachedGps;
    private static boolean sGpsListening;
    private static LocationManager sLocationManager;
    private static final LocationListener sGpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location != null) {
                sCachedGps = location;
                MghaLog.i(TAG, "GPS update " + location.getProvider()
                        + " lat=" + location.getLatitude()
                        + " lon=" + location.getLongitude());
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    private static final ServiceConnection sMapConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            sSaicMapBinder = service;
            MghaLog.i(TAG, "SAIC MapService bağlı");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sSaicMapBinder = null;
        }
    };

    private VehicleReader() {}

    public static synchronized void init(Context context) {
        if (context == null) return;
        sAppContext = context.getApplicationContext();
        HaSettings.refreshVerboseCache(sAppContext);
        // Telefonda / sim’de android.car yok; bağlanmaya çalışma
        if (WifiHelper.isSim()) {
            MghaLog.i(TAG, "sim: Car/Map bind atlandı");
            return;
        }
        bindCarService(sAppContext);
        bindSaicMapService(sAppContext);
        startGpsUpdates(sAppContext);
    }

    public static boolean isReady() {
        return sCarPropertyManager != null;
    }

    /** Klima aç/kapat — CPM {@code PROP_HVAC_POWER}: yazmada 1=toggle, okuma 0=kapalı 1=açık. */
    public static boolean setHvacPower(boolean on) {
        if (sAppContext != null && HaSettings.demoMode(sAppContext)) {
            MghaLog.i(TAG, "demo: setHvacPower(" + on + ")");
            return true;
        }
        if (sAppContext != null && !WifiHelper.isSim()) {
            ensureReady(sAppContext);
        }
        Boolean current = hvacOnFromCpm(getIntArea(PROP_HVAC_POWER, AREA_HVAC));
        if (current != null && current == on) {
            MghaLog.i(TAG, "hvac zaten " + (on ? "açık" : "kapalı"));
            return true;
        }
        if (current == null) {
            MghaLog.w(TAG, "hvac durumu okunamadı, toggle atlandı");
            return false;
        }
        return setIntArea(PROP_HVAC_POWER, AREA_HVAC, 1);
    }

    /** Hedef klima °C — CPM {@code PROP_DRV_TEMP} area {@code AREA_HVAC_LEFT}. */
    public static boolean setHvacTemperature(int tempC) {
        if (tempC < 16 || tempC > 30) return false;
        if (sAppContext != null && HaSettings.demoMode(sAppContext)) {
            MghaLog.i(TAG, "demo: setHvacTemperature(" + tempC + ")");
            return true;
        }
        if (sAppContext != null && !WifiHelper.isSim()) {
            ensureReady(sAppContext);
        }
        return setFloatArea(PROP_DRV_TEMP, AREA_HVAC_LEFT, (float) tempC);
    }

    /** Şarj başlat/durdur — CPM {@code PROP_CHARGE_CONTROL} (1=başlat, 0=durdur). */
    public static boolean setChargingControl(boolean start) {
        if (sAppContext != null && HaSettings.demoMode(sAppContext)) {
            MghaLog.i(TAG, "demo: setChargingControl(" + start + ")");
            return true;
        }
        if (sAppContext != null && !WifiHelper.isSim()) {
            ensureReady(sAppContext);
        }
        return setIntArea(PROP_CHARGE_CONTROL, AREA_GLOBAL, start ? 1 : 0);
    }

    /** Araç READY (EV güç aktarma aktif). */
    private static boolean readVehicleReady() {
        int v = getInt(PROP_ENGINE_STATE);
        return v == 1;
    }

    /** Klima fan hızı — manuel 1–11; {@link #HVAC_FAN_AUTO} → {@code PROP_HVAC_AUTO}. */
    public static boolean setHvacFanSpeed(int level) {
        if (level < HVAC_FAN_MIN || level > HVAC_FAN_AUTO) return false;
        if (level > HVAC_FAN_MAX_MANUAL && level < HVAC_FAN_AUTO) return false;
        if (sAppContext != null && HaSettings.demoMode(sAppContext)) {
            MghaLog.i(TAG, "demo: setHvacFanSpeed(" + level + ")");
            return true;
        }
        if (sAppContext != null && !WifiHelper.isSim()) {
            ensureReady(sAppContext);
        }
        if (level == HVAC_FAN_AUTO) {
            return setHvacAuto(true);
        }
        if (!setHvacAuto(false)) {
            MghaLog.w(TAG, "klima auto kapatılamadı, fan yine de yazılıyor");
        }
        return setIntArea(PROP_HVAC_FAN, AREA_HVAC, level);
    }

    private static boolean setHvacAuto(boolean on) {
        return setIntArea(PROP_HVAC_AUTO, AREA_HVAC, on ? 1 : 0);
    }

    private static boolean isHvacAutoOn() {
        int raw = getIntArea(PROP_HVAC_AUTO, AREA_HVAC);
        return raw == 1;
    }

    private static int readHvacFanSpeed() {
        if (isHvacAutoOn()) {
            return HVAC_FAN_AUTO;
        }
        int v = getIntArea(PROP_HVAC_FAN, AREA_HVAC);
        if (v >= HVAC_FAN_MIN && v <= HVAC_FAN_MAX_MANUAL) {
            return v;
        }
        return -1;
    }

    private static int readDriverTempC() {
        float v = getFloatArea(PROP_DRV_TEMP, AREA_HVAC_LEFT);
        if (Float.isNaN(v) || v < 16f || v > 30f) return -1;
        return Math.round(v);
    }

    /** Multimedya ses — önce {@code CarAudioManager} (ekranla aynı 0–32), yoksa STREAM_MUSIC. */
    public static int readMediaVolumeLevel() {
        if (sAppContext != null && !WifiHelper.isSim()) {
            ensureReady(sAppContext);
        }
        Integer car = readCarMediaVolume();
        if (car != null) return car;
        return readStreamMusicVolumeScaled();
    }

    public static boolean setMediaVolumeLevel(int level) {
        if (level < 0 || level > MEDIA_VOLUME_MAX) return false;
        if (sAppContext != null && HaSettings.demoMode(sAppContext)) {
            MghaLog.i(TAG, "demo: setMediaVolumeLevel(" + level + ")");
            return true;
        }
        if (sAppContext != null && !WifiHelper.isSim()) {
            ensureReady(sAppContext);
        }
        if (writeCarMediaVolume(level)) return true;
        return writeStreamMusicVolumeScaled(level);
    }

    private static Integer readCarMediaVolume() {
        if (sCarAudioManager == null) return null;
        int groupId = resolveMediaVolumeGroupId();
        if (groupId < 0) return null;
        Integer vol = invokeCarAudioInt("getGroupVolume", groupId);
        if (vol == null || vol < 0 || vol > MEDIA_VOLUME_MAX) return null;
        return vol;
    }

    private static boolean writeCarMediaVolume(int level) {
        if (sCarAudioManager == null) return false;
        int groupId = resolveMediaVolumeGroupId();
        if (groupId < 0) return false;
        try {
            Method set = sCarAudioManager.getClass()
                    .getMethod("setGroupVolume", int.class, int.class, int.class);
            set.invoke(sCarAudioManager, groupId, level, 0);
            MghaLog.i(TAG, "CarAudio media volume " + level + " group=" + groupId);
            return true;
        } catch (Throwable t) {
            MghaLog.w(TAG, "CarAudio setGroupVolume: " + t.getMessage());
            return false;
        }
    }

    private static int resolveMediaVolumeGroupId() {
        if (sMediaVolumeGroupId >= 0) return sMediaVolumeGroupId;
        if (sCarAudioManager == null) return -1;
        Integer byUsage = invokeCarAudioInt("getVolumeGroupIdForUsage", AUDIO_USAGE_MEDIA);
        if (byUsage != null && byUsage >= 0) {
            sMediaVolumeGroupId = byUsage;
            MghaLog.i(TAG, "media volume group (usage) = " + byUsage);
            return sMediaVolumeGroupId;
        }
        Integer count = invokeCarAudioInt("getVolumeGroupCount");
        if (count == null || count <= 0) return -1;
        for (int g = 0; g < count; g++) {
            Integer max = invokeCarAudioInt("getGroupMaxVolume", g);
            if (max != null && max == MEDIA_VOLUME_MAX) {
                sMediaVolumeGroupId = g;
                MghaLog.i(TAG, "media volume group (max=32) = " + g);
                return g;
            }
        }
        return -1;
    }

    private static Integer invokeCarAudioInt(String method, int... args) {
        if (sCarAudioManager == null) return null;
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) types[i] = int.class;
            Method m = sCarAudioManager.getClass().getMethod(method, types);
            Object result = m.invoke(sCarAudioManager, boxInts(args));
            if (result instanceof Integer) return (Integer) result;
        } catch (Throwable t) {
            MghaLog.w(TAG, "CarAudio " + method + ": " + t.getMessage());
        }
        return null;
    }

    private static Object[] boxInts(int... values) {
        Object[] boxed = new Object[values.length];
        for (int i = 0; i < values.length; i++) boxed[i] = values[i];
        return boxed;
    }

    private static int readStreamMusicVolumeScaled() {
        if (sAppContext == null) return -1;
        AudioManager am = (AudioManager) sAppContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return -1;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return -1;
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (max == MEDIA_VOLUME_MAX) return cur;
        return Math.round(cur * MEDIA_VOLUME_MAX / (float) max);
    }

    private static boolean writeStreamMusicVolumeScaled(int level) {
        if (sAppContext == null) return false;
        AudioManager am = (AudioManager) sAppContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return false;
        int target = max == MEDIA_VOLUME_MAX
                ? level
                : Math.round(level * max / (float) MEDIA_VOLUME_MAX);
        target = Math.max(0, Math.min(max, target));
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            MghaLog.i(TAG, "STREAM_MUSIC volume " + target + "/" + max + " (ui " + level + ")");
            return true;
        } catch (SecurityException e) {
            MghaLog.w(TAG, "STREAM_MUSIC yazılamadı: " + e.getMessage());
            return false;
        }
    }

    /** CPM okuma: 0=kapalı, 1=açık. */
    private static Boolean hvacOnFromCpm(int raw) {
        if (raw == 0) return false;
        if (raw == 1) return true;
        return null;
    }

    private static boolean setIntArea(int propId, int area, int value) {
        if (sCarPropertyManager == null) return false;
        try {
            Method setInt = sCarPropertyManager.getClass()
                    .getMethod("setIntProperty", int.class, int.class, int.class);
            setInt.invoke(sCarPropertyManager, propId, area, value);
            MghaLog.i(TAG, "setInt 0x" + Integer.toHexString(propId)
                    + " area=0x" + Integer.toHexString(area) + " val=" + value);
            return true;
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException
                    ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
            MghaLog.w(TAG, "setInt 0x" + Integer.toHexString(propId) + " "
                    + (c != null ? c.getClass().getSimpleName() + ": " + c.getMessage() : t.toString()));
            return false;
        }
    }

    private static boolean setFloatArea(int propId, int area, float value) {
        if (sCarPropertyManager == null) return false;
        try {
            Method setFloat = sCarPropertyManager.getClass()
                    .getMethod("setFloatProperty", int.class, int.class, float.class);
            setFloat.invoke(sCarPropertyManager, propId, area, value);
            MghaLog.i(TAG, "setFloat 0x" + Integer.toHexString(propId)
                    + " area=0x" + Integer.toHexString(area) + " val=" + value);
            return true;
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException
                    ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
            MghaLog.w(TAG, "setFloat 0x" + Integer.toHexString(propId) + " "
                    + (c != null ? c.getClass().getSimpleName() + ": " + c.getMessage() : t.toString()));
            return false;
        }
    }

    /** Ham 1..7 → %40..%100 */
    public static int chargeLimitStepToPercent(int step) {
        if (step < 1 || step > 7) return -1;
        return 40 + (step - 1) * 10;
    }

    /** %40..%100 (10'ar) → ham 1..7 */
    public static int chargeLimitPercentToStep(int percent) {
        if (percent < 40 || percent > 100 || percent % 10 != 0) return -1;
        return (percent - 40) / 10 + 1;
    }

    /** Hedef şarj sınırı — CPM {@code PROP_CHARGE_LIMIT_SOC}. */
    public static boolean setChargeLimitPercent(int percent) {
        int step = chargeLimitPercentToStep(percent);
        if (step < 0) return false;
        if (sAppContext != null && HaSettings.demoMode(sAppContext)) {
            MghaLog.i(TAG, "demo: setChargeLimitPercent(" + percent + ") step=" + step);
            return true;
        }
        if (sAppContext != null && !WifiHelper.isSim()) {
            ensureReady(sAppContext);
        }
        return setIntArea(PROP_CHARGE_LIMIT_SOC, AREA_GLOBAL, step);
    }

    /** Konum izni sonradan verilince servisten çağrılabilir. */
    public static synchronized void startGpsUpdates(Context context) {
        if (context == null) return;
        Context ctx = context.getApplicationContext();
        if (sAppContext == null) sAppContext = ctx;
        if (sGpsListening) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            MghaLog.w(TAG, "GPS: konum izni yok");
            return;
        }
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                MghaLog.w(TAG, "GPS: LocationManager yok");
                return;
            }
            sLocationManager = lm;
            // En güncel last-known'u bir kez al
            Location best = pickBestLastKnown(lm);
            if (best != null) {
                sCachedGps = best;
                MghaLog.i(TAG, "GPS lastKnown " + best.getProvider()
                        + " lat=" + best.getLatitude() + " lon=" + best.getLongitude());
            }
            java.util.List<String> providers = lm.getProviders(true);
            if (providers == null || providers.isEmpty()) {
                MghaLog.w(TAG, "GPS: açık provider yok");
                return;
            }
            Handler main = new Handler(Looper.getMainLooper());
            int started = 0;
            for (String provider : providers) {
                try {
                    // 15 sn / 10 m — HA aralığıyla uyumlu, pili zorlamaz
                    lm.requestLocationUpdates(provider, 15_000L, 10f, sGpsListener, main.getLooper());
                    started++;
                } catch (Throwable t) {
                    MghaLog.w(TAG, "GPS listen " + provider + ": " + t.getMessage());
                }
            }
            sGpsListening = started > 0;
            MghaLog.i(TAG, "GPS listening providers=" + started + "/" + providers.size());
        } catch (Throwable t) {
            MghaLog.w(TAG, "GPS start: " + t.getMessage());
        }
    }

    private static Location pickBestLastKnown(LocationManager lm) {
        Location best = null;
        try {
            java.util.List<String> providers = lm.getProviders(true);
            if (providers == null) return null;
            for (String provider : providers) {
                Location loc = lm.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime()) {
                    best = loc;
                }
            }
        } catch (SecurityException e) {
            MghaLog.w(TAG, "GPS lastKnown izin: " + e.getMessage());
        } catch (Throwable ignored) {}
        return best;
    }

    public static VehicleSnapshot read() {
        if (sAppContext != null && HaSettings.demoMode(sAppContext)) {
            return DemoData.create();
        }

        if (sAppContext != null && !WifiHelper.isSim()) {
            ensureReady(sAppContext);
        }

        VehicleSnapshot s = new VehicleSnapshot();
        s.capturedAtMs = System.currentTimeMillis();
        s.carConnected = sCarPropertyManager != null;

        s.socPercent = firstFloat(getFloat(PROP_SOC), bmsFloat(PROP_SOC));
        int limitStep = firstInt(getInt(PROP_CHARGE_LIMIT_SOC), bmsInt(PROP_CHARGE_LIMIT_SOC));
        if (limitStep >= 1 && limitStep <= 7) {
            s.chargeLimitPercent = chargeLimitStepToPercent(limitStep);
        }
        s.rangeKm = firstInt(getInt(PROP_RANGE), bmsInt(PROP_RANGE));
        s.odometerKm = getInt(PROP_TOTAL_MILEAGE);
        s.exteriorTempC = readOutsideTempC();
        s.hvacOn = hvacOnFromCpm(getIntArea(PROP_HVAC_POWER, AREA_HVAC));
        s.hvacTempC = readDriverTempC();
        s.hvacFanLevel = readHvacFanSpeed();
        s.mediaVolumeLevel = readMediaVolumeLevel();
        s.vehicleReady = readVehicleReady();
        if (sAppContext != null) {
            s.vehicleLastRunMs = HaSettings.vehicleLastRunMs(sAppContext);
        }

        s.tireKpaFl = getInt(PROP_TIRE_PRESSURE_FL);
        s.tireKpaFr = getInt(PROP_TIRE_PRESSURE_FR);
        s.tireKpaRl = getInt(PROP_TIRE_PRESSURE_RL);
        s.tireKpaRr = getInt(PROP_TIRE_PRESSURE_RR);

        s.chargeStatus = firstInt(getInt(PROP_CHG_STATUS), bmsInt(PROP_CHG_STATUS));
        if (s.chargeStatus == 1 || s.chargeStatus == 10) {
            int remain = firstInt(getInt(PROP_CHARGE_REMAIN_MIN), bmsInt(PROP_CHARGE_REMAIN_MIN));
            // 2046 sentinel (F418); F417 için de aynı geçersiz değeri ele
            if (remain >= 0 && remain != 2046) {
                s.chargeRemainingMin = remain;
            }
        }
        // Dort: batarya/AC değerleri BMS callback cache öncelikli
        s.batteryVoltageV = firstFloat(bmsFloat(PROP_BATT_VOLT), getFloat(PROP_BATT_VOLT));
        s.batteryCurrentA = firstFloat(bmsFloat(PROP_CHR_AMP_ACT), getFloat(PROP_CHR_AMP_ACT));
        s.stationDcCurrentA = firstFloat(bmsFloat(PROP_CHR_AMP_EXP), getFloat(PROP_CHR_AMP_EXP));
        s.acVoltageV = firstFloat(bmsFloat(PROP_AC_VOLT), getFloat(PROP_AC_VOLT));
        s.acCurrentA = firstFloat(bmsFloat(PROP_AC_AMP), getFloat(PROP_AC_AMP));
        if (!Float.isNaN(s.acVoltageV) && !Float.isNaN(s.acCurrentA)) {
            s.acChargingPowerKw = (s.acVoltageV * s.acCurrentA) / 1000f;
        }
        if (!Float.isNaN(s.batteryVoltageV) && !Float.isNaN(s.batteryCurrentA)) {
            s.dcChargingPowerKw = (s.batteryVoltageV * s.batteryCurrentA) / 1000f;
        }
        if (!Float.isNaN(s.batteryVoltageV) && !Float.isNaN(s.stationDcCurrentA)) {
            float stationKw = (s.batteryVoltageV * s.stationDcCurrentA) / 1000f;
            if (Math.abs(stationKw) <= 300f) {
                s.stationDcPowerKw = stationKw;
            }
        }
        float speedKmh = getFloat(PROP_SPEED);
        s.charging = isCharging(s.chargeStatus, s.acCurrentA, s.batteryCurrentA,
                s.batteryVoltageV, speedKmh);
        fillGps(s);

        MghaLog.i(TAG, "read cpm=" + (sCarPropertyManager != null)
                + " soc=" + s.socPercent
                + " limit=" + s.chargeLimitPercent
                + " range=" + s.rangeKm
                + " km=" + s.odometerKm
                + " chg=" + s.chargeStatus
                + " tires=" + s.tireKpaFl + "/" + s.tireKpaFr + "/" + s.tireKpaRl + "/" + s.tireKpaRr
                + " outC=" + s.exteriorTempC
                + " hvac=" + s.hvacOn
                + " hvacT=" + s.hvacTempC
                + " hvacFan=" + s.hvacFanLevel
                + " media=" + s.mediaVolumeLevel
                + " ready=" + s.vehicleReady
                + " gps=" + (Double.isNaN(s.latitude) ? "none" : (s.latitude + "," + s.longitude))
                + " bmsCache=" + sBmsCache.size());
        return s;
    }

    private static void fillGps(VehicleSnapshot s) {
        Context ctx = sAppContext;
        if (ctx == null) return;
        startGpsUpdates(ctx);
        try {
            Location loc = sCachedGps;
            if (sLocationManager != null) {
                Location last = pickBestLastKnown(sLocationManager);
                if (last != null && (loc == null || last.getTime() > loc.getTime())) {
                    loc = last;
                    sCachedGps = last;
                }
            } else {
                LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    Location last = pickBestLastKnown(lm);
                    if (last != null) {
                        loc = last;
                        sCachedGps = last;
                    }
                }
            }
            if (loc == null) return;
            s.latitude = loc.getLatitude();
            s.longitude = loc.getLongitude();
            if (loc.hasAccuracy()) s.gpsAccuracyM = loc.getAccuracy();
        } catch (SecurityException e) {
            MghaLog.w(TAG, "GPS izni yok: " + e.getMessage());
        } catch (Throwable t) {
            MghaLog.w(TAG, "GPS okunamadı: " + t.getMessage());
        }
    }

    private static boolean isCharging(int status, float acAmp, float dcAmp, float battVolt, float speedKmh) {
        if (status == 1 || status == 10) return true;
        if (!Float.isNaN(acAmp) && acAmp > 0.5f) return true;
        return !Float.isNaN(dcAmp) && !Float.isNaN(battVolt)
                && battVolt > 200f && dcAmp <= -1f
                && (Float.isNaN(speedKmh) || speedKmh < 1f);
    }

    // MG4 car APIs are only reachable via reflection on the system image.
    @android.annotation.SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi"})
    private static void bindCarService(Context context) {
        if (sCarBindAttempted) return;
        sCarBindAttempted = true;
        try {
            Class<?> carClass = Class.forName("android" + ".car.Car");
            MghaLog.i(TAG, "android.car.Car bulundu");

            Method createCarCtx = null;
            Method createCarHandler = null;
            Method createCarSc = null;
            try {
                createCarCtx = carClass.getMethod("createCar", Context.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                createCarHandler = carClass.getMethod("createCar", Context.class, Handler.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                createCarSc = carClass.getMethod("createCar", Context.class, ServiceConnection.class);
            } catch (NoSuchMethodException ignored) {}

            Object car = null;
            if (createCarCtx != null) {
                try {
                    car = createCarCtx.invoke(null, context);
                    if (car != null) MghaLog.i(TAG, "createCar(Context) OK");
                } catch (Exception e) {
                    MghaLog.w(TAG, "createCar(Context): " + e.getMessage());
                }
            }
            if (car == null && createCarHandler != null) {
                try {
                    car = createCarHandler.invoke(null, context, new Handler(Looper.getMainLooper()));
                    if (car != null) MghaLog.i(TAG, "createCar(Context,Handler) OK");
                } catch (Exception e) {
                    MghaLog.w(TAG, "createCar(Context,Handler): " + e.getMessage());
                }
            }
            if (car == null && createCarSc != null) {
                try {
                    ServiceConnection sc = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            MghaLog.i(TAG, "Car ServiceConnection bağlı: " + name);
                            tryGetManagers(carClass);
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            MghaLog.w(TAG, "Car bağlantısı kesildi");
                            sCarPropertyManager = null;
                        }
                    };
                    car = createCarSc.invoke(null, context, sc);
                    if (car != null) MghaLog.i(TAG, "createCar(Context,SC) — callback bekleniyor");
                } catch (Exception e) {
                    MghaLog.w(TAG, "createCar(Context,SC): " + e.getMessage());
                }
            }
            if (car == null) {
                Log.e(TAG, "Car.createCar başarısız — tüm yöntemler");
                sCarBindAttempted = false; // sonra tekrar dene
                return;
            }
            sCar = car;
            try {
                Method connect = carClass.getMethod("connect");
                connect.invoke(car);
                MghaLog.i(TAG, "car.connect() çağrıldı");
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                MghaLog.w(TAG, "car.connect: " + e.getMessage());
            }

            boolean connected = false;
            try {
                Method isConnected = carClass.getMethod("isConnected");
                connected = Boolean.TRUE.equals(isConnected.invoke(car));
                MghaLog.i(TAG, "isConnected=" + connected);
            } catch (Exception ignored) {}

            if (connected) {
                tryGetManagers(carClass);
            } else {
                Handler h = new Handler(Looper.getMainLooper());
                h.postDelayed(() -> tryGetManagers(carClass), 500);
                h.postDelayed(() -> tryGetManagers(carClass), 2500);
                h.postDelayed(() -> tryGetManagers(carClass), 6000);
                h.postDelayed(() -> tryGetManagers(carClass), 12000);
            }
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "android.car.Car yok");
        } catch (Exception e) {
            Log.e(TAG, "bindCarService: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            sCarBindAttempted = false;
        }
    }

    /** Servis tick öncesi: CPM yoksa bağlanmayı yeniden dene. */
    public static synchronized void ensureReady(Context context) {
        if (context == null) return;
        if (sAppContext == null) sAppContext = context.getApplicationContext();
        if (WifiHelper.isSim()) return;
        if (sCarPropertyManager != null && sCarAudioManager != null) return;
        if (!sCarBindAttempted) {
            bindCarService(sAppContext);
            bindSaicMapService(sAppContext);
            startGpsUpdates(sAppContext);
            return;
        }
        startGpsUpdates(sAppContext);
        if (sCar != null) {
            try {
                Class<?> carClass = Class.forName("android" + ".car.Car");
                tryGetManagers(carClass);
            } catch (Exception e) {
                MghaLog.w(TAG, "ensureReady: " + e.getMessage());
            }
        } else {
            sCarBindAttempted = false;
            bindCarService(sAppContext);
        }
    }

    private static void tryGetManagers(Class<?> carClass) {
        if (sCar == null) return;
        if (sCarPropertyManager != null && sCarAudioManager != null) return;
        try {
            try {
                Method isConnected = carClass.getMethod("isConnected");
                boolean connected = Boolean.TRUE.equals(isConnected.invoke(sCar));
                MghaLog.i(TAG, "tryGetManagers isConnected=" + connected);
                if (!connected) {
                    MghaLog.w(TAG, "Car henüz bağlı değil — manager bekleniyor");
                    return;
                }
            } catch (Exception ignored) {}

            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            if (sCarPropertyManager == null) {
                String propertyService = "property";
                try {
                    propertyService = (String) carClass.getField("PROPERTY_SERVICE").get(null);
                } catch (Exception ignored) {}
                Object cpm = getCarManager.invoke(sCar, propertyService);
                if (cpm != null) {
                    sCarPropertyManager = cpm;
                    MghaLog.i(TAG, "CarPropertyManager hazır: " + cpm.getClass().getName());
                } else {
                    Log.e(TAG, "CarPropertyManager null (izin yok?)");
                }
            }
            if (sCarAudioManager == null) {
                String audioService = "audio";
                try {
                    audioService = (String) carClass.getField("AUDIO_SERVICE").get(null);
                } catch (Exception ignored) {}
                Object cam = getCarManager.invoke(sCar, audioService);
                if (cam != null) {
                    sCarAudioManager = cam;
                    sMediaVolumeGroupId = -1;
                    MghaLog.i(TAG, "CarAudioManager hazır: " + cam.getClass().getName());
                } else {
                    MghaLog.w(TAG, "CarAudioManager null");
                }
            }
            if (sCarPropertyManager != null) {
                try {
                    Object bms = getCarManager.invoke(sCar, "bms");
                    if (bms != null) {
                        registerBmsCallback(bms);
                        MghaLog.i(TAG, "CarBMSManager hazır");
                    } else {
                        MghaLog.w(TAG, "CarBMSManager null");
                    }
                } catch (Exception e) {
                    MghaLog.w(TAG, "BMS manager yok: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "tryGetManagers: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void registerBmsCallback(Object bmsManager) {
        try {
            Method registerMethod = null;
            for (Method m : bmsManager.getClass().getMethods()) {
                String n = m.getName();
                if (n.contains("register") || n.contains("Register")) {
                    registerMethod = m;
                    break;
                }
            }
            if (registerMethod == null || registerMethod.getParameterTypes().length == 0) return;
            Class<?> callbackClass = registerMethod.getParameterTypes()[0];
            if (!callbackClass.isInterface()) return;
            Object proxy = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxyObj, method, args) -> {
                        cacheBmsArgs(args);
                        Class<?> ret = method.getReturnType();
                        if (ret == boolean.class) return false;
                        if (ret == int.class) return 0;
                        if (ret == long.class) return 0L;
                        if (ret == float.class) return 0f;
                        return null;
                    });
            registerMethod.invoke(bmsManager, proxy);
            MghaLog.i(TAG, "BMS callback kayıtlı");
        } catch (Throwable t) {
            MghaLog.w(TAG, "BMS callback: " + t.getMessage());
        }
    }

    private static void cacheBmsArgs(Object[] args) {
        if (args == null || args.length == 0) return;
        try {
            if (args.length >= 3 && args[0] instanceof Number && args[2] instanceof Number) {
                int propId = ((Number) args[0]).intValue();
                sBmsCache.put(propId, args[2]);
                return;
            }
            if (args.length == 1 && args[0] != null) {
                Object event = args[0];
                Method getPropId = findNoArg(event.getClass(), "getPropertyId", "getPropId");
                Method getVal = findNoArg(event.getClass(), "getValue", "getFloatValue");
                if (getPropId == null || getVal == null) return;
                Object pid = getPropId.invoke(event);
                Object val = getVal.invoke(event);
                if (pid instanceof Number && val instanceof Number) {
                    sBmsCache.put(((Number) pid).intValue(), val);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Method findNoArg(Class<?> clazz, String... names) {
        for (String n : names) {
            try {
                return clazz.getMethod(n);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private static void bindSaicMapService(Context context) {
        if (sMapBindAttempted) return;
        sMapBindAttempted = true;
        try {
            Intent intent = new Intent();
            intent.setClassName(SAIC_MAP_PACKAGE, SAIC_MAP_SERVICE_CLASS);
            boolean ok = context.bindService(intent, sMapConnection, Context.BIND_AUTO_CREATE);
            MghaLog.i(TAG, "MapService bind=" + ok);
        } catch (Throwable t) {
            MghaLog.w(TAG, "MapService bind hata: " + t.getMessage());
        }
    }

    /** Dış ortam sıcaklığı (°C). Map getSensorTemperature IMU sıcaklığıdır — kullanma. */
    private static int readOutsideTempC() {
        float v = getFloatArea(PROP_OUT_CAR_TEMP, AREA_HVAC);
        if (Float.isNaN(v) || v <= OUT_CAR_TEMP_INVALID + 1f || v < -50f || v > 80f) {
            MghaLog.w(TAG, "outCarTemp geçersiz: " + v);
            return -1;
        }
        return Math.round(v);
    }

    private static final ConcurrentHashMap<Integer, Boolean> sLoggedPropErr = new ConcurrentHashMap<>();

    private static int getIntArea(int propId, int area) {
        if (sCarPropertyManager == null) return -1;
        try {
            Method getProperty = sCarPropertyManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
            Object cpv = getProperty.invoke(sCarPropertyManager, Integer.class, propId, area);
            if (cpv == null) return -1;
            Object v = cpv.getClass().getMethod("getValue").invoke(cpv);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            logPropErrOnce(propId, t);
            return -1;
        }
    }

    private static int getInt(int propId) {
        return getIntArea(propId, AREA_GLOBAL);
    }

    private static float getFloat(int propId) {
        return getFloatArea(propId, AREA_GLOBAL);
    }

    private static float getFloatArea(int propId, int area) {
        if (sCarPropertyManager == null) return Float.NaN;
        try {
            Method getProperty = sCarPropertyManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
            Object cpv = getProperty.invoke(sCarPropertyManager, Float.class, propId, area);
            if (cpv == null) return Float.NaN;
            Object v = cpv.getClass().getMethod("getValue").invoke(cpv);
            return v instanceof Number ? ((Number) v).floatValue() : Float.NaN;
        } catch (Throwable t) {
            logPropErrOnce(propId, t);
            return Float.NaN;
        }
    }

    private static void logPropErrOnce(int propId, Throwable t) {
        if (sLoggedPropErr.putIfAbsent(propId, Boolean.TRUE) != null) return;
        Throwable c = t instanceof java.lang.reflect.InvocationTargetException
                ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
        MghaLog.w(TAG, "prop 0x" + Integer.toHexString(propId) + " "
                + (c != null ? c.getClass().getSimpleName() + ": " + c.getMessage() : String.valueOf(t)));
    }

    private static float bmsFloat(int propId) {
        Object val = sBmsCache.get(propId);
        return val instanceof Number ? ((Number) val).floatValue() : Float.NaN;
    }

    private static int bmsInt(int propId) {
        Object val = sBmsCache.get(propId);
        return val instanceof Number ? ((Number) val).intValue() : -1;
    }

    private static float firstFloat(float a, float b) {
        return !Float.isNaN(a) ? a : b;
    }

    private static int firstInt(int a, int b) {
        return a >= 0 ? a : b;
    }
}
