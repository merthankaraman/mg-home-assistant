package com.drivehub.mgha.ha;

import android.content.Context;

import com.drivehub.mgha.R;

/** mg4_bridge.push → update_reason (HA tarafında çevrilir). */
public final class UpdateReason {
    public static final String PERIODIC = "periodic";
    public static final String STARTUP = "startup";
    public static final String WIFI = "wifi";
    public static final String VEHICLE_READY = "vehicle_ready";
    public static final String CAR_CHANGED = "car_changed";
    public static final String HA_COMMAND = "ha_command";
    public static final String MANUAL = "manual";
    public static final String RETRY = "retry";

    private UpdateReason() {}

    public static String label(Context ctx, String key) {
        if (ctx == null || key == null || key.isEmpty()) return "";
        switch (key) {
            case PERIODIC:
                return ctx.getString(R.string.update_reason_periodic);
            case STARTUP:
                return ctx.getString(R.string.update_reason_startup);
            case WIFI:
                return ctx.getString(R.string.update_reason_wifi);
            case VEHICLE_READY:
                return ctx.getString(R.string.update_reason_vehicle_ready);
            case CAR_CHANGED:
                return ctx.getString(R.string.update_reason_car_changed);
            case HA_COMMAND:
                return ctx.getString(R.string.update_reason_ha_command);
            case MANUAL:
                return ctx.getString(R.string.update_reason_manual);
            case RETRY:
                return ctx.getString(R.string.update_reason_retry);
            default:
                return key;
        }
    }
}
