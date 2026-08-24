package com.drivehub.mgha.hardware;

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

    public String formatForScreen() {
        StringBuilder sb = new StringBuilder();
        if (demo) {
            sb.append("DEMO MODU — sanal veri\n\n");
        }
        sb.append("SOC: ").append(fmt(socPercent, "%")).append('\n');
        sb.append("Menzil: ").append(fmtInt(rangeKm, " km")).append('\n');
        sb.append("Odometre: ").append(fmtInt(odometerKm, " km")).append('\n');
        sb.append("Dış sıcaklık: ").append(fmtInt(exteriorTempC, " °C")).append('\n');
        sb.append("Şarj: ").append(charging ? "evet" : "hayır");
        sb.append("  (").append(chargeLabel(chargeStatus)).append(")\n");
        sb.append("Şarj gücü: ").append(fmt(chargePowerKw, " kW")).append('\n');
        sb.append("AC: ").append(fmt(acVoltageV, " V")).append(" / ")
                .append(fmt(acCurrentA, " A")).append('\n');
        sb.append("Batarya: ").append(fmt(batteryVoltageV, " V")).append(" / ")
                .append(fmt(batteryCurrentA, " A")).append('\n');
        sb.append("Son güncelleme: ").append(fmtTime(capturedAtMs)).append('\n');
        sb.append('\n');
        sb.append("Lastik FL: ").append(fmtBar(tireKpaFl)).append('\n');
        sb.append("Lastik FR: ").append(fmtBar(tireKpaFr)).append('\n');
        sb.append("Lastik RL: ").append(fmtBar(tireKpaRl)).append('\n');
        sb.append("Lastik RR: ").append(fmtBar(tireKpaRr)).append('\n');
        sb.append('\n');
        sb.append("Konum: ").append(fmtGps());
        return sb.toString();
    }

    public static String chargeLabel(int st) {
        switch (st) {
            case 0: return "prizde değil";
            case 1: return "AC şarj";
            case 5: return "bağlanıyor";
            case 7: return "prizde, şarj yok";
            case 8: return "durdu";
            case 10: return "DC şarj";
            default: return st < 0 ? "?" : ("kod " + st);
        }
    }

    private static String fmt(float v, String unit) {
        if (Float.isNaN(v)) return "?";
        if (v == (long) v) return (long) v + unit;
        return String.format(java.util.Locale.US, "%.1f%s", v, unit);
    }

    private static String fmtInt(int v, String unit) {
        return v < 0 ? "?" : (v + unit);
    }

    private static String fmtBar(int kpa) {
        if (kpa < 0) return "?";
        return String.format(java.util.Locale.US, "%.2f bar", kpa / 100f);
    }

    private String fmtGps() {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) return "?";
        String acc = Float.isNaN(gpsAccuracyM) ? "" :
                String.format(java.util.Locale.US, "  (±%.0f m)", gpsAccuracyM);
        return String.format(java.util.Locale.US, "%.5f, %.5f%s", latitude, longitude, acc);
    }

    private static String fmtTime(long ms) {
        if (ms <= 0) return "?";
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(ms));
    }
}
