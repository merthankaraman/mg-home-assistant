package com.drivehub.mgha.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.drivehub.mgha.R;
import com.drivehub.mgha.ha.HaPublisher;
import com.drivehub.mgha.ha.HomeAssistantClient;
import com.drivehub.mgha.hardware.VehicleReader;
import com.drivehub.mgha.hardware.VehicleSnapshot;
import com.drivehub.mgha.net.WifiHelper;
import com.drivehub.mgha.prefs.HaSettings;
import com.drivehub.mgha.ui.MainActivity;

public class HaBridgeService extends Service {
    public static final String ACTION_STATUS = "com.drivehub.mgha.STATUS";
    public static final String ACTION_TICK_NOW = "com.drivehub.mgha.TICK_NOW";

    private static final String TAG = "MGHA_SVC";
    private static final String CHANNEL_ID = "mgha_bridge";
    private static final int NOTIF_ID = 41;

    private HandlerThread workerThread;
    private Handler worker;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean shuttingDown;

    private final Runnable tickRunnable = this::tick;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notify_running)));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mgha:bridge");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }

        workerThread = new HandlerThread("mgha-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        VehicleReader.init(this);
        BridgeStatus.running = true;
        BridgeStatus.lastMessage = "Servis açıldı";
        registerNetworkCallback();
        worker.post(tickRunnable);
        broadcastStatus();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification(BridgeStatus.lastMessage));
        if (intent != null && ACTION_TICK_NOW.equals(intent.getAction()) && worker != null) {
            worker.removeCallbacks(tickRunnable);
            worker.post(tickRunnable);
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shuttingDown = true;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
        unregisterNetworkCallback();
        tryMarkOffline();
        BridgeStatus.running = false;
        BridgeStatus.lastMessage = "Servis kapalı";
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
        broadcastStatus();
    }

    private void tick() {
        Log.i(TAG, "tick başlıyor");
        try {
            BridgeStatus.lastMessage = "Tick…";
            broadcastStatus();

            VehicleSnapshot snap = VehicleReader.read();
            BridgeStatus.carOk = snap.carConnected;
            boolean wifi = WifiHelper.hasWifiInternet(this);
            boolean anyNet = WifiHelper.hasAnyInternet(this);
            BridgeStatus.wifiOk = wifi;
            BridgeStatus.lastPreview = preview(snap);

            if (!HaSettings.isConfigured(this)) {
                BridgeStatus.lastMessage = "URL ve token kaydedilmedi";
                Log.i(TAG, BridgeStatus.lastMessage);
                notifyText(BridgeStatus.lastMessage);
                return;
            }

            boolean allowed = WifiHelper.canSend(this, HaSettings.wifiOnly(this));
            Log.i(TAG, "tick flavor=" + com.drivehub.mgha.BuildConfig.FLAVOR
                    + " net=" + WifiHelper.describe(this)
                    + " any=" + anyNet + " wifi=" + wifi
                    + " demo=" + HaSettings.demoMode(this)
                    + " wifiOnly=" + HaSettings.wifiOnly(this)
                    + " allowed=" + allowed
                    + " url=" + HaSettings.url(this));

            if (!allowed) {
                BridgeStatus.lastMessage = WifiHelper.isSim()
                        ? "İnternet yok — gönderilmedi (" + WifiHelper.describe(this) + ")"
                        : (HaSettings.wifiOnly(this)
                        ? "WiFi yok — gönderilmedi (aktif: " + WifiHelper.describe(this) + ")"
                        : "İnternet yok — gönderilmedi");
                notifyText(getString(R.string.notify_waiting_wifi));
                return;
            }

            if (WifiHelper.isSim() && !HaSettings.demoMode(this) && !snap.carConnected) {
                BridgeStatus.lastMessage = "Sim’de araç verisi yok — Demo modu aç";
                Log.i(TAG, BridgeStatus.lastMessage);
                notifyText(BridgeStatus.lastMessage);
                return;
            }

            BridgeStatus.lastMessage = "HA’ya gönderiliyor…";
            notifyText(BridgeStatus.lastMessage);
            broadcastStatus();

            HomeAssistantClient client = new HomeAssistantClient(
                    HaSettings.url(this),
                    HaSettings.token(this),
                    HaSettings.allowInsecureSsl(this));
            HaPublisher.PublishResult r = HaPublisher.publish(client, HaSettings.prefix(this), snap);
            BridgeStatus.lastOkCount = r.ok;
            BridgeStatus.lastFailCount = r.fail;
            BridgeStatus.lastSendAtMs = System.currentTimeMillis();
            BridgeStatus.lastSendOk = r.fail == 0 && r.ok > 0;
            if (BridgeStatus.lastSendOk) {
                BridgeStatus.lastMessage = "Gönderildi: group.mg4 (ok " + r.ok + ")";
                notifyText(getString(R.string.notify_sent));
            } else {
                BridgeStatus.lastMessage = "Kısmi/hata: ok=" + r.ok + " fail=" + r.fail
                        + (r.lastError != null ? (" " + r.lastError) : "");
                notifyText(getString(R.string.notify_error));
            }
            Log.i(TAG, BridgeStatus.lastMessage);
        } catch (Throwable t) {
            BridgeStatus.lastSendOk = false;
            BridgeStatus.lastMessage = "Hata: " + t.getMessage();
            Log.e(TAG, "tick", t);
            notifyText(getString(R.string.notify_error));
        } finally {
            broadcastStatus();
            if (!shuttingDown && worker != null) {
                long delay = HaSettings.intervalSec(this) * 1000L;
                worker.postDelayed(tickRunnable, delay);
            }
        }
    }

    private void tryMarkOffline() {
        if (!HaSettings.isConfigured(this)) return;
        if (!WifiHelper.canSend(this, HaSettings.wifiOnly(this))) return;
        try {
            HomeAssistantClient client = new HomeAssistantClient(
                    HaSettings.url(this),
                    HaSettings.token(this),
                    HaSettings.allowInsecureSsl(this));
            HaPublisher.markOffline(client, HaSettings.prefix(this));
        } catch (Throwable ignored) {}
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        NetworkRequest.Builder b = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        // Arabada WiFi; telefonda / sim’de hücresel ve ethernet de dinle
        if (!WifiHelper.isSim()) {
            b.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        }
        NetworkRequest req = b.build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "network onAvailable → tick");
                if (worker != null) {
                    worker.removeCallbacks(tickRunnable);
                    worker.post(tickRunnable);
                }
            }
        };
        try {
            connectivityManager.registerNetworkCallback(req, networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "network callback: " + e.getMessage());
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
    }

    private void broadcastStatus() {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
    }

    private void notifyText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_bridge), NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private static String preview(VehicleSnapshot s) {
        return s.formatForScreen();
    }
}
