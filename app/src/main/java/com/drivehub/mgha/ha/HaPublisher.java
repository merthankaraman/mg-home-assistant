package com.drivehub.mgha.ha;

import android.content.Context;

import com.drivehub.mgha.R;
import com.drivehub.mgha.hardware.VehicleReader;
import com.drivehub.mgha.hardware.VehicleSnapshot;
import com.drivehub.mgha.prefs.HaSettings;
import com.drivehub.mgha.util.MghaLog;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Önce {@code mg4_bridge.push} servisini dener (HA’da cihaz + kalıcılık).
 * Servis yoksa eski REST {@code /api/states} + {@code group.&lt;prefix&gt;} yoluna düşer.
 * Araç herhangi bir WiFi’den public HA URL + token ile yazar; LAN şart değil.
 */
public final class HaPublisher {

    public static class PublishResult {
        public int ok;
        public int fail;
        public String lastError;
        /** true = mg4_bridge.push kullanıldı */
        public boolean viaBridge;
    }

    private HaPublisher() {}

    public static PublishResult publish(Context ctx, HomeAssistantClient client,
                                        String prefix, VehicleSnapshot snap) {
        return publish(ctx, client, prefix, snap, null);
    }

    public static PublishResult publish(Context ctx, HomeAssistantClient client,
                                        String prefix, VehicleSnapshot snap,
                                        String updateReason) {
        PublishResult out = new PublishResult();
        if (client == null || snap == null) {
            out.lastError = ctx.getString(R.string.msg_client_missing);
            out.fail = 1;
            return out;
        }
        String p = sanitize(prefix);

        HomeAssistantClient.Result push = client.callService(
                "mg4_bridge", "push", buildPush(ctx, snap, p, true, updateReason));
        if (push.ok) {
            out.ok = 1;
            out.viaBridge = true;
            HaCommandPoller.scheduleCommandsBaseline(snap.charging, snap.hvacOn);
            if (snap.chargeLimitPercent >= 40 && snap.chargeLimitPercent <= 100) {
                HaCommandPoller.noteChargeLimitFromCar(snap.chargeLimitPercent);
            }
            if (snap.hvacTempC >= 16 && snap.hvacTempC <= 30) {
                HaCommandPoller.noteHvacTempFromCar(snap.hvacTempC);
            }
            if (snap.hvacFanLevel >= VehicleReader.HVAC_FAN_MIN
                    && (snap.hvacFanLevel <= VehicleReader.HVAC_FAN_MAX_MANUAL
                    || snap.hvacFanLevel == VehicleReader.HVAC_FAN_AUTO)) {
                HaCommandPoller.noteHvacFanFromCar(snap.hvacFanLevel);
            }
            if (snap.mediaVolumeLevel >= 0 && snap.mediaVolumeLevel <= 32) {
                HaCommandPoller.noteMediaVolumeFromCar(snap.mediaVolumeLevel);
            }
            HaCommandPoller.noteIntervalsFromCar(
                    HaSettings.intervalNormalMin(ctx),
                    HaSettings.intervalChargingSec(ctx));
            return out;
        }
        // Yalnızca servis yoksa REST; ağ/SSL hatasında fallback yağmuru yapma
        if (isMissingBridgeService(push)) {
            MghaLog.i("MGHA_HA", "mg4_bridge.push yok, REST fallback: " + formatErr(ctx, push));
            return publishRest(ctx, client, p, snap, out, updateReason);
        }
        MghaLog.w("MGHA_HA", "push başarısız, REST atlandı (sonraki tick): " + formatErr(ctx, push));
        out.fail = 1;
        out.lastError = formatErr(ctx, push);
        return out;
    }

    /** 404 / unknown service → entegrasyon yok; geçici ağ hataları değil. */
    private static boolean isMissingBridgeService(HomeAssistantClient.Result r) {
        if (r == null) return false;
        if (r.httpCode == 404) return true;
        String blob = ((r.error != null ? r.error : "") + " " + (r.body != null ? r.body : "")).toLowerCase();
        if (blob.contains("connectexception")
                || blob.contains("unknownhost")
                || blob.contains("sslhandshake")
                || blob.contains("sockettimeout")
                || blob.contains("failed to connect")
                || blob.contains("unacceptable certificate")) {
            return false;
        }
        return blob.contains("not found")
                || blob.contains("does not exist")
                || blob.contains("unable to find service");
    }

