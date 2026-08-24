package com.drivehub.mgha.hardware;

import android.content.Context;

import com.drivehub.mgha.R;

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
    public float chargePowerKw = Float.NaN;
    public float acVoltageV = Float.NaN;
    public float acCurrentA = Float.NaN;
    public float batteryVoltageV = Float.NaN;
    public float batteryCurrentA = Float.NaN;

    public String formatForScreen(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (demo) {
            sb.append(ctx.getString(R.string.preview_demo_header));
        }
        sb.append(ctx.getString(R.string.preview_soc, fmt(ctx, socPercent, R.string.unit_percent)));
        sb.append(ctx.getString(R.string.preview_range, fmtInt(ctx, rangeKm, R.string.unit_km)));
        sb.append(ctx.getString(R.string.preview_odometer, fmtInt(ctx, odometerKm, R.string.unit_km)));
        sb.append(ctx.getString(R.string.preview_exterior, fmtInt(ctx, exteriorTempC, R.string.unit_celsius)));
        sb.append(ctx.getString(R.string.preview_charge,
                ctx.getString(charging ? R.string.preview_yes : R.string.preview_no),
                chargeLabel(ctx, chargeStatus)));
        sb.append(ctx.getString(R.string.preview_power, fmt(ctx, chargePowerKw, R.string.unit_kw)));
        sb.append(ctx.getString(R.string.preview_ac,
                fmt(ctx, acVoltageV, R.string.unit_v),
                fmt(ctx, acCurrentA, R.string.unit_a)));
        sb.append(ctx.getString(R.string.preview_batt,
                fmt(ctx, batteryVoltageV, R.string.unit_v),
                fmt(ctx, batteryCurrentA, R.string.unit_a)));
        sb.append(ctx.getString(R.string.preview_last_update, fmtTime(ctx, capturedAtMs)));
        sb.append('\n');
        sb.append(ctx.getString(R.string.preview_tire_fl, fmtBar(ctx, tireKpaFl)));
        sb.append(ctx.getString(R.string.preview_tire_fr, fmtBar(ctx, tireKpaFr)));
        sb.append(ctx.getString(R.string.preview_tire_rl, fmtBar(ctx, tireKpaRl)));
        sb.append(ctx.getString(R.string.preview_tire_rr, fmtBar(ctx, tireKpaRr)));
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

    private static String fmt(Context ctx, float v, int unitRes) {
        if (Float.isNaN(v)) return ctx.getString(R.string.preview_unknown);
        String unit = ctx.getString(unitRes);
        if (v == (long) v) return (long) v + unit;
        return String.format(java.util.Locale.US, "%.1f%s", v, unit);
    }

    private static String fmtInt(Context ctx, int v, int unitRes) {
        return v < 0
                ? ctx.getString(R.string.preview_unknown)
                : (v + ctx.getString(unitRes));
    }

    private static String fmtBar(Context ctx, int kpa) {
        if (kpa < 0) return ctx.getString(R.string.preview_unknown);
        return String.format(java.util.Locale.US, ctx.getString(R.string.unit_bar), kpa / 100f);
    }

    private String fmtGps(Context ctx) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return ctx.getString(R.string.preview_unknown);
        }
        String acc = Float.isNaN(gpsAccuracyM) ? "" :
                String.format(java.util.Locale.US, "  (±%.0f m)", gpsAccuracyM);
        return String.format(java.util.Locale.US, "%.5f, %.5f%s", latitude, longitude, acc);
    }

    private static String fmtTime(Context ctx, long ms) {
        if (ms <= 0) return ctx.getString(R.string.preview_unknown);
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(ms));
    }
}
