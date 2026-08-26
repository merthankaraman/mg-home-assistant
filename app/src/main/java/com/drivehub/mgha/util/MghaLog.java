package com.drivehub.mgha.util;

import android.content.Context;
import android.util.Log;

import com.drivehub.mgha.prefs.HaSettings;

/** Ayrıntılı log kapalıysa Info/Warn basılmaz; Error her zaman gider. */
public final class MghaLog {
    private static volatile boolean sVerbose;

    private MghaLog() {}

    public static void refresh(Context ctx) {
        if (ctx == null) return;
        sVerbose = HaSettings.verboseLog(ctx);
    }

    public static boolean isVerbose() {
        return sVerbose;
    }

    public static void i(String tag, String msg) {
        if (sVerbose) Log.i(tag, msg);
    }

    public static void w(String tag, String msg) {
        if (sVerbose) Log.w(tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
    }
}
