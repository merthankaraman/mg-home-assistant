package com.drivehub.mgha.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.drivehub.mgha.net.WifiHelper;
import com.drivehub.mgha.prefs.HaSettings;
import com.drivehub.mgha.service.HaBridgeService;
import com.drivehub.mgha.util.MghaLog;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "MGHA_BOOT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (!HaSettings.autoStart(context)) return;
            if (!HaSettings.isConfigured(context)) return;
            HaSettings.refreshVerboseCache(context);
            if (HaSettings.wifiOnBoot(context)) {
                MghaLog.i(TAG, "boot → WiFi aç");
                WifiHelper.ensureWifiEnabled(context);
            }
            context.startForegroundService(new Intent(context, HaBridgeService.class));
        }
    }
}
