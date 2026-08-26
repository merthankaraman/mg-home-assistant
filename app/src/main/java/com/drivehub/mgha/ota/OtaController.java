package com.drivehub.mgha.ota;

import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.drivehub.mgha.R;

import java.io.File;
import java.util.Locale;

/**
 * GitHub Releases üzerinden OTA: kontrol → indir → SHA-256 → kurulum ekranı.
 */
public final class OtaController {

    private static final String PREFS_NAME = "mgha_ota";
    private static final String KEY_ALLOW_BETA = "allowBetaUpdates";

    private final AppCompatActivity activity;
    private TextView statusView;
    private Button checkButton;
    private CheckBox betaCheck;

    private OtaUpdateManager.UpdateInfo lastCheckInfo;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private Dialog progressDialog;

    private enum VerificationState { IDLE, IN_FLIGHT, PASSED, FAILED }

    private long verificationDownloadId = -1L;
    private VerificationState verificationState = VerificationState.IDLE;
    private OtaUpdateManager.UpdateInfo activeDownloadInfo;

    public OtaController(AppCompatActivity activity) {
        this.activity = activity;
    }

    public void setup(Button checkButton, TextView statusView, CheckBox betaCheck) {
        this.checkButton = checkButton;
        this.statusView = statusView;
        this.betaCheck = betaCheck;

        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, 0);
        if (betaCheck != null) {
            betaCheck.setChecked(prefs.getBoolean(KEY_ALLOW_BETA, false));
            betaCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean(KEY_ALLOW_BETA, isChecked).apply();
                lastCheckInfo = null;
                renderStatus(null, true);
                OtaUpdateManager.checkForUpdates(activity, isChecked, info -> {
                    lastCheckInfo = info;
                    renderStatus(info, false);
                });
            });
        }

        renderStatus(null, false);
        if (checkButton != null) {
            checkButton.setOnClickListener(v -> onCheckClicked());
        }
    }

    /** Açılışta sessiz kontrol; güncelleme varsa toast. */
    public void checkOnStartup() {
        boolean allowBeta = allowBeta();
        OtaUpdateManager.checkForUpdates(activity, allowBeta, info -> {
            lastCheckInfo = info;
            renderStatus(info, false);
            if (info != null && info.success && info.updateAvailable) {
                Toast.makeText(
                        activity,
                        activity.getString(R.string.ota_status_update_available, info.latestVersion),
                        Toast.LENGTH_LONG
                ).show();
            }
        });
    }

    public void stop() {
        stopProgressWatcher();
    }

    private void onCheckClicked() {
        if (lastCheckInfo != null && lastCheckInfo.success && lastCheckInfo.updateAvailable) {
            maybeStartDownload(lastCheckInfo);
            return;
        }
        triggerCheck(true);
    }

    private void triggerCheck(boolean showToast) {
        if (showToast) {
            Toast.makeText(activity, R.string.ota_toast_checking, Toast.LENGTH_SHORT).show();
        }
        renderStatus(null, true);
        OtaUpdateManager.checkForUpdates(activity, allowBeta(), info -> {
            lastCheckInfo = info;
            renderStatus(info, false);
            if (info != null && info.success && info.updateAvailable) {
                maybeStartDownload(info);
            } else if (info != null && info.success) {
                OtaDialogs.showMessageDialog(activity,
                        activity.getString(R.string.ota_dialog_up_to_date_message));
            } else {
                OtaDialogs.showMessageDialog(activity,
                        activity.getString(R.string.ota_dialog_check_failed_message));
            }
        });
    }

    private boolean allowBeta() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, 0);
        if (betaCheck != null) return betaCheck.isChecked();
        return prefs.getBoolean(KEY_ALLOW_BETA, false);
    }

    private void maybeStartDownload(OtaUpdateManager.UpdateInfo info) {
        if (info == null || info.expectedSha256 == null || info.expectedSha256.trim().isEmpty()) {
            OtaDialogs.showMessageDialog(
                    activity,
                    info != null && info.message != null && !info.message.trim().isEmpty()
                            ? info.message
                            : activity.getString(R.string.ota_error_no_hash)
            );
            return;
        }
        OtaDialogs.showConfirmDialog(
                activity,
                activity.getString(R.string.ota_dialog_mobile_warning_message, info.latestVersion),
                activity.getString(R.string.ota_action_download),
                () -> startDownload(info)
        );
    }

    private void startDownload(OtaUpdateManager.UpdateInfo info) {
        try {
            stopProgressWatcher();
            long downloadId = OtaUpdateManager.enqueueDownload(activity, info);
            activeDownloadInfo = info;
            showProgressDialog(info, downloadId);
        } catch (Throwable t) {
            OtaDialogs.showMessageDialog(
                    activity,
                    activity.getString(R.string.ota_dialog_download_failed_message, t.getClass().getSimpleName())
            );
        }
    }

    private void showProgressDialog(OtaUpdateManager.UpdateInfo info, long downloadId) {
        OtaDialogs.ProgressDialogHandle handle = OtaDialogs.showProgressDialog(
                activity,
                activity.getString(R.string.ota_dialog_download_started_message, info.latestVersion),
                () -> retryDownload(downloadId),
                () -> installDownloadedApk(downloadId)
        );
        progressDialog = handle.dialog;
        progressDialog.setOnDismissListener(d -> stopProgressWatcher());
        verificationDownloadId = -1L;
        verificationState = VerificationState.IDLE;

        final TextView progressStatus = handle.statusView;
        final ProgressBar progressBar = handle.progressBar;
        final View retryButton = handle.retryButton;
        final View installButton = handle.installButton;
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                boolean cont = tickProgress(downloadId, progressBar, progressStatus, retryButton, installButton);
                if (cont && progressDialog != null && progressDialog.isShowing()) {
                    progressHandler.postDelayed(this, 500L);
                }
            }
        };
        progressHandler.post(progressRunnable);
    }

    private boolean tickProgress(long downloadId, ProgressBar progressBar, TextView statusView,
                                 View retryButton, View installButton) {
        DownloadManager dm = activity.getSystemService(DownloadManager.class);
        if (dm == null) {
            if (statusView != null) statusView.setText(R.string.ota_progress_unavailable);
            return false;
        }

        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) {
                if (statusView != null) statusView.setText(R.string.ota_progress_missing);
                if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
                return false;
            }

            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int pct = (total > 0L) ? (int) ((downloaded * 100L) / total) : 0;
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));

            if (progressBar != null) {
                if (total > 0L) {
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(100);
                    progressBar.setProgress(Math.max(0, Math.min(100, pct)));
                } else {
                    progressBar.setIndeterminate(true);
                }
            }
            if (statusView != null && status != DownloadManager.STATUS_SUCCESSFUL) {
                statusView.setText(progressStatusText(status, pct, downloaded, total, reason));
            }

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                if (retryButton != null) retryButton.setVisibility(View.GONE);
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(100);
                    progressBar.setProgress(100);
                }
                if (verificationState == VerificationState.PASSED || verificationState == VerificationState.FAILED) {
                    if (installButton != null) {
                        installButton.setVisibility(
                                verificationState == VerificationState.PASSED ? View.VISIBLE : View.GONE);
                    }
                    return false;
                }
                if (installButton != null) installButton.setVisibility(View.GONE);
                if (verificationState != VerificationState.IN_FLIGHT || verificationDownloadId != downloadId) {
                    startVerification(downloadId, statusView, installButton);
                } else if (statusView != null) {
                    statusView.setText(R.string.ota_progress_verifying);
                }
                return true;
            }

            if (retryButton != null) {
                retryButton.setVisibility(status == DownloadManager.STATUS_FAILED ? View.VISIBLE : View.GONE);
            }
            if (installButton != null) installButton.setVisibility(View.GONE);
            return status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED;

        } catch (Throwable t) {
            if (statusView != null) {
                statusView.setText(activity.getString(R.string.ota_progress_failed_reason, t.getClass().getSimpleName()));
            }
            if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
            return false;
        }
    }

    private void startVerification(long downloadId, TextView statusView, View installButton) {
        if (verificationState == VerificationState.IN_FLIGHT && verificationDownloadId == downloadId) return;
        verificationDownloadId = downloadId;
        verificationState = VerificationState.IN_FLIGHT;
        if (installButton != null) installButton.setVisibility(View.GONE);
        if (statusView != null) statusView.setText(R.string.ota_progress_verifying);

        Uri apkUri;
        try {
            apkUri = resolveDownloadedApkUri(downloadId);
        } catch (Exception e) {
            verificationState = VerificationState.FAILED;
            if (statusView != null) {
                statusView.setText(activity.getString(
                        R.string.ota_progress_integrity_failed, e.getClass().getSimpleName()));
            }
            return;
        }

        OtaUpdateManager.verifyDownloadedApk(activity, downloadId, apkUri, activeDownloadInfo,
                (success, computedSha256, message) -> {
                    if (downloadId != verificationDownloadId) return;
                    verificationState = success ? VerificationState.PASSED : VerificationState.FAILED;
                    if (statusView != null) {
                        if (success) {
                            statusView.setText(R.string.ota_progress_verified);
                        } else {
                            statusView.setText(activity.getString(R.string.ota_progress_integrity_failed, message));
                        }
                    }
                    if (installButton != null) {
                        installButton.setVisibility(success ? View.VISIBLE : View.GONE);
                    }
                    if (success) {
                        // Doğrulama OK → kurulum ekranını aç
                        installDownloadedApk(downloadId);
                    }
                });
    }

    private void installDownloadedApk(long downloadId) {
        try {
            Uri apkUri = resolveDownloadedApkUri(downloadId);
            OtaInstaller.install(activity, downloadId, apkUri);
            // Kurulum ekranı açıldı; diğer eski APK’ları sil (kurulan dosyayı koru)
            File keep = OtaInstaller.resolveApkFilePublic(activity, downloadId, apkUri);
            OtaCleanup.deleteOldApks(keep);
        } catch (Exception e) {
            Log.w("MGHA_OTA", "install failed, opening downloads: " + e.getMessage());
            OtaDialogs.showMessageDialog(
                    activity,
                    activity.getString(R.string.ota_dialog_install_failed_message,
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
            );
            openDownloadsFolder();
        }
    }

    private void openDownloadsFolder() {
        try {
            Intent downloadsIntent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            downloadsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.PackageManager pm = activity.getPackageManager();
            if (pm != null && downloadsIntent.resolveActivity(pm) != null) {
                activity.startActivity(downloadsIntent);
                return;
            }
            throw new IllegalStateException("Downloads app not available");
        } catch (Exception e) {
            OtaDialogs.showMessageDialog(
                    activity,
                    activity.getString(R.string.ota_dialog_open_downloads_failed_message, e.getClass().getSimpleName())
            );
        }
    }

    private Uri resolveDownloadedApkUri(long downloadId) throws Exception {
        DownloadManager dm = activity.getSystemService(DownloadManager.class);
        if (dm == null) return null;
        Uri downloadedUri = dm.getUriForDownloadedFile(downloadId);
        if (downloadedUri != null) return downloadedUri;
        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            if (localUri == null || localUri.isEmpty()) return null;
            return Uri.parse(localUri);
        }
    }

    private void retryDownload(long failedDownloadId) {
        OtaUpdateManager.UpdateInfo info = activeDownloadInfo;
        cleanupFailedDownload(failedDownloadId);
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        if (info != null) {
            startDownload(info);
        } else {
            OtaDialogs.showMessageDialog(activity, activity.getString(R.string.ota_status_check_failed));
        }
    }

    private void cleanupFailedDownload(long downloadId) {
        if (downloadId <= 0L) return;
        try {
            DownloadManager dm = activity.getSystemService(DownloadManager.class);
            if (dm != null) dm.remove(downloadId);
        } catch (Throwable ignored) {
        }
    }

    private void stopProgressWatcher() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
        verificationDownloadId = -1L;
        verificationState = VerificationState.IDLE;
        activeDownloadInfo = null;
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }

    private void renderStatus(OtaUpdateManager.UpdateInfo info, boolean checking) {
        if (statusView == null) return;
        if (checking) {
            statusView.setText(R.string.ota_status_checking);
            statusView.setTextColor(ContextCompat.getColor(activity, R.color.muted));
            return;
        }
        if (info != null && info.success && info.updateAvailable) {
            statusView.setText(activity.getString(R.string.ota_status_update_available, info.latestVersion));
            statusView.setTextColor(ContextCompat.getColor(activity, R.color.warn));
            if (checkButton != null) {
                checkButton.setText(R.string.btn_ota_download);
            }
            return;
        }
        if (info != null && info.success) {
            statusView.setText(R.string.ota_status_up_to_date);
            statusView.setTextColor(ContextCompat.getColor(activity, R.color.ok));
            if (checkButton != null) {
                checkButton.setText(R.string.btn_ota_check);
            }
            return;
        }
        if (info != null) {
            statusView.setText(activity.getString(R.string.ota_status_check_failed_detail,
                    info.message != null ? info.message : ""));
        } else {
            statusView.setText(R.string.ota_status_idle);
        }
        statusView.setTextColor(ContextCompat.getColor(activity, R.color.muted));
        if (checkButton != null) {
            checkButton.setText(R.string.btn_ota_check);
        }
    }

    private CharSequence progressStatusText(int status, int pct, long downloaded, long total, int reason) {
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            return activity.getString(R.string.ota_progress_complete);
        }
        if (status == DownloadManager.STATUS_FAILED) {
            return activity.getString(R.string.ota_progress_failed_reason, "reason " + reason);
        }
        if (status == DownloadManager.STATUS_PAUSED) {
            return activity.getString(R.string.ota_progress_paused, pct);
        }
        if (status == DownloadManager.STATUS_PENDING) {
            return activity.getString(R.string.ota_progress_pending);
        }
        if (total > 0L) {
            return activity.getString(R.string.ota_progress_downloading,
                    pct, formatBytes(downloaded), formatBytes(total));
        }
        return activity.getString(R.string.ota_progress_running_unknown, formatBytes(downloaded));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
