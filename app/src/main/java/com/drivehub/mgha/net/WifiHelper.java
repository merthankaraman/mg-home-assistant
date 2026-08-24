package com.drivehub.mgha.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.drivehub.mgha.BuildConfig;
import com.drivehub.mgha.R;

/**
 * Ağ kontrolü. {@code sim} varyantında WiFi şart değil (hücresel / ethernet de olur).
 * {@code car} varyantında ayardaki “Sadece WiFi” geçerli.
 */
public final class WifiHelper {
    private WifiHelper() {}

    public static boolean hasWifiInternet(Context ctx) {
        return hasTransportInternet(ctx, NetworkCapabilities.TRANSPORT_WIFI);
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
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return ctx.getString(R.string.net_wifi);
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return ctx.getString(R.string.net_cellular);
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return ctx.getString(R.string.net_ethernet);
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return ctx.getString(R.string.net_vpn);
        }
        return ctx.getString(R.string.net_other);
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
