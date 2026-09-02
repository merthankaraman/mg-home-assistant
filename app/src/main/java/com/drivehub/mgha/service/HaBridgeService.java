package com.drivehub.mgha.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.drivehub.mgha.R;
import com.drivehub.mgha.ha.HaCommandPoller;
import com.drivehub.mgha.ha.HaPublisher;
import com.drivehub.mgha.ha.HomeAssistantClient;
import com.drivehub.mgha.ha.UpdateReason;
import com.drivehub.mgha.hardware.VehicleReader;
import com.drivehub.mgha.hardware.VehicleSnapshot;
import com.drivehub.mgha.net.WifiHelper;
import com.drivehub.mgha.prefs.HaSettings;
import com.drivehub.mgha.ui.MainActivity;
import com.drivehub.mgha.util.MghaLog;

public class HaBridgeService extends Service {
    public static final String ACTION_STATUS = "com.drivehub.mgha.STATUS";
    public static final String ACTION_TICK_NOW = "com.drivehub.mgha.TICK_NOW";

    private static final String TAG = "MGHA_SVC";
    private static final String CHANNEL_ID = "mgha_bridge";
    private static final int NOTIF_ID = 41;

    /** Hazır değilken / geçici hatada yeniden deneme. */
    private static final long RETRY_SOON_MS = 1_000L;
    /** VALIDATED gelmezse bu süre sonra yine dene (OEM). */
    private static final long VALIDATED_GRACE_MS = 45_000L;

    private HandlerThread workerThread;
    private Handler worker;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean shuttingDown;
    private long serviceStartElapsedMs;
    /** finally’de kullanılacak bir sonraki gecikme; -1 = normal interval. */
    private long nextDelayMs = -1L;
    /** Son tick anlığındaki push modu (aralık seçimi). */
    private HaSettings.PushMode lastTickPushMode = HaSettings.PushMode.NORMAL;
    /** Son push'taki poll komut alanları (arabada değişince tam push tetiklenir). */
    private Integer lastPublishedChargeLimit;
    private Boolean lastPublishedHvac;
    private Integer lastPublishedHvacTemp;
    private Integer lastPublishedHvacFan;
    private Integer lastPublishedMediaVolume;
    /** Son okunan READY (yükselen kenar; WiFi şart değil). */
    private Boolean lastSeenVehicleReady;
    private String pendingTickReason = UpdateReason.STARTUP;

    private final Runnable tickRunnable = this::tick;
    private final Runnable pollRunnable = this::pollTick;
    private final Runnable readyWatchRunnable = this::readyWatchTick;

    /** READY + arabada poll alanı değişimi (WiFi yokken de okunur; gönderim WiFi ile). */
    private static final long READY_WATCH_MS = 1_000L;

    private void pollTick() {
        try {
            if (HaCommandPoller.poll(this)) {
                MghaLog.i(TAG, "HA güncelle → tam push");
                requestTickNow(UpdateReason.HA_COMMAND);
            }
        } catch (Throwable t) {
            MghaLog.w(TAG, "poll: " + t.getMessage());
        } finally {
            schedulePoll();
        }
    }

    private void schedulePoll() {
        if (shuttingDown || worker == null) return;
        worker.removeCallbacks(pollRunnable);
        // Refresh + aralık her zaman; komut poll ayrı bayrak
        worker.postDelayed(pollRunnable, HaSettings.pollIntervalMs(this));
    }

    private void startPollLoop() {
        if (shuttingDown || worker == null) return;
        worker.removeCallbacks(pollRunnable);
        worker.post(pollRunnable);
    }

    private void startReadyWatch() {
        if (shuttingDown || worker == null) return;
        worker.removeCallbacks(readyWatchRunnable);
        worker.postDelayed(readyWatchRunnable, READY_WATCH_MS);
    }

