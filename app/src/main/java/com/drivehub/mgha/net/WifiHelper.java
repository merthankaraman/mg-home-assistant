package com.drivehub.mgha.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.drivehub.mgha.BuildConfig;

/**
 * Ağ kontrolü. {@code sim} varyantında WiFi şart değil (hücresel / ethernet de olur).
 * {@code car} varyantında ayardaki “Sadece WiFi” geçerli.
 */
public final class WifiHelper {
    private WifiHelper() {}

    public static boolean hasWifiInternet(Context ctx) {
        return hasTransportInternet(ctx, NetworkCapabilities.TRANSPORT_WIFI);
    }

    public static boolean hasEthernetInternet(Context ctx) {
        return hasTransportInternet(ctx, NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    public static boolean hasAnyInternet(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
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

    public static boolean isSim() {
        return "sim".equals(BuildConfig.FLAVOR);
    }

    public static String describe(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "yok";
        Network net = cm.getActiveNetwork();
        if (net == null) return "yok";
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return "yok";
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "internet yok";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "hücresel";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        return "diğer";
    }

    private static boolean hasTransportInternet(Context ctx, int transport) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return false;
        return caps.hasTransport(transport)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
