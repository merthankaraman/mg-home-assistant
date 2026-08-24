package com.drivehub.mgha.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.drivehub.mgha.prefs.HaSettings;
import com.drivehub.mgha.service.HaBridgeService;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (!HaSettings.autoStart(context)) return;
            if (!HaSettings.isConfigured(context)) return;
            context.startForegroundService(new Intent(context, HaBridgeService.class));
        }
    }
}
