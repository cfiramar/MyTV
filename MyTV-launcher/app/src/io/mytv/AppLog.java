package io.mytv;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/** The app's log (adb logcat -s MyTV). Never logs keys. */
final class AppLog {
    private static final String TAG = "MyTV";

    private AppLog() {}

    static void i(String message) {
        try {
            Log.i(TAG, message == null ? "" : message);
        } catch (Throwable ignored) {
        }
    }

    static void w(String message) {
        try {
            Log.w(TAG, message == null ? "" : message);
        } catch (Throwable ignored) {
        }
    }

    static void w(String message, Throwable t) {
        String formatted = formatMessageWithStackTrace(message, t);
        try {
            Log.w(TAG, formatted);
        } catch (Throwable ignored) {
        }
    }

    static void e(String message) {
        try {
            Log.e(TAG, message == null ? "" : message);
        } catch (Throwable ignored) {
        }
    }

    static void e(String message, Throwable t) {
        String formatted = formatMessageWithStackTrace(message, t);
        try {
            Log.e(TAG, formatted);
        } catch (Throwable ignored) {
        }
    }

    /** Formats message + Throwable stack trace safely on both Android and headless JVM tests. */
    static String formatMessageWithStackTrace(String message, Throwable t) {
        String base = message == null ? "" : message;
        if (t == null) return base;
        StringWriter sw = new StringWriter(256);
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        String trace = sw.toString().trim();
        return base.isEmpty() ? trace : (base + "\n" + trace);
    }
}
