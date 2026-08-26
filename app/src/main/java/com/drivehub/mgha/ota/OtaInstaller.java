package com.drivehub.mgha.ota;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * İndirilen APK'yı sistem kurulum ekranı ile başlatır.
 * Platform imzalı uygulamada genelde sorunsuz; aksi halde kullanıcı onayı ister.
 */
final class OtaInstaller {

    private static final String TAG = "MGHA_OTA";

    private OtaInstaller() {
    }

    static void install(Context context, long downloadId, Uri apkUri) throws Exception {
        Uri contentUri = toInstallUri(context, downloadId, apkUri);
        if (contentUri == null) {
            throw new IllegalStateException("APK URI missing");
        }

        if (Build.VERSION.SDK_INT >= 26
                && !context.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            settings.setData(Uri.parse("package:" + context.getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settings);
            throw new IllegalStateException("unknown sources permission required");
        }

        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(contentUri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (install.resolveActivity(context.getPackageManager()) == null) {
            throw new IllegalStateException("No package installer activity");
        }
        context.startActivity(install);
        Log.i(TAG, "install started uri=" + contentUri);
    }

    /** Kurulum sırasında korumak için dosya yolu. */
    static File resolveApkFilePublic(Context context, long downloadId, Uri apkUri) {
        return resolveApkFile(context, downloadId, apkUri);
    }

    private static Uri toInstallUri(Context context, long downloadId, Uri apkUri) throws Exception {
        File file = resolveApkFile(context, downloadId, apkUri);
        if (file != null && file.exists()) {
            return FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", file);
        }
        if (apkUri != null) {
            String scheme = apkUri.getScheme();
            if ("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
                if ("file".equalsIgnoreCase(scheme)) {
                    File f = new File(apkUri.getPath());
                    if (f.exists()) {
                        return FileProvider.getUriForFile(
                                context, context.getPackageName() + ".fileprovider", f);
                    }
                }
                return apkUri;
            }
        }
        return null;
    }

    private static File resolveApkFile(Context context, long downloadId, Uri apkUri) {
        try {
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null && downloadId > 0L) {
                try (Cursor c = dm.query(new android.app.DownloadManager.Query().setFilterById(downloadId))) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                        if (idx >= 0) {
                            String local = c.getString(idx);
                            if (local != null && local.startsWith("file:")) {
                                File f = new File(Uri.parse(local).getPath());
                                if (f.exists()) return f;
                            }
                        }
                        int pathIdx = c.getColumnIndex("local_filename");
                        if (pathIdx >= 0) {
                            String path = c.getString(pathIdx);
                            if (path != null) {
                                File f = new File(path);
                                if (f.exists()) return f;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolve download file: " + t.getMessage());
        }
        if (apkUri != null && "file".equalsIgnoreCase(apkUri.getScheme())) {
            File f = new File(apkUri.getPath());
            if (f.exists()) return f;
        }
        return null;
    }
}