    /**
     * READY ve poll komut alanlarını saniyede bir oku.
     * READY false→true veya arabada değişim varsa WiFi uygunsa hemen tam push.
     */
    private void readyWatchTick() {
        try {
            if (!HaSettings.isConfigured(this)) return;
            VehicleSnapshot snap = VehicleReader.read();
            lastTickPushMode = HaSettings.pushMode(snap.charging);
            boolean readyRising = noteReadyRisingEdge(snap);
            if (!WifiHelper.canSend(this, HaSettings.wifiOnly(this))) return;
            if (readyRising) {
                MghaLog.i(TAG, "araç READY oldu → tam push");
                requestTickNow(UpdateReason.VEHICLE_READY);
                return;
            }
            if (pollCommandChangedOnCar(snap)) {
                MghaLog.i(TAG, "poll komutu arabada değişti → tam push");
                requestTickNow(UpdateReason.CAR_CHANGED);
            }
        } catch (Throwable t) {
            MghaLog.w(TAG, "readyWatch: " + t.getMessage());
        } finally {
            startReadyWatch();
        }
    }

    private boolean pollCommandChangedOnCar(VehicleSnapshot snap) {
        if (snap == null) return false;
        boolean changed = false;
        if (snap.chargeLimitPercent >= 40 && snap.chargeLimitPercent <= 100) {
            if (lastPublishedChargeLimit != null
                    && lastPublishedChargeLimit != snap.chargeLimitPercent) {
                changed = true;
            }
        }
        if (snap.hvacOn != null) {
            if (lastPublishedHvac != null && !lastPublishedHvac.equals(snap.hvacOn)) {
                changed = true;
            }
        }
        if (snap.hvacTempC >= 16 && snap.hvacTempC <= 30) {
            if (lastPublishedHvacTemp != null && !lastPublishedHvacTemp.equals(snap.hvacTempC)) {
                changed = true;
            }
        }
        if (snap.hvacFanLevel >= VehicleReader.HVAC_FAN_MIN
                && (snap.hvacFanLevel <= VehicleReader.HVAC_FAN_MAX_MANUAL
                || snap.hvacFanLevel == VehicleReader.HVAC_FAN_AUTO)) {
            if (lastPublishedHvacFan != null && lastPublishedHvacFan != snap.hvacFanLevel) {
                changed = true;
            }
        }
        if (snap.mediaVolumeLevel >= 0 && snap.mediaVolumeLevel <= 32) {
            if (lastPublishedMediaVolume != null
                    && lastPublishedMediaVolume != snap.mediaVolumeLevel) {
                changed = true;
            }
        }
        return changed;
    }

    /** READY false→true: son çalışma zamanını kaydet; true dönerse WiFi varsa hemen push. */
    private boolean noteReadyRisingEdge(VehicleSnapshot snap) {
        if (snap == null) return false;
        Boolean prev = lastSeenVehicleReady;
        boolean nowReady = snap.vehicleReady;
        lastSeenVehicleReady = nowReady;
        if (nowReady) {
            if (HaSettings.vehicleLastRunMs(this) <= 0) {
                long now = System.currentTimeMillis();
                HaSettings.setVehicleLastRunMs(this, now);
                snap.vehicleLastRunMs = now;
            }
            if (Boolean.FALSE.equals(prev)) {
                long now = System.currentTimeMillis();
                HaSettings.setVehicleLastRunMs(this, now);
                snap.vehicleLastRunMs = now;
                HaCommandPoller.resetCommandsBaseline();
                return true;
            }
        }
        return false;
    }

