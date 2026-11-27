package io.soartv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings > Sleep Timer: an alarm, then the remote's Sleep key through the TV's own ADB (the one the switch uses):
 * Android lets no other app put the TV to sleep.
 */
final class SleepTimer {
    private SleepTimer() {}

    static final String ACTION = HomeMode.PKG + ".SLEEP";
    private static final int STEP = 5, MOST = 240;
    /** Curated likely TV sleep durations (in minutes) before continuing in +15m increments. */
    static final int[] PRESET_MINUTES = {0, 10, 15, 20, 25, 30, 45, 60, 75, 90, 120};
    /** Step increment (in minutes) when extending beyond the top preset (120 min). */
    static final int EXTRA_STEP_MINUTES = 15;
    /** Quick fine-tune increment (in minutes) for adding extra time anytime. */
    static final int FINE_ADD_MINUTES = 5;
    /** Maximum configurable sleep timer duration (8 hours). */
    static final int MAX_MINUTES = 480;
    /** When the TV was last turned off, in uptimeMillis (dumpsys power). Leaving the screensaver isn't waking up. */
    private static final Pattern LAST_SLEEP = Pattern.compile("mLastSleepTime=(\\d+)");

    /**
     * Steps up from {@code currentMinutes} to the next curated sleep duration preset, or extends past
     * 120 minutes in 15-minute intervals up to {@link #MAX_MINUTES}.
     */
    static int nextDurationUp(int currentMinutes) {
        int cur = Math.max(0, Math.min(MAX_MINUTES, currentMinutes));
        for (int p : PRESET_MINUTES) {
            if (p > cur) return p;
        }
        int next = ((cur / EXTRA_STEP_MINUTES) + 1) * EXTRA_STEP_MINUTES;
        return Math.min(MAX_MINUTES, next);
    }

    /**
     * Steps down from {@code currentMinutes} to the previous curated sleep duration preset (or 15-minute
     * interval above 120 minutes), landing cleanly on {@code 0} (Off).
     */
    static int nextDurationDown(int currentMinutes) {
        int cur = Math.max(0, Math.min(MAX_MINUTES, currentMinutes));
        if (cur <= 0) return 0;
        if (cur > PRESET_MINUTES[PRESET_MINUTES.length - 1]) {
            int prev = ((cur - 1) / EXTRA_STEP_MINUTES) * EXTRA_STEP_MINUTES;
            return Math.max(PRESET_MINUTES[PRESET_MINUTES.length - 1], prev);
        }
        for (int i = PRESET_MINUTES.length - 1; i >= 0; i--) {
            if (PRESET_MINUTES[i] < cur) return PRESET_MINUTES[i];
        }
        return 0;
    }

    /**
     * Adds {@code deltaMinutes} to {@code currentMinutes}, clamped to {@code [0, MAX_MINUTES]}.
     */
    static int addMinutes(int currentMinutes, int deltaMinutes) {
        return Math.max(0, Math.min(MAX_MINUTES, currentMinutes + deltaMinutes));
    }

    /**
     * Pure headless-safe human-readable duration label ({@code "Off"}, {@code "15 min"}, {@code "1 h"},
     * {@code "1 h 30 min"}).
     */
    static String formatDurationLabel(int minutes) {
        if (minutes <= 0) return "Off";
        if (minutes < 60) return minutes + " min";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? (h + " h") : (h + " h " + m + " min");
    }

