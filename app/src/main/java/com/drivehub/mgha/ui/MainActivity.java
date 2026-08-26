package com.drivehub.mgha.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
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

import androidx.annotation.NonNull;
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
import com.drivehub.mgha.ota.OtaController;
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
    private CheckBox wifiOnBootView;
    private CheckBox verboseLogView;
    private TextView statusView;
    private TextView previewView;
    private Button startStopBtn;
    private Button demoBtn;
    private OtaController otaController;

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
        if (!WifiHelper.isSim()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
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
        wifiOnBootView = findViewById(R.id.check_wifi_on_boot);
        verboseLogView = findViewById(R.id.check_verbose_log);
        statusView = findViewById(R.id.text_status);
        previewView = findViewById(R.id.text_preview);
        startStopBtn = findViewById(R.id.btn_start_stop);
        demoBtn = findViewById(R.id.btn_demo);

        TextView versionView = findViewById(R.id.text_version);
        versionView.setText(getString(R.string.version_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.DEBUG ? "(" + BuildConfig.FLAVOR : "",
                BuildConfig.DEBUG ? getString(R.string.version_debug_suffix) + ")" : ""));

        loadFromSettings();
        HaSettings.refreshVerboseCache(this);

        findViewById(R.id.btn_save).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btn_test).setOnClickListener(v -> testHa());
        startStopBtn.setOnClickListener(v -> toggleService());
        findViewById(R.id.btn_send_now).setOnClickListener(v -> sendNow());
        demoBtn.setOnClickListener(v -> toggleDemo());
        findViewById(R.id.btn_paste_token).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.btn_from_web).setOnClickListener(v -> startFromWeb());

        otaController = new OtaController(this);
        otaController.setup(
                findViewById(R.id.btn_ota_check),
                findViewById(R.id.text_ota_status),
                findViewById(R.id.check_ota_beta));
        otaController.checkOnStartup();

        VehicleReader.init(this);
        refreshDemoButton();
        refreshStatus();
        ui.postDelayed(this::refreshLocalPreview, 400);
    }

    /** Açılışta başlat açıksa ve ayar kayıtlıysa servisi kaldır (boot geçmiş olsa bile). */
    private void maybeAutoStartService() {
        if (BridgeStatus.running) return;
        if (!HaSettings.autoStart(this)) return;
        if (!HaSettings.isConfigured(this)) return;
        ContextCompat.startForegroundService(this, new Intent(this, HaBridgeService.class));
        ui.removeCallbacks(statusPoll);
        ui.post(statusPoll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(HaBridgeService.ACTION_STATUS);
        ContextCompat.registerReceiver(this, statusReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        maybeAutoStartService();
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
        if (otaController != null) {
            otaController.stop();
        }
        webServer.stop();
        dismissPairingDialog();
        super.onDestroy();
    }

    private void loadFromSettings() {
        urlView.setText(HaSettings.url(this));
        tokenView.setText(HaSettings.token(this));
        prefixView.setText(HaSettings.prefix(this));
        intervalView.setText(String.valueOf(HaSettings.intervalMin(this)));
        wifiOnlyView.setChecked(HaSettings.wifiOnly(this));
        insecureView.setChecked(HaSettings.allowInsecureSsl(this));
        autoStartView.setChecked(HaSettings.autoStart(this));
        wifiOnBootView.setChecked(HaSettings.wifiOnBoot(this));
        verboseLogView.setChecked(HaSettings.verboseLog(this));
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
            VehicleReader.startGpsUpdates(this);
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, 22);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 22) {
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) {
                    VehicleReader.startGpsUpdates(this);
                    break;
                }
            }
        }
    }

    private void saveSettings() {
        int interval = 1;
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
                autoStartView.isChecked(),
                wifiOnBootView.isChecked(),
                verboseLogView.isChecked());
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void testHa() {
        saveSettings();
        if (!HaSettings.isConfigured(this)) {
            Toast.makeText(this, R.string.toast_need_url_token, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            HomeAssistantClient client = new HomeAssistantClient(
                    this,
                    HaSettings.url(this),
                    HaSettings.token(this),
                    HaSettings.allowInsecureSsl(this));
            HomeAssistantClient.Result r = client.testConnection();
            runOnUiThread(() -> {
                if (r.ok) {
                    Toast.makeText(this, R.string.toast_ha_ok, Toast.LENGTH_SHORT).show();
                    statusView.setText(getString(R.string.ha_test_ok, r.body == null ? "" : r.body));
                } else {
                    Toast.makeText(this, R.string.toast_ha_fail, Toast.LENGTH_SHORT).show();
                    statusView.setText(getString(R.string.ha_test_fail,
                            r.error != null ? r.error : r.body));
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
                Toast.makeText(this, R.string.toast_need_url_token_save, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, R.string.toast_need_url_token_save, Toast.LENGTH_SHORT).show();
            return;
        }
        ensureServiceAndTick();
        Toast.makeText(this, R.string.toast_sending_now, Toast.LENGTH_SHORT).show();
    }

    private void toggleDemo() {
        saveSettings();
        boolean next = !HaSettings.demoMode(this);
        HaSettings.setDemoMode(this, next);
        refreshDemoButton();
        refreshLocalPreview();
        if (next) {
            if (!HaSettings.isConfigured(this)) {
                Toast.makeText(this, R.string.toast_demo_need_config, Toast.LENGTH_LONG).show();
                return;
            }
            ensureServiceAndTick();
            Toast.makeText(this, R.string.toast_demo_on, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.toast_demo_off, Toast.LENGTH_SHORT).show();
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
        demoBtn.setText(HaSettings.demoMode(this) ? R.string.btn_demo_on : R.string.btn_demo);
    }

    private void refreshStatus() {
        startStopBtn.setText(BridgeStatus.running ? R.string.btn_service_stop : R.string.btn_service_start);
        refreshDemoButton();
        String last = BridgeStatus.lastSendAtMs == 0
                ? getString(R.string.em_dash)
                : new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(BridgeStatus.lastSendAtMs));
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.status_service,
                getString(BridgeStatus.running ? R.string.status_on : R.string.status_off))).append('\n');
        sb.append(getString(R.string.status_mode,
                getString(HaSettings.demoMode(this) ? R.string.status_mode_demo : R.string.status_mode_car))).append('\n');
        if (!HaSettings.demoMode(this)) {
            sb.append(getString(R.string.status_cpm,
                    getString(VehicleReader.isReady() ? R.string.status_ready : R.string.status_missing))).append('\n');
        }
        sb.append(getString(R.string.status_net,
                WifiHelper.describe(this),
                getString(WifiHelper.hasAnyInternet(this) ? R.string.status_yes : R.string.status_no),
                getString(WifiHelper.hasWifiInternet(this) ? R.string.status_yes : R.string.status_no))).append('\n');
        if (WifiHelper.isSim()) {
            sb.append(getString(R.string.status_sim_hint)).append('\n');
        }
        sb.append(getString(R.string.status_config,
                getString(HaSettings.isConfigured(this)
                        ? R.string.status_config_ok : R.string.status_config_missing))).append('\n');
        sb.append(getString(R.string.status_last_send, last));
        if (BridgeStatus.lastOkCount + BridgeStatus.lastFailCount > 0) {
            sb.append(getString(R.string.status_counts,
                    BridgeStatus.lastOkCount, BridgeStatus.lastFailCount));
        }
        sb.append('\n');
        sb.append(getString(R.string.status_line, BridgeStatus.lastMessage));
        statusView.setText(sb.toString());
        if (BridgeStatus.lastPreview != null && !BridgeStatus.lastPreview.isEmpty()) {
            previewView.setText(BridgeStatus.lastPreview);
        }
    }

    private void refreshLocalPreview() {
        VehicleSnapshot s = VehicleReader.read();
        previewView.setText(s.formatForScreen(this));
        if (!BridgeStatus.running) {
            BridgeStatus.carOk = s.carConnected;
            refreshStatus();
            previewView.setText(s.formatForScreen(this));
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, R.string.toast_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        CharSequence cs = clip == null || clip.getItemCount() == 0
                ? null : clip.getItemAt(0).coerceToText(this);
        String t = cs == null ? "" : cs.toString().trim();
        if (t.isEmpty()) {
            Toast.makeText(this, R.string.toast_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject cfg = HaSettings.parseConfig(t);
        if (HaSettings.applyJson(this, cfg)) {
            loadFromSettings();
            refreshStatus();
            Toast.makeText(this, R.string.toast_settings_from_clipboard, Toast.LENGTH_SHORT).show();
            return;
        }
        if (t.startsWith("http://") || t.startsWith("https://")) {
            urlView.setText(t);
            Toast.makeText(this, R.string.toast_url_pasted, Toast.LENGTH_SHORT).show();
            return;
        }
        tokenView.setText(t);
        Toast.makeText(this, R.string.toast_token_pasted, Toast.LENGTH_SHORT).show();
    }

    private void startFromWeb() {
        webServer.stop();
        TextView urlViewBig = new TextView(this);
        urlViewBig.setTextSize(20);
        urlViewBig.setGravity(Gravity.CENTER);
        urlViewBig.setTextColor(ContextCompat.getColor(this, R.color.accent));
        urlViewBig.setPadding(24, 24, 24, 8);
        urlViewBig.setTextIsSelectable(true);
        urlViewBig.setText(R.string.dialog_preparing_link);
        pairingDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_from_web_title)
                .setMessage(R.string.dialog_from_web_message)
                .setView(urlViewBig)
                .setNegativeButton(R.string.btn_cancel, (d, w) -> webServer.stop())
                .setCancelable(false)
                .create();
        pairingDialog.show();
        webServer.start(this, new ConfigWebServer.Listener() {
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
                applyIncoming(cfg, getString(R.string.toast_from_web_ok));
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
            Toast.makeText(this, R.string.toast_config_incomplete, Toast.LENGTH_LONG).show();
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