    public static void markOffline(Context ctx, HomeAssistantClient client, String prefix) {
        String p = sanitize(prefix);
        try {
            JSONObject data = new JSONObject();
            data.put("prefix", p);
            data.put("online", false);
            HomeAssistantClient.Result r = client.callService("mg4_bridge", "push", data);
            if (r.ok) return;
        } catch (Exception ignored) {}

        JSONObject attr = new JSONObject();
        client.postState("switch." + p + "_charging", "off",
                binAttrs(ctx.getString(R.string.ha_name_charging)));
        client.postState("binary_sensor." + p + "_vehicle_ready", "off",
                binAttrs(ctx.getString(R.string.ha_name_vehicle_ready)));
        String[] sensors = {
                "sensor." + p + "_battery",
                "sensor." + p + "_range",
                "sensor." + p + "_mileage",
                "sensor." + p + "_exterior_temperature",
                "sensor." + p + "_tire_pressure_fl",
                "sensor." + p + "_tire_pressure_fr",
                "sensor." + p + "_tire_pressure_rl",
                "sensor." + p + "_tire_pressure_rr",
                "sensor." + p + "_charging_status",
                "sensor." + p + "_ac_voltage",
                "sensor." + p + "_ac_current",
                "sensor." + p + "_ac_charging_power",
                "sensor." + p + "_battery_voltage",
                "sensor." + p + "_battery_current",
                "sensor." + p + "_battery_charging_power",
                "sensor." + p + "_station_dc_current",
                "sensor." + p + "_station_dc_power",
                "sensor." + p + "_charge_remaining",
                "sensor." + p + "_vehicle_last_run",
                "sensor." + p + "_command_feedback",
                "sensor." + p + "_last_update"
        };
        for (String id : sensors) {
            client.postState(id, "unavailable", attr);
        }
    }

    private static JSONObject buildPush(Context ctx, VehicleSnapshot snap, String prefix,
                                        boolean online, String updateReason) {
        JSONObject o = new JSONObject();
        try {
            o.put("prefix", prefix);
            o.put("online", online);
            if (snap.demo) o.put("demo", true);
            o.put("last_update", isoUtc(snap.capturedAtMs));
            if (updateReason != null && !updateReason.isEmpty()) {
                o.put("update_reason", updateReason);
            }
            putNum(o, "battery", snap.socPercent);
            putInt(o, "charge_limit", snap.chargeLimitPercent);
            if (snap.hvacOn != null) {
                o.put("hvac", snap.hvacOn);
            }
            putInt(o, "hvac_temp", snap.hvacTempC);
            putInt(o, "hvac_fan", snap.hvacFanLevel);
            putInt(o, "media_volume", snap.mediaVolumeLevel);
            putInt(o, "range", snap.rangeKm);
            putInt(o, "mileage", snap.odometerKm);
            putInt(o, "exterior_temperature", snap.exteriorTempC);
            putInt(o, "tire_pressure_fl", snap.tireKpaFl);
            putInt(o, "tire_pressure_fr", snap.tireKpaFr);
            putInt(o, "tire_pressure_rl", snap.tireKpaRl);
            putInt(o, "tire_pressure_rr", snap.tireKpaRr);
            o.put("charging", snap.charging);
            o.put("charging_status", chargeState(snap.chargeStatus));
            o.put("vehicle_ready", snap.vehicleReady);
            if (snap.vehicleLastRunMs > 0) {
                o.put("vehicle_last_run", isoUtc(snap.vehicleLastRunMs));
            }
            if (ctx != null) {
                o.put("interval_normal", HaSettings.intervalNormalMin(ctx));
                o.put("interval_charging", HaSettings.intervalChargingSec(ctx));
                HaCommandPoller.CommandFeedback fb = HaCommandPoller.lastFeedback();
                if (fb != null && fb.atMs > 0) {
                    o.put("command_feedback", fb.status);
                    if (fb.command != null && !fb.command.isEmpty()) {
                        o.put("command_name", fb.command);
                    }
                    if (fb.detailKey != null && !fb.detailKey.isEmpty()) {
                        o.put("command_detail_key", fb.detailKey);
                    }
                    if (fb.detailArg != null && !fb.detailArg.isEmpty()) {
                        o.put("command_detail_arg", fb.detailArg);
                    }
                    o.put("command_at", isoUtc(fb.atMs));
                }
            }
            putNum(o, "battery_voltage", snap.batteryVoltageV);
            putNum(o, "battery_current", snap.batteryCurrentA);
            putNum(o, "battery_charging_power", snap.dcChargingPowerKw);
            putChargeInt(o, "charge_remaining", snap.chargeRemainingMin,
                    snap.chargeStatus == 1 || snap.chargeStatus == 10);
            putChargeNum(o, "station_dc_current", snap.stationDcCurrentA, snap.chargeStatus == 10);
            putChargeNum(o, "station_dc_power", snap.stationDcPowerKw, snap.chargeStatus == 10);
            putChargeNum(o, "ac_voltage", snap.acVoltageV, snap.chargeStatus == 1);
            putChargeNum(o, "ac_current", snap.acCurrentA, snap.chargeStatus == 1);
            putChargeNum(o, "ac_charging_power", snap.acChargingPowerKw, snap.chargeStatus == 1);
            if (!Double.isNaN(snap.latitude) && !Double.isNaN(snap.longitude)) {
                o.put("latitude", snap.latitude);
                o.put("longitude", snap.longitude);
                if (!Float.isNaN(snap.gpsAccuracyM)) o.put("gps_accuracy", snap.gpsAccuracyM);
            }
        } catch (Exception ignored) {}
        return o;
    }