    /**
     * Formats the projected local clock time at which the TV will sleep (e.g. {@code "23:45"} or
     * {@code "11:45 PM"}), or {@code ""} when {@code minutes <= 0}.
     */
    static String formatSleepAtClock(int minutes, long nowEpochMs, boolean h24) {
        if (minutes <= 0) return "";
        long targetMs = nowEpochMs + minutes * 60_000L;
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                h24 ? "HH:mm" : "h:mm a", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getDefault());
        return fmt.format(new java.util.Date(targetMs));
    }

    private static PendingIntent alarm(Context c, long setAt, int flags) {
        Intent i = new Intent(ACTION).setClass(c, HomeReceiver.class).putExtra("setAt", setAt);
        return PendingIntent.getBroadcast(c, 0, i, flags | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Minutes left, rounded up; 0 when off. A one-shot alarm is gone once it went off or the TV restarted. */
    static int minutesLeft(Context c) {
        if (alarm(c, 0, PendingIntent.FLAG_NO_CREATE) == null) return 0;
        long left = Prefs.sleepAt() - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /**
     * OK and → go to the next multiple of 5 minutes after what's left (42 min left → 45), ← to the one before (→ 40);
     * past 4 hours or below 5 minutes it's off, and from off ← starts at 4 hours.
     */
    static void step(Context c, int dir) {
        int left = minutesLeft(c);
        int next = dir > 0 ? (left / STEP + 1) * STEP : left == 0 ? MOST : (left - 1) / STEP * STEP;
        set(c, next > MOST ? 0 : next);
    }

    /**
     * Original mode and the watchdog's revert (main process): the alarm and the pill go. Prefs aren't written here: that
     * process keeps the settings it read when it started, and writing would bring old ones back.
     */
    static void stop(Context c) {
        cancel(c);
        c.stopService(new Intent(c, SleepOverlay.class));
    }

    private static void cancel(Context c) {
        PendingIntent old = alarm(c, 0, PendingIntent.FLAG_NO_CREATE);
        if (old != null) {
            c.getSystemService(AlarmManager.class).cancel(old);
            old.cancel();
        }
    }

    private static boolean listening;

    /**
     * The TV turned off some other way (the remote, HDMI-CEC) while the timer ran: the timer ends there, as the alarm
     * would leave the TV on anyway, and the pill goes. Heard by the processes that run while it does (:home, :sleep).
     */
    static synchronized void listen(Context c) {
        if (listening) return;
        listening = true;
        c.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent i) {
                stop(context);
            }
        }, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    /**
     * The same when no process was there to hear it (Android stopped :home under another app, and the pill was off): when
     * :home starts again with a timer running, asks the TV whether it was turned off since, and whether the alarm is still
     * there (a Force stop takes it away), in :home.
     */
    static void check(Context c) {
        if (minutesLeft(c) == 0 || !Capabilities.adb(c)) return;   // the timer needs SoarTV mode: its key is known
        long from = Prefs.sleepFrom();   // 0: set by a build that didn't keep it
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                boolean ended = from > 0 && sleptSince(adb, from) || alarmGone(adb.shell("dumpsys alarm"));
                if (ended && Prefs.sleepFrom() == from) {   // not a new timer set meanwhile
                    AppLog.w("Sleep timer: the TV was turned off since, or the alarm is gone: timer ended");
                    stop(app);
                }
            } catch (IOException e) {
                AppLog.w("Sleep timer: " + e.getMessage());
            }
        }).start();
    }

    /** Whether `dumpsys alarm` lists no timer among the pending alarms (its history keeps the ones that went off). */
    private static boolean alarmGone(String dump) {
        int from = dump.indexOf("Pending alarm batches"), to = dump.indexOf("Pending alarms per uid", from);
        return from >= 0 && to > from && !dump.substring(from, to).contains(ACTION);
    }

    /** Whether the TV was turned off after `from` (uptimeMillis). Leaving the screensaver isn't waking up. */
    private static boolean sleptSince(Adb adb, long from) throws IOException {
        Matcher m = LAST_SLEEP.matcher(adb.shell("dumpsys power"));
        return m.find() && Long.parseLong(m.group(1)) > from;
    }

    /** 45 min, 1 h, 1 h 30 min: the timer's times, in the list and as they run down. */
    static String duration(int minutes) {
        return minutes < 60 ? Ui.trf("{n} min", "n", minutes) : minutes % 60 == 0 ? Ui.trf("{n} h", "n", minutes / 60)
                : Ui.trf("{h} h {m} min", "h", minutes / 60, "m", minutes % 60);
    }

    /** Starts the timer for `minutes` from now; 0 stops it (Settings, in :home). The pill follows (SleepOverlay.update). */
    static void set(Context c, int minutes) {
        cancel(c);
        long from = SystemClock.uptimeMillis();
        Prefs.setSleepAt(minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L, from);
        if (minutes > 0) {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            long at = SystemClock.elapsedRealtime() + minutes * 60_000L;
            PendingIntent alarm = alarm(c, from, 0);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarm);
            } catch (SecurityException e) {   // Android 12 with exact alarms turned off for it: up to 10 minutes late
                am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, 60_000, alarm);
            }
            AppLog.w("Sleep timer: " + minutes + " min");
        }
        SleepOverlay.update(c);
    }

    /** The alarm went off (main process): sleep, unless the TV is off, or was turned off since (and on again). */
    static void fire(Context c, long setAt) {
        if (!c.getSystemService(PowerManager.class).isInteractive()) return;
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            if (sleptSince(adb, setAt)) {
                AppLog.w("Sleep timer: the TV was turned off since, left on");
                return;
            }
            adb.shell("input keyevent KEYCODE_SLEEP");
            AppLog.w("Sleep timer: TV off");
        } catch (IOException e) {
            AppLog.w("Sleep timer: " + e.getMessage());
        }
    }
}
