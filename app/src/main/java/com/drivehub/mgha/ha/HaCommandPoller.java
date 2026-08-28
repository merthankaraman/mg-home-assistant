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

    private HaCommandPoller() {}

    /** {@code switch.{prefix}_hvac} durumuna göre klimayı aç/kapat. */
    public static void poll(Context ctx) {
        if (!HaSettings.pollEnabled(ctx)) return;
        if (!HaSettings.isConfigured(ctx)) return;
        if (!WifiHelper.canSend(ctx, HaSettings.wifiOnly(ctx))) return;

        String prefix = HaSettings.prefix(ctx);
        String entity = "switch." + prefix + "_hvac";
        HomeAssistantClient client = new HomeAssistantClient(
                ctx,
                HaSettings.url(ctx),
                HaSettings.token(ctx),
                HaSettings.allowInsecureSsl(ctx));

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

    public static void resetCache() {
        sLastHvacOn = null;
    }
}