    private static void putNum(JSONObject o, String key, float v) throws Exception {
        if (!Float.isNaN(v)) o.put(key, v);
    }

    /** Şarj modu aktif değilse 0; aktifse geçerli değer (NaN ise gönderme). */
    private static void putChargeNum(JSONObject o, String key, float v, boolean active)
            throws Exception {
        if (!active) {
            o.put(key, 0);
            return;
        }
        putNum(o, key, v);
    }

    private static void putInt(JSONObject o, String key, int v) throws Exception {
        if (v >= 0) o.put(key, v);
    }

    /** Şarj modu aktif değilse 0; aktifse geçerli değer (geçersizse gönderme). */
    private static void putChargeInt(JSONObject o, String key, int v, boolean active)
            throws Exception {
        if (!active) {
            o.put(key, 0);
            return;
        }
        putInt(o, key, v);
    }

    private static PublishResult publishRest(Context ctx, HomeAssistantClient client,
                                             String p, VehicleSnapshot snap, PublishResult out,
                                             String updateReason) {
        JSONArray members = new JSONArray();

        JSONObject lastUpdateAttrs = attrs(ctx.getString(R.string.ha_name_last_update), null,
                "timestamp", null, "mdi:clock-outline");
        putUpdateReason(lastUpdateAttrs, updateReason);
        postStr(client, out, members, "sensor." + p + "_last_update", isoUtc(snap.capturedAtMs),
                lastUpdateAttrs);
        if (!Double.isNaN(snap.latitude) && !Double.isNaN(snap.longitude)) {
            JSONObject a = new JSONObject();
            try {
                a.put("friendly_name", ctx.getString(R.string.ha_name_location));
                a.put("source_type", "gps");
                a.put("latitude", snap.latitude);
                a.put("longitude", snap.longitude);
                if (!Float.isNaN(snap.gpsAccuracyM)) a.put("gps_accuracy", snap.gpsAccuracyM);
                if (snap.demo) a.put("demo", true);
            } catch (Exception ignored) {}
            post(client, out, members, "device_tracker." + p, "not_home", a);
        }
        postInt(client, out, members, "sensor." + p + "_mileage", snap.odometerKm,
                attrs(ctx.getString(R.string.ha_name_mileage), "km", "distance", "total_increasing", "mdi:counter"));
        postNum(client, out, members, "sensor." + p + "_battery", snap.socPercent,
                attrs(ctx.getString(R.string.ha_name_battery), "%", "battery", "measurement", "mdi:battery"));
        postInt(client, out, members, "sensor." + p + "_range", snap.rangeKm,
                attrs(ctx.getString(R.string.ha_name_range), "km", "distance", "measurement", "mdi:map-marker-distance"));
        postInt(client, out, members, "sensor." + p + "_exterior_temperature", snap.exteriorTempC,
                attrs(ctx.getString(R.string.ha_name_exterior), "°C", "temperature", "measurement", "mdi:thermometer"));
        postInt(client, out, members, "sensor." + p + "_tire_pressure_fl", snap.tireKpaFl,
                attrs(ctx.getString(R.string.ha_name_tire_fl), "kPa", "pressure", "measurement", "mdi:car-tire-alert"));
        postInt(client, out, members, "sensor." + p + "_tire_pressure_fr", snap.tireKpaFr,
                attrs(ctx.getString(R.string.ha_name_tire_fr), "kPa", "pressure", "measurement", "mdi:car-tire-alert"));
        postInt(client, out, members, "sensor." + p + "_tire_pressure_rl", snap.tireKpaRl,
                attrs(ctx.getString(R.string.ha_name_tire_rl), "kPa", "pressure", "measurement", "mdi:car-tire-alert"));
        postInt(client, out, members, "sensor." + p + "_tire_pressure_rr", snap.tireKpaRr,
                attrs(ctx.getString(R.string.ha_name_tire_rr), "kPa", "pressure", "measurement", "mdi:car-tire-alert"));
        postStr(client, out, members, "switch." + p + "_charging",
                snap.charging ? "on" : "off",
                binAttrs(ctx.getString(R.string.ha_name_charging)));
        postStr(client, out, members, "binary_sensor." + p + "_vehicle_ready",
                snap.vehicleReady ? "on" : "off",
                binAttrs(ctx.getString(R.string.ha_name_vehicle_ready)));
        if (snap.vehicleLastRunMs > 0) {
            postStr(client, out, members, "sensor." + p + "_vehicle_last_run",
                    isoUtc(snap.vehicleLastRunMs),
                    attrs(ctx.getString(R.string.ha_name_vehicle_last_run), null, "timestamp", null, "mdi:clock-outline"));
        }
        postStr(client, out, members, "sensor." + p + "_charging_status", chargeState(snap.chargeStatus),
                attrs(ctx.getString(R.string.ha_name_charging_status), null, null, null, "mdi:ev-station"));
        postChargeInt(client, out, members, "sensor." + p + "_charge_remaining", snap.chargeRemainingMin,
                snap.chargeStatus == 1 || snap.chargeStatus == 10,
                attrs(ctx.getString(R.string.ha_name_charge_remaining), "min", "duration", "measurement", "mdi:timer-sand"));
        postNum(client, out, members, "sensor." + p + "_battery_voltage", snap.batteryVoltageV,
                attrs(ctx.getString(R.string.ha_name_battery_voltage), "V", "voltage", "measurement", "mdi:car-battery"));
        postNum(client, out, members, "sensor." + p + "_battery_current", snap.batteryCurrentA,
                attrs(ctx.getString(R.string.ha_name_battery_current), "A", "current", "measurement", "mdi:current-dc"));
        postNum(client, out, members, "sensor." + p + "_battery_charging_power", snap.dcChargingPowerKw,
                attrs(ctx.getString(R.string.ha_name_battery_charging_power), "kW", "power", "measurement", "mdi:ev-station"));
        postChargeNum(client, out, members, "sensor." + p + "_station_dc_current", snap.stationDcCurrentA,
                snap.chargeStatus == 10,
                attrs(ctx.getString(R.string.ha_name_station_dc_current), "A", "current", "measurement", "mdi:current-dc"));
        postChargeNum(client, out, members, "sensor." + p + "_station_dc_power", snap.stationDcPowerKw,
                snap.chargeStatus == 10,
                attrs(ctx.getString(R.string.ha_name_station_dc_power), "kW", "power", "measurement", "mdi:ev-station"));
        postChargeNum(client, out, members, "sensor." + p + "_ac_voltage", snap.acVoltageV,
                snap.chargeStatus == 1,
                attrs(ctx.getString(R.string.ha_name_ac_voltage), "V", "voltage", "measurement", "mdi:flash"));
        postChargeNum(client, out, members, "sensor." + p + "_ac_current", snap.acCurrentA,
                snap.chargeStatus == 1,
                attrs(ctx.getString(R.string.ha_name_ac_current), "A", "current", "measurement", "mdi:current-ac"));
        postChargeNum(client, out, members, "sensor." + p + "_ac_charging_power", snap.acChargingPowerKw,
                snap.chargeStatus == 1,
                attrs(ctx.getString(R.string.ha_name_ac_charging_power), "kW", "power", "measurement", "mdi:flash"));
        if (members.length() > 0) {
            ensureGroup(ctx, client, out, p, members);
        }
        if (out.ok > 0 && snap.chargeLimitPercent >= 40 && snap.chargeLimitPercent <= 100) {
            HaCommandPoller.noteChargeLimitFromCar(snap.chargeLimitPercent);
        }
        if (out.ok > 0 && snap.hvacOn != null) {
            HaCommandPoller.noteHvacFromCar(snap.hvacOn);
        }
        if (out.ok > 0 && snap.hvacTempC >= 16 && snap.hvacTempC <= 30) {
            HaCommandPoller.noteHvacTempFromCar(snap.hvacTempC);
        }
        if (out.ok > 0 && snap.hvacFanLevel >= VehicleReader.HVAC_FAN_MIN
                && (snap.hvacFanLevel <= VehicleReader.HVAC_FAN_MAX_MANUAL
                || snap.hvacFanLevel == VehicleReader.HVAC_FAN_AUTO)) {
            HaCommandPoller.noteHvacFanFromCar(snap.hvacFanLevel);
        }
        if (out.ok > 0 && snap.mediaVolumeLevel >= 0 && snap.mediaVolumeLevel <= 32) {
            HaCommandPoller.noteMediaVolumeFromCar(snap.mediaVolumeLevel);
        }
        if (out.ok > 0) {
            HaCommandPoller.scheduleCommandsBaseline(snap.charging, snap.hvacOn);
            HaCommandPoller.noteIntervalsFromCar(
                    HaSettings.intervalNormalMin(ctx),
                    HaSettings.intervalChargingSec(ctx));
        }
        return out;
    }

