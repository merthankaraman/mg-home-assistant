package com.drivehub.mgha.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import com.drivehub.mgha.BuildConfig;
import com.drivehub.mgha.R;
import com.drivehub.mgha.util.MghaLog;

/**
 * Ağ kontrolü. {@code sim} varyantında WiFi şart değil (hücresel / ethernet de olur).
 * {@code car} varyantında ayardaki “Sadece WiFi” geçerli.
 */
public final class WifiHelper {
    private static final String TAG = "MGHA_WIFI";

    private WifiHelper() {}

    public static boolean hasWifiInternet(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public static boolean hasAnyInternet(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** DNS/route oturmuş mu (API 23+). OEM hiç set etmezse false kalabilir. */
    public static boolean isValidated(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return false;
        if (Build.VERSION.SDK_INT < 23) {
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** Gönderim için yeterli ağ var mı? */
    public static boolean canSend(Context ctx, boolean wifiOnlySetting) {
        if (isSim()) {
            return hasAnyInternet(ctx);
        }
        if (wifiOnlySetting) {
            return hasWifiInternet(ctx);
        }
        return hasAnyInternet(ctx);
    }

    /**
     * Radyoyu açar (kayıtlı ağa bağlanması sisteme kalır).
     * System / platform imzalı uygulamada setWifiEnabled genelde çalışır.
     */
    @SuppressWarnings("deprecation")
    public static boolean ensureWifiEnabled(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                MghaLog.w(TAG, "WifiManager yok");
                return false;
            }
            if (wm.isWifiEnabled()) {
                MghaLog.i(TAG, "WiFi zaten açık");
                return true;
            }
            boolean ok = wm.setWifiEnabled(true);
            MghaLog.i(TAG, "setWifiEnabled(true) → " + ok);
            return ok;
        } catch (Throwable t) {
            MghaLog.e(TAG, "WiFi açılamadı: " + t.getMessage());
            return false;
        }
    }

    /** Flavor-dependent; IDE may warn "always true/false" for the active variant. */
    @SuppressWarnings("ConstantConditions")
    public static boolean isSim() {
        return "sim".equals(BuildConfig.FLAVOR);
    }

    public static String describe(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return ctx.getString(R.string.net_none);
        Network net = cm.getActiveNetwork();
        if (net == null) return ctx.getString(R.string.net_none);
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return ctx.getString(R.string.net_none);
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return ctx.getString(R.string.net_no_internet);
        }
        String base;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            base = ctx.getString(R.string.net_wifi);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            base = ctx.getString(R.string.net_cellular);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            base = ctx.getString(R.string.net_ethernet);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            base = ctx.getString(R.string.net_vpn);
        } else {
            base = ctx.getString(R.string.net_other);
        }
        if (isValidated(ctx)) return base + "+ok";
        return base;
    }
}
