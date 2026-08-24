package com.drivehub.mgha.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.drivehub.mgha.BuildConfig;
import com.drivehub.mgha.R;
import com.drivehub.mgha.ha.HomeAssistantClient;
import com.drivehub.mgha.hardware.VehicleReader;
import com.drivehub.mgha.hardware.VehicleSnapshot;
import com.drivehub.mgha.net.WifiHelper;
import com.drivehub.mgha.prefs.HaSettings;
import com.drivehub.mgha.service.BridgeStatus;
import com.drivehub.mgha.service.HaBridgeService;
import com.drivehub.mgha.sync.ConfigWebServer;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private EditText urlView;
    private EditText tokenView;
    private EditText prefixView;
    private EditText intervalView;
    private CheckBox wifiOnlyView;
    private CheckBox insecureView;
    private CheckBox autoStartView;
    private TextView statusView;
    private TextView previewView;
    private Button startStopBtn;
    private Button demoBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ConfigWebServer webServer = new ConfigWebServer();
    private AlertDialog pairingDialog;
    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            if (BridgeStatus.running) {
                ui.postDelayed(this, 1000);
            }
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestNotifyPermission();
        requestLocationPermission();

        urlView = findViewById(R.id.input_url);
        tokenView = findViewById(R.id.input_token);
        prefixView = findViewById(R.id.input_prefix);
        intervalView = findViewById(R.id.input_interval);
        wifiOnlyView = findViewById(R.id.check_wifi_only);
        insecureView = findViewById(R.id.check_insecure);
        autoStartView = findViewById(R.id.check_autostart);
        statusView = findViewById(R.id.text_status);
        previewView = findViewById(R.id.text_preview);
        startStopBtn = findViewById(R.id.btn_start_stop);
        demoBtn = findViewById(R.id.btn_demo);

        TextView versionView = findViewById(R.id.text_version);
        versionView.setText("v" + BuildConfig.VERSION_NAME
                + "  (" + BuildConfig.FLAVOR + (BuildConfig.DEBUG ? " debug" : "") + ")");

        loadFromSettings();

        findViewById(R.id.btn_save).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btn_test).setOnClickListener(v -> testHa());
        startStopBtn.setOnClickListener(v -> toggleService());
        findViewById(R.id.btn_send_now).setOnClickListener(v -> sendNow());
        demoBtn.setOnClickListener(v -> toggleDemo());
        findViewById(R.id.btn_paste_token).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.btn_from_web).setOnClickListener(v -> startFromWeb());

        VehicleReader.init(this);
        refreshDemoButton();
        refreshStatus();
        ui.postDelayed(this::refreshLocalPreview, 400);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(HaBridgeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, f);
        }
        refreshStatus();
        refreshLocalPreview();
        ui.removeCallbacks(statusPoll);
        if (BridgeStatus.running) {
            ui.post(statusPoll);
        }
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(statusPoll);
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {}
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        webServer.stop();
        dismissPairingDialog();
        super.onDestroy();
    }

    private void loadFromSettings() {
        urlView.setText(HaSettings.url(this));
        tokenView.setText(HaSettings.token(this));
        prefixView.setText(HaSettings.prefix(this));
        intervalView.setText(String.valueOf(HaSettings.intervalSec(this)));
        wifiOnlyView.setChecked(HaSettings.wifiOnly(this));
        insecureView.setChecked(HaSettings.allowInsecureSsl(this));
        autoStartView.setChecked(HaSettings.autoStart(this));
    }

    private void requestNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 21);
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, 22);
    }

    private void saveSettings() {
        int interval = 30;
        try {
            interval = Integer.parseInt(intervalView.getText().toString().trim());
        } catch (Exception ignored) {}
        HaSettings.save(this,
                urlView.getText().toString(),
                tokenView.getText().toString(),
                prefixView.getText().toString(),
                interval,
                wifiOnlyView.isChecked(),
                insecureView.isChecked(),
                autoStartView.isChecked());
        Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void testHa() {
        saveSettings();
        if (!HaSettings.isConfigured(this)) {
            Toast.makeText(this, "Önce URL ve token gir", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            HomeAssistantClient client = new HomeAssistantClient(
                    HaSettings.url(this),
                    HaSettings.token(this),
                    HaSettings.allowInsecureSsl(this));
            HomeAssistantClient.Result r = client.testConnection();
            runOnUiThread(() -> {
                if (r.ok) {
                    Toast.makeText(this, "Home Assistant bağlı", Toast.LENGTH_SHORT).show();
                    statusView.setText("HA test: OK " + (r.body == null ? "" : r.body));
                } else {
                    Toast.makeText(this, "HA test başarısız", Toast.LENGTH_SHORT).show();
                    statusView.setText("HA test hata: " + (r.error != null ? r.error : r.body));
                }
            });
        }, "mgha-test").start();
    }

    private void toggleService() {
        saveSettings();
        if (BridgeStatus.running) {
            stopService(new Intent(this, HaBridgeService.class));
        } else {
            if (!HaSettings.isConfigured(this)) {
                Toast.makeText(this, "Önce URL ve token kaydet", Toast.LENGTH_SHORT).show();
                return;
            }
            ContextCompat.startForegroundService(this, new Intent(this, HaBridgeService.class));
        }
        ui.removeCallbacks(statusPoll);
        ui.post(statusPoll);
        ui.postDelayed(this::refreshStatus, 400);
    }

    private void sendNow() {
        saveSettings();
        if (!HaSettings.isConfigured(this)) {
            Toast.makeText(this, "Önce URL ve token kaydet", Toast.LENGTH_SHORT).show();
            return;
        }
        ensureServiceAndTick();
        Toast.makeText(this, "Şimdi gönderiliyor", Toast.LENGTH_SHORT).show();
    }

    private void toggleDemo() {
        saveSettings();
        boolean next = !HaSettings.demoMode(this);
        HaSettings.setDemoMode(this, next);
        refreshDemoButton();
        refreshLocalPreview();
        if (next) {
            if (!HaSettings.isConfigured(this)) {
                Toast.makeText(this, "Demo veri ekranda. HA'ya gitmesi için URL ve token gir.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            ensureServiceAndTick();
            Toast.makeText(this, "Demo açık — sahte veri HA'ya gönderiliyor", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Demo kapalı", Toast.LENGTH_SHORT).show();
            if (BridgeStatus.running) {
                ensureServiceAndTick();
            }
        }
    }

    private void ensureServiceAndTick() {
        if (!BridgeStatus.running) {
            ContextCompat.startForegroundService(this, new Intent(this, HaBridgeService.class));
        }
        Intent i = new Intent(this, HaBridgeService.class);
        i.setAction(HaBridgeService.ACTION_TICK_NOW);
        ContextCompat.startForegroundService(this, i);
        ui.removeCallbacks(statusPoll);
        ui.post(statusPoll);
    }

    private void refreshDemoButton() {
        demoBtn.setText(HaSettings.demoMode(this) ? "Demo modu açık — kapat" : "Demo modu");
    }

    private void refreshStatus() {
        startStopBtn.setText(BridgeStatus.running ? "Servisi durdur" : "Servisi başlat");
        refreshDemoButton();
        String last = BridgeStatus.lastSendAtMs == 0
                ? "-"
                : new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(BridgeStatus.lastSendAtMs));
        StringBuilder sb = new StringBuilder();
        sb.append("Servis: ").append(BridgeStatus.running ? "AÇIK" : "KAPALI").append('\n');
        sb.append("Mod: ").append(HaSettings.demoMode(this) ? "DEMO" : "araç").append('\n');
        if (!HaSettings.demoMode(this)) {
            sb.append("Araç CPM: ").append(VehicleReader.isReady() ? "bağlı" : "yok").append('\n');
        }
        sb.append("Ağ: ").append(WifiHelper.describe(this));
        sb.append("  (any ").append(WifiHelper.hasAnyInternet(this) ? "var" : "yok");
        sb.append(", WiFi ").append(WifiHelper.hasWifiInternet(this) ? "var" : "yok").append(")\n");
        if (WifiHelper.isSim()) {
            sb.append("Sim: WiFi şart değil — hücresel/ethernet OK\n");
        }
        sb.append("Yapılandırma: ").append(HaSettings.isConfigured(this) ? "tamam" : "URL/token eksik").append('\n');
        sb.append("Son gönderim: ").append(last);
        if (BridgeStatus.lastOkCount + BridgeStatus.lastFailCount > 0) {
            sb.append("  (ok ").append(BridgeStatus.lastOkCount)
                    .append(" / fail ").append(BridgeStatus.lastFailCount).append(')');
        }
        sb.append('\n');
        sb.append("Durum: ").append(BridgeStatus.lastMessage);
        statusView.setText(sb.toString());
        if (BridgeStatus.lastPreview != null && !BridgeStatus.lastPreview.isEmpty()) {
            previewView.setText(BridgeStatus.lastPreview);
        }
    }

    private void refreshLocalPreview() {
        VehicleSnapshot s = VehicleReader.read();
        previewView.setText(s.formatForScreen());
        if (!BridgeStatus.running) {
            BridgeStatus.carOk = s.carConnected;
            refreshStatus();
            previewView.setText(s.formatForScreen());
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, "Pano boş", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        CharSequence cs = clip == null || clip.getItemCount() == 0
                ? null : clip.getItemAt(0).coerceToText(this);
        String t = cs == null ? "" : cs.toString().trim();
        if (t.isEmpty()) {
            Toast.makeText(this, "Pano boş", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject cfg = HaSettings.parseConfig(t);
        if (cfg != null && HaSettings.applyJson(this, cfg)) {
            loadFromSettings();
            refreshStatus();
            Toast.makeText(this, "Ayarlar panodan alındı", Toast.LENGTH_SHORT).show();
            return;
        }
        if (t.startsWith("http://") || t.startsWith("https://")) {
            urlView.setText(t);
            Toast.makeText(this, "URL yapıştırıldı", Toast.LENGTH_SHORT).show();
            return;
        }
        tokenView.setText(t);
        Toast.makeText(this, "Token yapıştırıldı — Kaydet’e bas", Toast.LENGTH_SHORT).show();
    }

    private void startFromWeb() {
        webServer.stop();
        TextView urlViewBig = new TextView(this);
        urlViewBig.setTextSize(20);
        urlViewBig.setGravity(Gravity.CENTER);
        urlViewBig.setTextColor(ContextCompat.getColor(this, R.color.accent));
        urlViewBig.setPadding(24, 24, 24, 8);
        urlViewBig.setTextIsSelectable(true);
        urlViewBig.setText("Bağlantı hazırlanıyor…");
        pairingDialog = new AlertDialog.Builder(this)
                .setTitle("Siteden al")
                .setMessage("Telefon ve araba aynı WiFi’de olsun. Telefonda tarayıcıya şu adresi yaz, token’ı yapıştırıp gönder.")
                .setView(urlViewBig)
                .setNegativeButton("İptal", (d, w) -> webServer.stop())
                .setCancelable(false)
                .create();
        pairingDialog.show();
        webServer.start(new ConfigWebServer.Listener() {
            @Override
            public void onReady(String openUrl) {
                urlViewBig.setText(openUrl);
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("mgha", openUrl));
                }
            }

            @Override
            public void onStatus(String msg) {
                if (pairingDialog != null && pairingDialog.isShowing()) {
                    pairingDialog.setMessage(msg);
                }
            }

            @Override
            public void onReceived(JSONObject cfg) {
                applyIncoming(cfg, "Siteden alındı");
            }

            @Override
            public void onFailed(String reason) {
                dismissPairingDialog();
                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void applyIncoming(JSONObject cfg, String okMsg) {
        dismissPairingDialog();
        if (!HaSettings.applyJson(this, cfg)) {
            Toast.makeText(this, "Gelen ayar eksik", Toast.LENGTH_LONG).show();
            return;
        }
        loadFromSettings();
        refreshStatus();
        Toast.makeText(this, okMsg, Toast.LENGTH_SHORT).show();
    }

    private void dismissPairingDialog() {
        try {
            if (pairingDialog != null && pairingDialog.isShowing()) pairingDialog.dismiss();
        } catch (Exception ignored) {}
        pairingDialog = null;
        webServer.stop();
    }
}