    private void rememberPublishedPollCommands(VehicleSnapshot snap) {
        if (snap == null) return;
        if (snap.chargeLimitPercent >= 40 && snap.chargeLimitPercent <= 100) {
            lastPublishedChargeLimit = snap.chargeLimitPercent;
        }
        if (snap.hvacOn != null) {
            lastPublishedHvac = snap.hvacOn;
        }
        if (snap.hvacTempC >= 16 && snap.hvacTempC <= 30) {
            lastPublishedHvacTemp = snap.hvacTempC;
        }
        if (snap.hvacFanLevel >= VehicleReader.HVAC_FAN_MIN
                && (snap.hvacFanLevel <= VehicleReader.HVAC_FAN_MAX_MANUAL
                || snap.hvacFanLevel == VehicleReader.HVAC_FAN_AUTO)) {
            lastPublishedHvacFan = snap.hvacFanLevel;
        }
        if (snap.mediaVolumeLevel >= 0 && snap.mediaVolumeLevel <= 32) {
            lastPublishedMediaVolume = snap.mediaVolumeLevel;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startFg(buildNotification(getString(R.string.notify_running)));
        serviceStartElapsedMs = SystemClock.elapsedRealtime();
        HaSettings.refreshVerboseCache(this);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mgha:bridge");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(HaSettings.intervalMs(this) + 120_000L);
        }

        workerThread = new HandlerThread("mgha-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        VehicleReader.init(this);
        VehicleReader.ensureReady(this);
        VehicleReader.startGpsUpdates(this);
        if (HaSettings.wifiOnBoot(this)) {
            WifiHelper.ensureWifiEnabled(this);
        }
        if (!WifiHelper.isSim()) {
            WifiHelper.maintainWifiConnection(this,
                    HaSettings.wifiOnBoot(this) || HaSettings.wifiOnly(this), true);
        }
        BridgeStatus.running = true;
        BridgeStatus.lastMessage = getString(R.string.msg_service_started);
        HaCommandPoller.resetCommandsBaseline();
        registerNetworkCallback();
        requestTickNow(UpdateReason.STARTUP);
        startPollLoop();
        startReadyWatch();
        broadcastStatus();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg(buildNotification(BridgeStatus.lastMessage));
        VehicleReader.startGpsUpdates(this);
        if (intent != null && ACTION_TICK_NOW.equals(intent.getAction()) && worker != null) {
            requestTickNow(UpdateReason.MANUAL);
        }
        startPollLoop();
        return START_STICKY;
    }

    private void startFg(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, notification);
        }
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
        BridgeStatus.lastMessage = getString(R.string.msg_service_stopped);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
        broadcastStatus();
    }

    private void tick() {
        String updateReason = pendingTickReason != null ? pendingTickReason : UpdateReason.PERIODIC;
        MghaLog.i(TAG, "tick başlıyor reason=" + updateReason);
        nextDelayMs = -1L;
        renewWakeLock();
        try {
            BridgeStatus.lastMessage = getString(R.string.msg_tick);
            broadcastStatus();

            VehicleSnapshot snap = VehicleReader.read();
            BridgeStatus.carOk = snap.carConnected;
            lastTickPushMode = HaSettings.pushMode(snap.charging);
            if (snap.vehicleReady && HaSettings.vehicleLastRunMs(this) <= 0) {
                HaSettings.setVehicleLastRunMs(this, snap.capturedAtMs);
                snap.vehicleLastRunMs = snap.capturedAtMs;
            }
            boolean wifi = WifiHelper.hasWifiInternet(this);
            boolean anyNet = WifiHelper.hasAnyInternet(this);
            boolean validated = WifiHelper.isValidated(this);
            BridgeStatus.wifiOk = wifi;
            BridgeStatus.lastPreview = preview(snap);

            if (!HaSettings.isConfigured(this)) {
                BridgeStatus.lastMessage = getString(R.string.msg_not_configured);
                MghaLog.i(TAG, BridgeStatus.lastMessage);
                notifyText(BridgeStatus.lastMessage);
                return;
            }

            boolean allowed = WifiHelper.canSend(this, HaSettings.wifiOnly(this));
            MghaLog.i(TAG, "tick flavor=" + com.drivehub.mgha.BuildConfig.FLAVOR
                    + " carOk=" + BridgeStatus.carOk
                    + " net=" + WifiHelper.describe(this)
                    + " any=" + anyNet + " wifi=" + wifi
                    + " validated=" + validated
                    + " demo=" + HaSettings.demoMode(this)
                    + " wifiOnly=" + HaSettings.wifiOnly(this)
                    + " allowed=" + allowed
                    + " url=" + HaSettings.url(this));

            if (!allowed) {
                if (!WifiHelper.isSim()) {
                    WifiHelper.maintainWifiConnection(this,
                            HaSettings.wifiOnBoot(this) || HaSettings.wifiOnly(this));
                }
                BridgeStatus.lastMessage = WifiHelper.isSim()
                        ? getString(R.string.msg_no_internet_sim, WifiHelper.describe(this))
                        : (HaSettings.wifiOnly(this)
                        ? getString(R.string.msg_no_wifi, WifiHelper.describe(this))
                        : getString(R.string.msg_no_internet));
                notifyText(getString(R.string.notify_waiting_wifi));
                nextDelayMs = RETRY_SOON_MS;
                return;
            }

            // Erken DNS/SSL: VALIDATED yoksa kısa süre bekle (OEM hiç vermezse grace sonrası gönder)
            if (!WifiHelper.isSim()
                    && !validated
                    && SystemClock.elapsedRealtime() - serviceStartElapsedMs < VALIDATED_GRACE_MS) {
                BridgeStatus.lastMessage = getString(R.string.msg_wait_net);
                MghaLog.i(TAG, BridgeStatus.lastMessage);
                notifyText(BridgeStatus.lastMessage);
                nextDelayMs = RETRY_SOON_MS;
                return;
            }

            if (WifiHelper.isSim() && !HaSettings.demoMode(this) && !snap.carConnected) {
                BridgeStatus.lastMessage = getString(R.string.msg_sim_need_demo);
                MghaLog.i(TAG, BridgeStatus.lastMessage);
                notifyText(BridgeStatus.lastMessage);
                return;
            }

            // Boş araç anlığı gönderme (GPS sonra gelebilir)
            if (!HaSettings.demoMode(this) && !hasUsefulVehicleData(snap)) {
                BridgeStatus.lastMessage = getString(R.string.msg_wait_car);
                MghaLog.i(TAG, BridgeStatus.lastMessage + " (cpm henüz yok)");
                notifyText(BridgeStatus.lastMessage);
                nextDelayMs = RETRY_SOON_MS;
                return;
            }

            long waitMs = remainingPushIntervalMs(updateReason);
            if (waitMs > 0) {
                MghaLog.i(TAG, "aralık bekleniyor " + (waitMs / 1000L) + "s (reason=" + updateReason + ")");
                nextDelayMs = waitMs;
                return;
            }

            BridgeStatus.lastMessage = getString(R.string.msg_sending);
            notifyText(BridgeStatus.lastMessage);
            broadcastStatus();

            HomeAssistantClient client = new HomeAssistantClient(
                    this,
                    HaSettings.url(this),
                    HaSettings.token(this),
                    HaSettings.allowInsecureSsl(this));
            HaPublisher.PublishResult r = HaPublisher.publish(
                    this, client, HaSettings.prefix(this), snap, updateReason);
            BridgeStatus.lastOkCount = r.ok;
            BridgeStatus.lastFailCount = r.fail;
            BridgeStatus.lastSendAtMs = System.currentTimeMillis();
            BridgeStatus.lastSendOk = r.fail == 0 && r.ok > 0;
            if (BridgeStatus.lastSendOk) {
                BridgeStatus.lastUpdateReason = updateReason;
                rememberPublishedPollCommands(snap);
                String dest = r.viaBridge
                        ? ("mg4_bridge/" + HaSettings.prefix(this))
                        : ("group." + HaSettings.prefix(this));
                BridgeStatus.lastMessage = getString(R.string.msg_sent_ok, dest, r.ok);
                notifyText(getString(R.string.notify_sent));
            } else {
                String errPart = r.lastError != null ? (" " + r.lastError) : "";
                BridgeStatus.lastMessage = getString(R.string.msg_sent_partial, r.ok, r.fail, errPart);
                notifyText(getString(R.string.notify_error));
                // Geçici HA hatası → kısa sonra tekrar (REST yağmuru yok)
                nextDelayMs = RETRY_SOON_MS;
            }
            MghaLog.i(TAG, BridgeStatus.lastMessage);
        } catch (Throwable t) {
            BridgeStatus.lastSendOk = false;
            BridgeStatus.lastMessage = getString(R.string.msg_error,
                    t.getMessage() == null ? "" : t.getMessage());
            MghaLog.e(TAG, "tick", t);
            notifyText(getString(R.string.notify_error));
            nextDelayMs = RETRY_SOON_MS;
        } finally {
            broadcastStatus();
            if (!shuttingDown && worker != null) {
                long delay = nextDelayMs > 0
                        ? nextDelayMs
                        : HaSettings.intervalMsForMode(this, lastTickPushMode);
                scheduleNextTick(delay,
                        nextDelayMs > 0 ? UpdateReason.RETRY : UpdateReason.PERIODIC);
            }
        }
    }

    private void requestTickNow(String reason) {
        if (worker == null) return;
        pendingTickReason = reason;
        worker.removeCallbacks(tickRunnable);
        worker.post(tickRunnable);
    }

    private void scheduleNextTick(long delayMs, String reason) {
        if (shuttingDown || worker == null) return;
        pendingTickReason = reason;
        worker.removeCallbacks(tickRunnable);
        worker.postDelayed(tickRunnable, delayMs);
    }

    private long currentPushIntervalMs() {
        return HaSettings.intervalMsForMode(this, lastTickPushMode);
    }

    /**
     * Son başarılı gönderimden bu yana aralık dolmadıysa kalan ms; aksi halde 0.
     * WiFi / periyodik / retry aynı aralığı paylaşır.
     */
    private long remainingPushIntervalMs(String reason) {
        if (!BridgeStatus.lastSendOk || BridgeStatus.lastSendAtMs <= 0) {
            return 0;
        }
        if (!UpdateReason.PERIODIC.equals(reason)
                && !UpdateReason.RETRY.equals(reason)
                && !UpdateReason.WIFI.equals(reason)) {
            return 0;
        }
        long since = System.currentTimeMillis() - BridgeStatus.lastSendAtMs;
        long interval = currentPushIntervalMs();
        return since >= interval ? 0 : interval - since;
    }

    /** SOC / menzil / km’den biri doluysa göndermeye değer (GPS şart değil). */
    private static boolean hasUsefulVehicleData(VehicleSnapshot snap) {
        if (snap == null) return false;
        if (!Float.isNaN(snap.socPercent)) return true;
        if (snap.rangeKm >= 0) return true;
        if (snap.odometerKm >= 0) return true;
        return false;
    }

    private void tryMarkOffline() {
        if (!HaSettings.isConfigured(this)) return;
        if (!WifiHelper.canSend(this, HaSettings.wifiOnly(this))) return;
        try {
            HomeAssistantClient client = new HomeAssistantClient(
                    this,
                    HaSettings.url(this),
                    HaSettings.token(this),
                    HaSettings.allowInsecureSsl(this));
            HaPublisher.markOffline(this, client, HaSettings.prefix(this));
        } catch (Throwable ignored) {}
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        NetworkRequest.Builder b = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        if (!WifiHelper.isSim()) {
            b.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        }
        NetworkRequest req = b.build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@NonNull Network network) {
                if (!WifiHelper.isSim()) {
                    WifiHelper.maintainWifiConnection(HaBridgeService.this,
                            HaSettings.wifiOnBoot(HaBridgeService.this)
                                    || HaSettings.wifiOnly(HaBridgeService.this), true);
                }
            }

            @Override
            public void onAvailable(@NonNull Network network) {
                onWifiConnected("onAvailable");
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                                              @NonNull NetworkCapabilities caps) {
                if (Build.VERSION.SDK_INT >= 23
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    onWifiConnected("validated");
                }
            }
        };
        try {
            connectivityManager.registerNetworkCallback(req, networkCallback);
        } catch (Exception e) {
            MghaLog.w(TAG, "network callback: " + e.getMessage());
        }
    }

    /** WiFi bağlanınca: aralık dolduysa hemen gönder, dolmadıysa kalan süreyi bekle. */
    private void onWifiConnected(String reason) {
        if (worker == null) return;
        long waitMs = remainingPushIntervalMs(UpdateReason.WIFI);
        if (waitMs > 0) {
            if (MghaLog.isVerbose()) {
                MghaLog.i(TAG, "wifi " + reason + " yok sayıldı (aralık "
                        + (waitMs / 1000L) + "s kaldı)");
            }
            scheduleNextTick(waitMs, UpdateReason.PERIODIC);
            return;
        }
        MghaLog.i(TAG, "wifi " + reason + " → tick");
        requestTickNow(UpdateReason.WIFI);
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
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_bridge), NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void renewWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock.acquire(HaSettings.intervalMs(this) + 120_000L);
    }

    private String preview(VehicleSnapshot s) {
        return s.formatForScreen(this);
    }
}