    private static void ensureGroup(Context ctx, HomeAssistantClient client, PublishResult out,
                                    String objectId, JSONArray members) {
        JSONObject attrs = new JSONObject();
        try {
            attrs.put("friendly_name", objectId);
            attrs.put("icon", "mdi:car-electric");
            attrs.put("entity_id", members);
            attrs.put("order", 0);
        } catch (Exception ignored) {}

        HomeAssistantClient.Result st = client.postState("group." + objectId, "unknown", attrs);
        if (st.ok) {
            out.ok++;
            return;
        }

        try {
            JSONObject g = new JSONObject();
            g.put("object_id", objectId);
            g.put("name", objectId);
            g.put("icon", "mdi:car-electric");
            g.put("entities", members);
            HomeAssistantClient.Result gr = client.callService("group", "set", g);
            if (gr.ok) {
                out.ok++;
                return;
            }
            out.fail++;
            out.lastError = ctx.getString(R.string.msg_group_fail, formatErr(ctx, gr));
            android.util.Log.e("MGHA_HA", out.lastError);
        } catch (Exception e) {
            out.fail++;
            out.lastError = ctx.getString(R.string.msg_group_fail,
                    "states=" + formatErr(ctx, st) + " set=" + e.getMessage());
            android.util.Log.e("MGHA_HA", out.lastError);
        }
    }

