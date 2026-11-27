package io.soartv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * Multi-brand TV remote shortcut button router (invoked from {@link RemoteKeyService} and {@link HomeActivity}).
 *
 * <p>Supports TCL, Xiaomi (PatchWall, MiTV-M, MiTV-AXSO2, MiTV-AFKR0, Formovie), Sony BRAVIA (including VU31 and
 * BRAVIA 8 II {@code VIDEO_APP_1..8}), Hisense (including {@code KEYCODE_TV_INPUT} fallback when the stock input
 * panel is disabled), Philips Google TV (TP Vision), Skyworth / JVC G24, Vestel, KTC / NextStar, Chromecast with
 * Google TV (Sabrina), Google TV Streamer, and Walmart onn 4K Pro remotes, plus universal {@code KEYCODE_SETTINGS}
 * routing to SoarTV's Settings screen and double-Volume-Down mute.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;
    /** KeyEvent.KEYCODE_VIDEO_APP_1 to _8 (API 28): numbers here, since minSdk is 26. */
    private static final int KEYCODE_VIDEO_APP_1 = 289, KEYCODE_VIDEO_APP_8 = 296;

    private static final String MODEL = Build.MODEL != null ? Build.MODEL : "";
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"), FORMOVIE = is("Formovie"),
            HISENSE = is("Hisense");
    static final boolean CHROMECAST_SABRINA = SabrinaRemote.supported(Build.DEVICE, MODEL);
    static final boolean GOOGLE_STREAMER = is("Google") && MODEL.equals("Google TV Streamer");
    static final boolean ONN = is("onn");
    private static final int ONN_REMOTE_VENDOR = 0x0957, ONN_REMOTE_PRODUCT = 0x0031;
    static final boolean JVC_SKYWORTH = SkyworthRemote.supported(Build.MANUFACTURER, Build.BRAND,
            MODEL, Build.VERSION.SDK_INT);
    static final boolean XIAOMI_PATCHWALL_ONLY = is("Xiaomi") && MODEL.equals("MiTV-AXSO2");
    static final boolean XIAOMI = is("Xiaomi") && !FORMOVIE && (MODEL.startsWith("MiTV-M") || XIAOMI_PATCHWALL_ONLY);
    static final boolean XIAOMI_AFKR0 = is("Xiaomi") && MODEL.equals("MiTV-AFKR0");
    static final boolean PHILIPS_GTV = (is("Philips") || is("TPV") || MODEL.startsWith("Philips"))
            && Build.VERSION.SDK_INT >= 33;
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;
    /**
     * With a key filter on, TCL's Android 11 changes the volume twice for one press, and a held key gets stuck.
     * There the service stays off: the buttons work on our home screen, whose window gets them.
     */
    static final boolean FILTER_SAFE = !(TCL && Build.VERSION.SDK_INT == Build.VERSION_CODES.R);

    private static final long BURST_MS = 1000;
    private static final long DOUBLE_VOL_DOWN_MS = 420;
    private static final String VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    private static int lastKey;
    private static long lastDown;
    private static long lastVolDownAt;

    static boolean supported() {
        return TCL || STEM_NETFLIX || KTC || SONY || XIAOMI || XIAOMI_AFKR0 || HISENSE || CHROMECAST_SABRINA
                || GOOGLE_STREAMER || ONN || JVC_SKYWORTH || PHILIPS_GTV || Build.VERSION.SDK_INT >= 34;
    }

    static boolean serviceWanted() {
        if (!FILTER_SAFE) return false;
        if (HISENSE || XIAOMI || Build.VERSION.SDK_INT >= 34) return true;
        if (!supported()) return false;
        for (int key : Prefs.KEYS) if (!Prefs.keyApp(key).isEmpty()) return true;
        return false;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /**
     * Checks if Volume Down was pressed twice fast (within 420 ms) and toggles system Mute if so.
     * Returns true when the second press triggered Mute.
     */
    static boolean checkDoubleVolDownMute(Context c, KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastVolDownAt <= DOUBLE_VOL_DOWN_MS) {
                lastVolDownAt = 0;
                try {
                    android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.ADJUST_TOGGLE_MUTE,
                                android.media.AudioManager.FLAG_SHOW_UI);
                    }
                } catch (RuntimeException ignored) {
                }
                return true;
            }
            lastVolDownAt = now;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code keyCode} is a TV remote Settings / gear key that should open
     * SoarTV's Settings tab and never be discarded by gamepad/joystick source filtering.
     */
    static boolean isSettingsButtonKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_NOTIFICATION
                || (PHILIPS_GTV && keyCode == Prefs.KEY_PHILIPS_SETTINGS);
    }

    /**
     * Determines whether a key event originates from a physical game controller rather than a TV remote.
     */
    static boolean shouldIgnoreControllerSource(int keyCode, int source) {
        if (isSettingsButtonKeyCode(keyCode)) {
            return false;
        }
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            return true;
        }
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && (source & InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD;
    }

    static Intent buildSettingsTabIntent(Context c) {
        return new Intent(c, HomeActivity.class)
                .putExtra(HomeActivity.EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static boolean onKey(Context c, KeyEvent e) {
        if (checkDoubleVolDownMute(c, e)) return true;
        int rawKeyCode = e.getKeyCode();
        if (!supported() && rawKeyCode != KeyEvent.KEYCODE_SETTINGS) return false;
        if (shouldIgnoreControllerSource(rawKeyCode, e.getSource())) return false;
        if (CHROMECAST_SABRINA && rawKeyCode != KeyEvent.KEYCODE_SETTINGS) {
            InputDevice device = InputDevice.getDevice(e.getDeviceId());
            if (device == null || !SabrinaRemote.isRemote(device.getVendorId(), device.getProductId())) return false;
        }
        if (HISENSE && rawKeyCode == KeyEvent.KEYCODE_TV_INPUT && inputsUnanswered(c)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                AppLog.w("remote key " + rawKeyCode + " -> inputs");
                Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .putExtra(HomeActivity.EXTRA_INPUTS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(intent);
                } catch (android.content.ActivityNotFoundException | SecurityException ex) {
                    AppLog.w("remote target unavailable: inputs");
                }
            }
            return true;
        }
        int keyCode = TCL && rawKeyCode == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : rawKeyCode;
        if (JVC_SKYWORTH) {
            if (SkyworthRemote.settings(keyCode)) keyCode = Prefs.KEY_JVC_SETTINGS;
            else if (SkyworthRemote.shortcut(keyCode, e.getScanCode())) keyCode = Prefs.KEY_JVC_SHORTCUT;
            else if (keyCode != KeyEvent.KEYCODE_SETTINGS) return false;
        }
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9 || keyCode == KeyEvent.KEYCODE_BUTTON_2
                || keyCode == KeyEvent.KEYCODE_BUTTON_15
                || (keyCode >= KEYCODE_VIDEO_APP_1 && keyCode <= KEYCODE_VIDEO_APP_8))) {
            keyCode = Prefs.sonyKey(e.getScanCode());
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        String pkg;
        if (ours) {
            if (ONN && !onnRemote(e)) return false;
            pkg = Prefs.keyApp(keyCode);
        } else if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            pkg = Prefs.SETTINGS_TAB;
        } else {
            return false;
        }
        if (pkg.isEmpty()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            boolean burst = XIAOMI && keyCode == lastKey && e.getEventTime() - lastDown < BURST_MS;
            lastKey = keyCode;
            lastDown = e.getEventTime();
            if (burst) return true;
            if (pkg.equals(Prefs.SETTINGS_TAB) && c instanceof HomeActivity) {
                ((HomeActivity) c).openSettingsTabFromRemote();
                return true;
            }
            Intent intent = pkg.equals(Prefs.SETTINGS_TAB)
                    ? buildSettingsTabIntent(c)
                    : pkg.equals(HomeMode.PKG) || pkg.equals(Prefs.ALL_APPS)
                    ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    : JVC_SKYWORTH && pkg.equals(Prefs.QUICK_SETTINGS)
                    ? new Intent(c, QuickSettingsActivity.class)
                    : JVC_SKYWORTH && pkg.equals(Prefs.TV_SHORTCUTS)
                    ? new Intent(Intent.ACTION_MAIN).setClassName("com.smartdevice.dailyshortcut",
                            "com.smartdevice.dailyshortcut.ui.MainActivity")
                    : (TCL || PHILIPS_GTV) && pkg.equals(Prefs.QUICK_PANEL)
                    ? new Intent(c, QuickPanelActivity.class)
                    : (JVC_SKYWORTH || TCL || PHILIPS_GTV || SONY || XIAOMI || XIAOMI_AFKR0 || HISENSE)
                            && pkg.equals("com.android.tv.settings")
                    ? new Intent(android.provider.Settings.ACTION_SETTINGS).setPackage(pkg)
                    : launchIntent(c.getPackageManager(), pkg);
            if (pkg.equals(Prefs.ALL_APPS)) intent.putExtra(HomeActivity.EXTRA_ALL_APPS, true);
            AppLog.w("remote key " + rawKeyCode + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            if (intent != null) {
                try {
                    c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (android.content.ActivityNotFoundException | SecurityException ex) {
                    AppLog.w("remote target unavailable: " + pkg);
                }
            }
        }
        return true;
    }

    private static boolean inputsUnanswered(Context c) {
        return c.getPackageManager().resolveActivity(new Intent(VIEW_INPUTS), 0) == null;
    }

    private static boolean onnRemote(KeyEvent e) {
        InputDevice device = InputDevice.getDevice(e.getDeviceId());
        return device != null && device.getVendorId() == ONN_REMOTE_VENDOR && device.getProductId() == ONN_REMOTE_PRODUCT;
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
