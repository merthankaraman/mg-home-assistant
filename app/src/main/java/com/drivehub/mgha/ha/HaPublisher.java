package com.drivehub.mgha.ha;

import android.content.Context;

import com.drivehub.mgha.R;
import com.drivehub.mgha.hardware.VehicleSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * REST {@code /api/states} ile varlık basar, ardından {@code group.&lt;prefix&gt;}
 * state yazar (üyeler {@code entity_id} attribute’unda). HA’ya eklenti / group YAML gerekmez.
 */
public final class HaPublisher {

    public static class PublishResult {
        public int ok;
        public int fail;
        public String lastError;
    }

    private HaPublisher() {}

    public static PublishResult publish(Context ctx, HomeAssistantClient client,
                                        String prefix, VehicleSnapshot snap) {
        PublishResult out = new PublishResult();
        if (client == null || snap == null) {
            out.lastError = ctx.getString(R.string.msg_client_missing);
            out.fail = 1;
            return out;
        }
        String p = sanitize(prefix);
        JSONArray members = new JSONArray();

        postStr(client, out, members, "sensor." + p + "_last_update", isoUtc(snap.capturedAtMs),
                attrs(ctx.getString(R.string.ha_name_last_update), null, "timestamp", null, "mdi:clock-outline"));
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
        postStr(client, out, members, "sensor." + p + "_charging_status", chargeState(snap.chargeStatus),
                attrs(ctx.getString(R.string.ha_name_charging_status), null, null, null, "mdi:ev-station"));
        postNum(client, out, members, "sensor." + p + "_ac_voltage", snap.acVoltageV,
                attrs(ctx.getString(R.string.ha_name_ac_voltage), "V", "voltage", "measurement", "mdi:flash"));
        postNum(client, out, members, "sensor." + p + "_ac_current", snap.acCurrentA,
                attrs(ctx.getString(R.string.ha_name_ac_current), "A", "current", "measurement", "mdi:current-ac"));
        postNum(client, out, members, "sensor." + p + "_battery_voltage", snap.batteryVoltageV,
                attrs(ctx.getString(R.string.ha_name_battery_voltage), "V", "voltage", "measurement", "mdi:car-battery"));
        postNum(client, out, members, "sensor." + p + "_battery_current", snap.batteryCurrentA,
                attrs(ctx.getString(R.string.ha_name_battery_current), "A", "current", "measurement", "mdi:current-dc"));
        postNum(client, out, members, "sensor." + p + "_charging_power", snap.chargePowerKw,
                attrs(ctx.getString(R.string.ha_name_charging_power), "kW", "power", "measurement", "mdi:flash"));
        postStr(client, out, members, "binary_sensor." + p + "_charging",
                snap.charging ? "on" : "off",
                binAttrs(ctx.getString(R.string.ha_name_charging)));

        if (members.length() > 0) {
            ensureGroup(ctx, client, out, p, members);
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

    public static void markOffline(Context ctx, HomeAssistantClient client, String prefix) {
        String p = sanitize(prefix);
        JSONObject attr = new JSONObject();
        client.postState("binary_sensor." + p + "_charging", "off",
                binAttrs(ctx.getString(R.string.ha_name_charging)));
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
                "sensor." + p + "_battery_voltage",
                "sensor." + p + "_battery_current",
                "sensor." + p + "_charging_power",
                "sensor." + p + "_last_update"
        };
        for (String id : sensors) {
            client.postState(id, "unavailable", attr);
        }
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

    private static void postInt(HomeAssistantClient c, PublishResult out, JSONArray members,
                                String id, int v, JSONObject attrs) {
        if (v < 0) return;
        post(c, out, members, id, String.valueOf(v), attrs);
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