    private static String formatErr(Context ctx, HomeAssistantClient.Result r) {
        if (r == null) return ctx.getString(R.string.preview_unknown);
        StringBuilder sb = new StringBuilder();
        if (r.error != null) sb.append(r.error);
        if (r.httpCode > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('(').append(r.httpCode).append(')');
        }
        if (r.body != null && !r.body.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            String b = r.body;
            if (b.length() > 200) b = b.substring(0, 200);
            sb.append(b);
        }
        return sb.length() == 0 ? ctx.getString(R.string.msg_unknown_error) : sb.toString();
    }

    private static String sanitize(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) return "mg4";
        return prefix.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    private static void postNum(HomeAssistantClient c, PublishResult out, JSONArray members,
                                String id, float v, JSONObject attrs) {
        if (Float.isNaN(v)) return;
        post(c, out, members, id, trimNum(v), attrs);
    }

    private static void postChargeNum(HomeAssistantClient c, PublishResult out, JSONArray members,
                                      String id, float v, boolean active, JSONObject attrs) {
        if (!active) {
            post(c, out, members, id, "0", attrs);
            return;
        }
        postNum(c, out, members, id, v, attrs);
    }

    private static void postInt(HomeAssistantClient c, PublishResult out, JSONArray members,
                                String id, int v, JSONObject attrs) {
        if (v < 0) return;
        post(c, out, members, id, String.valueOf(v), attrs);
    }

