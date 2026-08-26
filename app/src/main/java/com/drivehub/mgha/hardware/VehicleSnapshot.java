package com.drivehub.mgha.hardware;

import android.content.Context;

import com.drivehub.mgha.R;

import java.util.Locale;

/**
 * Ekranda ve Home Assistant'ta kullanılan sade araç anlığı.
 */
public class VehicleSnapshot {
    public long capturedAtMs;
    public boolean demo;
    public boolean carConnected;

    public float socPercent = Float.NaN;
    public int rangeKm = -1;
    public int odometerKm = -1;
    public int exteriorTempC = -1;

    public int tireKpaFl = -1;
    public int tireKpaFr = -1;
    public int tireKpaRl = -1;
    public int tireKpaRr = -1;

    public double latitude = Double.NaN;
    public double longitude = Double.NaN;
    public float gpsAccuracyM = Float.NaN;

    public boolean charging;
    public int chargeStatus = -1;
    /** Kalan şarj süresi (dakika); yalnız AC/DC şarjda dolu. */
    public int chargeRemainingMin = -1;
    public float acVoltageV = Float.NaN;
    public float acCurrentA = Float.NaN;
    public float acChargingPowerKw = Float.NaN;
    public float batteryVoltageV = Float.NaN;
    public float batteryCurrentA = Float.NaN;
    public float dcChargingPowerKw = Float.NaN;
    /** DC istasyon beklenen akım (PROP_CHR_AMP_EXP). */
    public float stationDcCurrentA = Float.NaN;
    /** DC istasyon teklif gücü: batarya V × beklenen A. */
    public float stationDcPowerKw = Float.NaN;

    public String formatForScreen(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (demo) {
            sb.append(ctx.getString(R.string.preview_demo_header));
        }
        sb.append(ctx.getString(R.string.preview_last_update, fmtTime(ctx, capturedAtMs)));
        sb.append(ctx.getString(R.string.preview_soc, fmt(ctx, socPercent, R.string.fmt_percent)));
        sb.append(ctx.getString(R.string.preview_range, fmtInt(ctx, rangeKm, R.string.fmt_km)));
        sb.append(ctx.getString(R.string.preview_odometer, fmtInt(ctx, odometerKm, R.string.fmt_km)));
        sb.append(ctx.getString(R.string.preview_exterior, fmtInt(ctx, exteriorTempC, R.string.fmt_celsius)));
        sb.append('\n');
        sb.append(ctx.getString(R.string.preview_charge, chargeLabel(ctx, chargeStatus)));
        if (chargeStatus == 1 || chargeStatus == 10) {
            sb.append(ctx.getString(R.string.preview_charge_remaining,
                    fmtInt(ctx, chargeRemainingMin, R.string.fmt_min)));
        }
        if (chargeStatus == 1) {
            sb.append(ctx.getString(R.string.preview_ac,
                    fmt(ctx, acVoltageV, R.string.fmt_v),
                    fmt(ctx, acCurrentA, R.string.fmt_a)));
            sb.append(ctx.getString(R.string.preview_ac_power, fmt(ctx, acChargingPowerKw, R.string.fmt_kw)));
        }
        sb.append(ctx.getString(R.string.preview_batt,
                fmt(ctx, batteryVoltageV, R.string.fmt_v),
                fmt(ctx, batteryCurrentA, R.string.fmt_a)));
        sb.append(ctx.getString(R.string.preview_dc_power, fmt(ctx, dcChargingPowerKw, R.string.fmt_kw)));
        if (chargeStatus == 10) {
            sb.append(ctx.getString(R.string.preview_station_current,
                    fmt(ctx, stationDcCurrentA, R.string.fmt_a)));
            sb.append(ctx.getString(R.string.preview_station_power,
                    fmt(ctx, stationDcPowerKw, R.string.fmt_kw)));
        }
        sb.append('\n');
        sb.append(ctx.getString(R.string.preview_tire_fl, fmtInt(ctx, tireKpaFl, R.string.fmt_kpa)));
        sb.append(ctx.getString(R.string.preview_tire_fr, fmtInt(ctx, tireKpaFr, R.string.fmt_kpa)));
        sb.append(ctx.getString(R.string.preview_tire_rl, fmtInt(ctx, tireKpaRl, R.string.fmt_kpa)));
        sb.append(ctx.getString(R.string.preview_tire_rr, fmtInt(ctx, tireKpaRr, R.string.fmt_kpa)));
        sb.append('\n');
        sb.append(ctx.getString(R.string.preview_location, fmtGps(ctx)));
        return sb.toString();
    }

    public static String chargeLabel(Context ctx, int st) {
        switch (st) {
            case 0: return ctx.getString(R.string.charge_unplugged);
            case 1: return ctx.getString(R.string.charge_ac);
            case 5: return ctx.getString(R.string.charge_connecting);
            case 7: return ctx.getString(R.string.charge_plugged);
            case 8: return ctx.getString(R.string.charge_stopped);
            case 10: return ctx.getString(R.string.charge_dc);
            default:
                return st < 0
                        ? ctx.getString(R.string.preview_unknown)
                        : ctx.getString(R.string.charge_code, st);
        }
    }

    /** Sayıyı string yapıp birim kalıbına verir; sıra strings.xml’de. */
    private static String fmt(Context ctx, float v, int formatRes) {
        if (Float.isNaN(v)) return ctx.getString(R.string.preview_unknown);
        String num = (v == (long) v)
                ? Long.toString((long) v)
                : String.format(Locale.US, "%.1f", v);
        return ctx.getString(formatRes, num);
    }

    private static String fmtInt(Context ctx, int v, int formatRes) {
        if (v < 0) return ctx.getString(R.string.preview_unknown);
        return ctx.getString(formatRes, String.valueOf(v));
    }

    private String fmtGps(Context ctx) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return ctx.getString(R.string.preview_unknown);
        }
        if (Float.isNaN(gpsAccuracyM)) {
            return ctx.getString(R.string.fmt_gps, latitude, longitude);
        }
        return ctx.getString(R.string.fmt_gps_acc, latitude, longitude, gpsAccuracyM);
    }

    private static String fmtTime(Context ctx, long ms) {
        if (ms <= 0) return ctx.getString(R.string.preview_unknown);
        return new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date(ms));
    }
}
