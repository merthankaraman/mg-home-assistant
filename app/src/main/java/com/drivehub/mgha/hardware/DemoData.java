package com.drivehub.mgha.hardware;

/**
 * Emülatör / Sim varyantı için sahte MG4 verisi.
 * Değerler birkaç saniyede bir hafif değişir ki HA'da güncelleme görülsün.
 */
public final class DemoData {
    private DemoData() {}

    public static VehicleSnapshot create() {
        long step = System.currentTimeMillis() / 15000L;
        int wave = (int) (step % 8);
        boolean dc = (step % 2) == 0;

        VehicleSnapshot s = new VehicleSnapshot();
        s.capturedAtMs = System.currentTimeMillis();
        s.demo = true;
        s.carConnected = true;
        s.socPercent = 64.0f + wave * 0.8f;
        s.chargeLimitPercent = 80;
        s.rangeKm = 236 + wave * 2;
        s.odometerKm = 18432;
        s.exteriorTempC = 16 + (wave % 4);
        s.hvacOn = (wave % 3) != 0;
        s.hvacTempC = 20 + (wave % 6);
        s.hvacFanLevel = (wave % 9) == 0 ? VehicleReader.HVAC_FAN_AUTO : 3 + (wave % 8);
        s.mediaVolumeLevel = 8 + wave * 3;
        s.vehicleReady = (wave % 4) == 0;
        if (s.vehicleReady) {
            s.vehicleLastRunMs = System.currentTimeMillis() - wave * 60_000L;
        }
        s.tireKpaFl = 238 + wave;
        s.tireKpaFr = 241;
        s.tireKpaRl = 235 + wave / 2;
        s.tireKpaRr = 237;
        s.charging = true;
        s.batteryVoltageV = 384f + wave * 0.5f;
        if (dc) {
            s.chargeStatus = 10;
            s.batteryCurrentA = -115f - wave * 0.8f;
            s.stationDcCurrentA = 125f + wave;
            s.stationDcPowerKw = (s.batteryVoltageV * s.stationDcCurrentA) / 1000f;
            s.dcChargingPowerKw = (s.batteryVoltageV * s.batteryCurrentA) / 1000f;
            s.chargeRemainingMin = 45 - wave * 2;
        } else {
            s.chargeStatus = 1;
            s.acVoltageV = 230f + wave * 0.4f;
            s.acCurrentA = 27.0f + wave * 0.3f;
            s.batteryCurrentA = -18.5f - wave * 0.2f;
            s.acChargingPowerKw = (s.acVoltageV * s.acCurrentA) / 1000f;
            s.dcChargingPowerKw = (s.batteryVoltageV * s.batteryCurrentA) / 1000f;
            s.chargeRemainingMin = 820 - wave * 5;
        }
        // İstanbul civarı, hafif kayma
        s.latitude = 41.0082 + wave * 0.00025;
        s.longitude = 28.9784 + wave * 0.00018;
        s.gpsAccuracyM = 8f;
        return s;
    }
}