    private static void postChargeInt(HomeAssistantClient c, PublishResult out, JSONArray members,
                                      String id, int v, boolean active, JSONObject attrs) {
        if (!active) {
            post(c, out, members, id, "0", attrs);
            return;
        }
        postInt(c, out, members, id, v, attrs);
    }

    private static void postStr(HomeAssistantClient c, PublishResult out, JSONArray members,
                                String id, String state, JSONObject attrs) {
        if (state == null) return;
        post(c, out, members, id, state, attrs);
    }

    private static void post(HomeAssistantClient c, PublishResult out, JSONArray members,
                             String id, String state, JSONObject attrs) {
        HomeAssistantClient.Result r = c.postState(id, state, attrs);
        if (r.ok) {
            out.ok++;
            members.put(id);
        } else {
            out.fail++;
            out.lastError = r.error != null ? r.error : r.body;
            android.util.Log.e("MGHA_HA", "post fail " + id + ": " + out.lastError);
        }
    }

    private static String trimNum(float v) {
        if (v == (long) v) return Long.toString((long) v);
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String isoUtc(long ms) {
        if (ms <= 0) ms = System.currentTimeMillis();
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getDefault());
        return sdf.format(new java.util.Date(ms));
    }

    private static void putUpdateReason(JSONObject attrs, String updateReason) {
        if (attrs == null || updateReason == null || updateReason.isEmpty()) return;
        try {
            attrs.put("update_reason", updateReason);
        } catch (Exception ignored) {}
    }

    private static JSONObject attrs(String name, String unit, String deviceClass,
                                    String stateClass, String icon) {
        JSONObject a = new JSONObject();
        try {
            a.put("friendly_name", name);
            if (unit != null) a.put("unit_of_measurement", unit);
            if (deviceClass != null) a.put("device_class", deviceClass);
            if (stateClass != null) a.put("state_class", stateClass);
            if (icon != null) a.put("icon", icon);
        } catch (Exception ignored) {}
        return a;
    }

    private static JSONObject binAttrs(String name) {
        JSONObject a = new JSONObject();
        try {
            a.put("friendly_name", name);
            a.put("device_class", "battery_charging");
            a.put("icon", "mdi:battery-charging");
        } catch (Exception ignored) {}
        return a;
    }

    /** HA state kodları (makine kimliği; çeviriye girmez). */
    public static String chargeState(int st) {
        switch (st) {
            case 0: return "unplugged";
            case 1: return "AC";
            case 5: return "connecting";
            case 7: return "plugged_in";
            case 8: return "stopped";
            case 10: return "DC";
            default: return st < 0 ? "unknown" : ("code_" + st);
        }
    }
}
