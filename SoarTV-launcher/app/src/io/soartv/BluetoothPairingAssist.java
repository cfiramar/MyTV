package io.soartv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Solves the Xiaomi Android 14 BluetoothPairingDialog D-pad focus & confirmation timeout bug.
 *
 * <p>On Xiaomi Android 14 ({@code MiGTVSettingsTwoPanel.apk}: {@code BluetoothPairingDialog},
 * {@code ConfirmationDialogFragment}, {@code ActionFragment}, {@code ActionAdapter}):
 * <ol>
 *   <li>Window root {@link AccessibilityNodeInfo} uses {@code FrameLayout} and generic TwoPanel IDs
 *       ({@code content_frame}, {@code action_fragment}, {@code list}) with zero {@code "pairing"} view IDs,
 *       and prompt strings such as {@code "Pair with Wireless Controller?"} without the word {@code "Bluetooth"}.</li>
 *   <li>{@code ActionFragment} inflates its {@code GridView} action rows asynchronously ~50-250ms after
 *       {@code TYPE_WINDOW_STATE_CHANGED} without setting initial D-pad focus on {@code "Pair"}.</li>
 *   <li>When pairing is initiated from inside SoarTV's in-app scanner ({@link BluetoothControllerPairer}),
 *       a short-lived cross-process stamp ({@code pending_bt_pair.txt}, readable from both {@code :home}
 *       and {@code :keys}) allows {@code BluetoothPairingAssist} to auto-confirm {@code "Pair"} immediately
 *       so pairing completes inside SoarTV without a 10s {@code hciReason: 14} timeout.</li>
 * </ol></p>
 */
public final class BluetoothPairingAssist {

    public static final String SETTINGS_PKG = "com.android.tv.settings";
    public static final String PAIRING_DIALOG_CLS = "BluetoothPairingDialog";

    /** Maximum age (ms) for a SoarTV-initiated pending Bluetooth bond auto-confirm stamp. */
    public static final long PENDING_PAIR_TTL_MS = 25_000L;
    private static final String PENDING_PAIR_FILENAME = "pending_bt_pair.txt";

    private static final long[] RETRY_DELAYS_MS = new long[] {80L, 200L, 450L, 850L, 1400L};
    private static final long FOCUS_RETRY_INTERVAL_MS = 120L;
    private static final long CONTENT_CHECK_INTERVAL_MS = 120L;
    private static final long PENDING_STAMP_CACHE_MS = 250L;

    /**
     * Dedicated daemon thread for all {@code getRootInActiveWindow()} / {@code getWindows()} Binder IPC
     * so {@link RemoteKeyService}'s main looper ({@code onKeyEvent}) never stalls while navigating Native Settings.
     */
    private final ExecutorService inspectExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "soartv-bt-pair-assist");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inspectScheduled = new AtomicBoolean(false);

    private volatile boolean dialogVisible;
    private volatile boolean pendingUnfocusedPairAction;
    private volatile long dialogVisibleUntilMs;
    private volatile boolean knownPairingActivityWindow;
    private boolean consumedConfirmKeyDown;
    private long lastFocusAttemptMs;
    private long lastContentCheckMs;
    private long lastStampCheckMs;
    private boolean cachedFreshPendingStamp;
    private Handler retryHandler;

    public BluetoothPairingAssist() {}

    /**
     * Returns {@code true} only when a system Bluetooth pairing dialog is currently visible AND its {@code Pair}
     * button still lacks input focus, allowing {@link #onKeyEvent} to exit in {@code O(1)} with zero Binder IPC
     * on 99.99% of key presses.
     */
    public boolean isPendingUnfocusedPairAction() {
        return pendingUnfocusedPairAction;
    }

    /**
     * Formats a cross-process pending Bluetooth pair stamp (`timestamp|mac|name`) written by {@code :home} and read by {@code :keys}.
     */
    public static String formatPendingPairStamp(long timestampMs, String address, String name) {
        String safeAddr = address != null ? address.trim().replace('\n', ' ').replace('\r', ' ').replace('|', ' ') : "";
        String safeName = name != null ? name.trim().replace('\n', ' ').replace('\r', ' ').replace('|', ' ') : "";
        return timestampMs + "|" + safeAddr + "|" + safeName;
    }

    private static String[] splitPendingPairStamp(String stampContent) {
        if (stampContent == null || stampContent.trim().isEmpty()) return new String[] {"", "", ""};
        String trimmed = stampContent.trim();
        if (trimmed.indexOf('|') >= 0) {
            String[] p = trimmed.split("\\|", -1);
            return new String[] {
                    p.length > 0 ? p[0].trim() : "",
                    p.length > 1 ? p[1].trim() : "",
                    p.length > 2 ? p[2].trim() : ""
            };
        }
        String[] lines = stampContent.split("\\r?\\n", -1);
        return new String[] {
                lines.length > 0 ? lines[0].trim() : "",
                lines.length > 1 ? lines[1].trim() : "",
                lines.length > 2 ? lines[2].trim() : ""
        };
    }

    /**
     * Returns true when a pending pair stamp is well-formed and within {@code maxAgeMs} of {@code nowMs}.
     */
    public static boolean isPendingPairStampFresh(String stampContent, long nowMs, long maxAgeMs) {
        String[] parts = splitPendingPairStamp(stampContent);
        if (parts[0].isEmpty()) return false;
        try {
            long ts = Long.parseLong(parts[0]);
            long age = nowMs - ts;
            return age >= -2_000L && age <= maxAgeMs;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Verifies (`SEC-13`) that the system Bluetooth pairing dialog corresponds to the device the user clicked
     * in SoarTV's scanner (or Xiaomi's generic controller confirmation dialog), blocking unsolicited rogue
     * third-party pairing prompts (phones, speakers, soundbars, laptops, mice) from being auto-accepted.
     */
    public static boolean isPendingPairTargetAllowed(
            String stampContent,
            List<? extends CharSequence> dialogTexts
    ) {
        String[] parts = splitPendingPairStamp(stampContent);
        if (parts[0].isEmpty()) return false;
        String expectedMac = parts[1].toLowerCase(Locale.ROOT);
        String expectedName = parts[2].toLowerCase(Locale.ROOT);

        StringBuilder sb = new StringBuilder();
        if (dialogTexts != null) {
            for (CharSequence cs : dialogTexts) {
                if (cs != null && cs.length() > 0) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(cs);
                }
            }
        }
        String dialogText = sb.toString().toLowerCase(Locale.ROOT).trim();
        if (dialogText.isEmpty()) return false;

        // Block explicit rogue non-controller devices unless the user explicitly selected a device with that word
        String[] rogueKeywords = {
                "phone", "iphone", "galaxy", "pixel", "soundbar", "speaker",
                "headphone", "headphones", "earbuds", "airpods", "headset",
                "laptop", "desktop", "attacker", "mouse", "scale"
        };
        for (String rogue : rogueKeywords) {
            if (dialogText.contains(rogue) && !expectedName.contains(rogue)) {
                return false;
            }
        }

        if (!expectedName.isEmpty() && dialogText.contains(expectedName)) {
            return true;
        }
        if (!expectedMac.isEmpty()) {
            String macPlain = expectedMac.replace(":", "");
            if (dialogText.contains(expectedMac) || (!macPlain.isEmpty() && dialogText.contains(macPlain))) {
                return true;
            }
        }
        // Allow Xiaomi Android 14 TwoPanel generic controller confirmation prompts ("Pair with Wireless Controller?")
        String[] genericControllerMarkers = {
                "wireless controller", "gamepad", "pro controller", "game controller",
                "controller", "xbox", "dualsense", "dualshock", "gamesir", "8bitdo", "joy-con", "בקר"
        };
        for (String marker : genericControllerMarkers) {
            if (dialogText.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a pending user-initiated Bluetooth pairing request from SoarTV's in-app scanner ({@code :home} process)
     * so {@link RemoteKeyService} ({@code :keys} process) can auto-confirm the system dialog.
     */
    public static void recordPendingPairRequest(Context context, String address, String name) {
        if (context == null) return;
        try {
            File f = new File(context.getFilesDir(), PENDING_PAIR_FILENAME);
            byte[] bytes = formatPendingPairStamp(System.currentTimeMillis(), address, name)
                    .getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(bytes);
                fos.getFD().sync();
            }
        } catch (Throwable t) {
            AppLog.w("BluetoothPairingAssist: failed to write pending pair stamp", t);
        }
    }

    /**
     * Clears any pending SoarTV-initiated Bluetooth pairing stamp.
     */
    public static void clearPendingPairRequest(Context context) {
        if (context == null) return;
        try {
            File f = new File(context.getFilesDir(), PENDING_PAIR_FILENAME);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable t) {
            AppLog.w("BluetoothPairingAssist: failed to clear pending pair stamp", t);
        }
    }

    private static String readFreshPendingPairStamp(Context context) {
        if (context == null) return null;
        try {
            File f = new File(context.getFilesDir(), PENDING_PAIR_FILENAME);
            if (!f.exists() || f.length() <= 0 || f.length() > 1024) return null;
            byte[] buf = new byte[(int) f.length()];
            int read;
            try (FileInputStream fis = new FileInputStream(f)) {
                read = fis.read(buf);
            }
            if (read <= 0) return null;
            String content = new String(buf, 0, read, StandardCharsets.UTF_8);
            boolean fresh = isPendingPairStampFresh(content, System.currentTimeMillis(), PENDING_PAIR_TTL_MS);
            if (!fresh) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            return content;
        } catch (Throwable t) {
            AppLog.w("BluetoothPairingAssist: failed reading pending pair stamp", t);
            return null;
        }
    }

    /**
     * Checks if the package and class identify the Android TV BluetoothPairingDialog.
     */
    public static boolean isPairingDialogPackage(CharSequence pkg, CharSequence cls) {
        if (pkg == null || cls == null) return false;
        String p = pkg.toString();
        String c = cls.toString();
        boolean matchPkg = isSettingsPackage(p);
        boolean matchCls = c.contains(PAIRING_DIALOG_CLS);
        return matchPkg && matchCls;
    }

    /**
     * Checks if the package belongs to the TV settings application.
     */
    public static boolean isSettingsPackage(CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString();
        return p.equals("com.android.tv.settings")
                || p.equals("com.google.android.tv.settings")
                || p.endsWith(".tv.settings")
                || p.equals("com.android.settings");
    }

    /**
     * Determines whether a given action label represents the "Pair" or confirmation action.
     * Rejects informational strings, titles, prompts, codes, and cancellation words.
     */
    public static boolean isPairActionLabel(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 30) return false;

        // Negative checks to avoid matching "unpair", "cancel", "dismiss", etc.
        if (s.contains("unpair") || s.contains("cancel") || s.contains("ביטול") || s.contains("abort")
                || s.contains("abbrechen") || s.contains("annuler") || s.contains("отмена")
                || s.contains("dismiss") || s.contains("reject") || s.contains("דחה")) {
            return false;
        }

        // Informational phrases must NOT be treated as action buttons (e.g. "Bluetooth pairing code: ...")
        if (s.contains("code") || s.contains("passkey") || s.contains("pin") || s.contains("request")
                || s.contains("קוד") || s.contains("בקש") || s.contains("with") || s.contains("עם")
                || s.contains("accessory") || s.contains("device") || s.contains("מכשיר")) {
            return false;
        }

        // Universal confirmation keywords
        if (s.equals("ok") || s.equals("confirm") || s.equals("yes") || s.equals("connect")
                || s.equals("אישור") || s.equals("כן") || s.equals("התחבר")) {
            return true;
        }

        // Hebrew: "התאם", "התאמה", "בצע התאמה"
        if (s.contains("התאם") || s.contains("התאמה")) {
            return true;
        }

        // English: "pair", "pairing"
        if (s.contains("pair") || s.contains("pairing")) {
            return true;
        }

        // Multilingual: Spanish "vincular"/"emparejar", French "associer"/"appairer", German "koppeln", Russian "подключить"
        if (s.contains("vincular") || s.contains("emparejar") || s.contains("associer")
                || s.contains("appairer") || s.contains("koppeln") || s.contains("подключить")) {
            return true;
        }

        return false;
    }

    private static boolean isExplicitPairWordLabel(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 30 || s.contains("unpair") || s.contains("with") || s.contains("עם")) {
            return false;
        }
        return s.contains("pair") || s.contains("התאם") || s.contains("התאמה")
                || s.contains("vincular") || s.contains("emparejar")
                || s.contains("associer") || s.contains("appairer") || s.contains("koppeln");
    }

    /**
     * Selects the 0-based index of the "Pair" action button from candidate action labels.
     * Looks for "Pair", "התאם", "Pairing", "OK", or defaults to index 0 if exactly 2 action buttons exist.
     * Returns -1 if no candidate is recognized.
     */
    public static int selectPairActionIndex(List<CharSequence> actionTexts, List<CharSequence> actionContentDescriptions) {
        int count = 0;
        if (actionTexts != null) count = Math.max(count, actionTexts.size());
        if (actionContentDescriptions != null) count = Math.max(count, actionContentDescriptions.size());
        if (count == 0) return -1;

        // First pass: exact or localized keyword match
        for (int i = 0; i < count; i++) {
            CharSequence text = (actionTexts != null && i < actionTexts.size()) ? actionTexts.get(i) : null;
            CharSequence desc = (actionContentDescriptions != null && i < actionContentDescriptions.size()) ? actionContentDescriptions.get(i) : null;
            if (isPairActionLabel(text) || isPairActionLabel(desc)) {
                return i;
            }
        }

        // Second pass: if exactly 2 action buttons exist (Leanback ActionAdapter convention: index 0 = Pair, index 1 = Cancel)
        if (count == 2) {
            CharSequence text0 = (actionTexts != null && actionTexts.size() > 0) ? actionTexts.get(0) : null;
            CharSequence text1 = (actionTexts != null && actionTexts.size() > 1) ? actionTexts.get(1) : null;
            if (isCancelActionLabel(text0) && !isCancelActionLabel(text1)) {
                return 1;
            }
            return 0;
        }

        return -1;
    }

    /**
     * Helper to detect cancellation labels.
     */
    public static boolean isCancelActionLabel(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim().toLowerCase(Locale.ROOT);
        return s.contains("cancel") || s.contains("ביטול") || s.contains("abort")
                || s.contains("abbrechen") || s.contains("annuler") || s.contains("отмена")
                || s.contains("dismiss") || s.contains("reject") || s.contains("דחה")
                || s.equals("no") || s.equals("לא");
    }

    /**
     * Pure classifier deciding whether a Settings window's text nodes and action buttons represent
     * Android TV's Bluetooth pairing confirmation dialog (including Xiaomi's TwoPanel {@code ConfirmationDialogFragment}
     * where {@code "Bluetooth"} and {@code :id/*pairing*} are absent), while rejecting Unpair/Forget dialogs,
     * {@code AddAccessoryActivity} scan lists, and paired-device detail menus.
     */
    public static boolean isPairingWindowTextAndActions(
            List<? extends CharSequence> windowTexts,
            List<? extends CharSequence> actionTexts,
            List<? extends CharSequence> actionDescs,
            boolean knownPairingActivity
    ) {
        boolean hasClassicMarker = false;
        boolean hasPairPrompt = false;
        if (windowTexts != null) {
            for (CharSequence raw : windowTexts) {
                if (raw == null) continue;
                String s = raw.toString().trim().toLowerCase(Locale.ROOT);
                if (s.isEmpty()) continue;
                // Never match Unpair / Forget confirmation dialogs
                if (s.contains("unpair") || s.contains("בטל התאמה") || s.contains("forget device") || s.contains("שכח")) {
                    return false;
                }
                if ((s.contains("bluetooth") && (s.contains("pair") || s.contains("התאמ")))
                        || s.contains("passkey") || s.contains("pairing code") || s.contains("קוד התאמה")) {
                    hasClassicMarker = true;
                }
                if (s.contains("pair with") || s.contains("pairing request") || s.contains("would like to pair")
                        || s.contains("התאמה עם") || s.contains("בקשת התאמה") || s.contains("להתאים")) {
                    hasPairPrompt = true;
                }
            }
        }
        if (hasClassicMarker) return true;

        int count = 0;
        if (actionTexts != null) count = Math.max(count, actionTexts.size());
        if (actionDescs != null) count = Math.max(count, actionDescs.size());
        if (count != 2) {
            return false;
        }

        boolean foundConfirm = false;
        boolean foundExplicitPair = false;
        boolean foundCancel = false;
        for (int i = 0; i < 2; i++) {
            CharSequence t = (actionTexts != null && i < actionTexts.size()) ? actionTexts.get(i) : null;
            CharSequence d = (actionDescs != null && i < actionDescs.size()) ? actionDescs.get(i) : null;
            if (isCancelActionLabel(t) || isCancelActionLabel(d)) {
                foundCancel = true;
            } else if (isPairActionLabel(t) || isPairActionLabel(d)) {
                foundConfirm = true;
                if (isExplicitPairWordLabel(t) || isExplicitPairWordLabel(d)) {
                    foundExplicitPair = true;
                }
            }
        }
        if (!foundConfirm || !foundCancel) {
            return false;
        }
        return knownPairingActivity || hasPairPrompt || foundExplicitPair;
    }

    /**
     * Checks if a keycode is a confirm/select key.
     */
    public static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    /**
     * Determines whether a key event should be intercepted as a confirm action for an unfocused pairing dialog.
     * Intercepts DPAD_CENTER, ENTER, BUTTON_A when dialog is active and neither action button has input focus.
     */
    public static boolean shouldInterceptConfirmKey(int keyCode, boolean dialogVisible, boolean anyActionFocused) {
        if (!dialogVisible) return false;
        if (anyActionFocused) return false;
        return isConfirmKey(keyCode);
    }

    /**
     * Identifies routine Android TV Settings activities that are never a Bluetooth pairing confirmation dialog.
     *
     * <p><b>Why:</b> Walking {@code com.android.tv.settings}'s TwoPanel View hierarchy during normal Settings
     * browsing blocks {@code RemoteKeyService}'s main looper and delays {@code DPAD_UP}/{@code DPAD_DOWN}
     * {@code ACTION_UP} events past {@code KeyRepeatTimeout}, causing single D-pad presses to jump several rows.
     */
    public static boolean isKnownNonPairingSettingsActivity(CharSequence cls) {
        if (cls == null) return false;
        String c = cls.toString();
        if (c.contains(PAIRING_DIALOG_CLS) || c.toLowerCase(Locale.ROOT).contains("pair")) {
            return false;
        }
        return c.endsWith("MainSettings")
                || c.endsWith("NetworkActivity")
                || c.endsWith("WifiSetupActivity")
                || c.endsWith("EditIpSettingsActivity")
                || c.endsWith("MiPictureActivity")
                || c.endsWith("MiSoundActivity")
                || c.endsWith("MiHarmanSoundCustomActivity")
                || c.endsWith("AboutActivity")
                || c.endsWith("StatusActivity")
                || c.endsWith("AppsActivity")
                || c.endsWith("AllAppsActivity")
                || c.endsWith("AppManagementActivity")
                || c.endsWith("StorageResetActivity")
                || c.endsWith("NewStorageActivity")
                || c.endsWith("LocationActivity")
                || c.endsWith("LanguageActivity")
                || c.endsWith("DevelopmentActivity")
                || c.endsWith("EnergyModesActivity")
                || c.endsWith("AccountActivity")
                || c.endsWith("AccessibilityActivity")
                || c.endsWith("DateTimeActivity")
                || c.endsWith("InputMethodActivity")
                || c.endsWith("PrivacyActivity")
                || c.endsWith("RecyclerView")
                || c.endsWith("VerticalGridView")
                || c.endsWith("HorizontalGridView");
    }

    /**
     * Pure gate deciding whether an accessibility event from Android TV Settings warrants inspecting the
     * window hierarchy for an unfocused Bluetooth pairing dialog.
     *
     * <p>Returns {@code false} in {@code O(1)} for ALL {@code TYPE_WINDOW_CONTENT_CHANGED} events during
     * normal Native Settings browsing (when neither a pairing activity nor a fresh SoarTV-initiated bond stamp
     * is active), guaranteeing zero Binder IPC on D-pad navigation in Native Settings.
     */
    public static boolean shouldInspectSettingsEvent(
            int eventType,
            CharSequence pkg,
            CharSequence cls,
            boolean knownPairingActivity,
            boolean hasFreshPendingStamp
    ) {
        if (!isSettingsPackage(pkg)) return false;
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isPairingDialogPackage(pkg, cls)) return true;
            if (hasFreshPendingStamp) return true;
            if (isKnownNonPairingSettingsActivity(cls)) return false;
            return knownPairingActivity || isPotentialPairingDialogContainerClass(cls);
        }
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return knownPairingActivity || hasFreshPendingStamp;
        }
        return false;
    }

    private static boolean isPotentialPairingDialogContainerClass(CharSequence cls) {
        if (cls == null) return false;
        String c = cls.toString();
        return c.endsWith("FrameLayout")
                || c.endsWith("Dialog")
                || c.endsWith("AlertDialog")
                || c.contains("ConfirmationDialog")
                || c.contains("Bluetooth");
    }

    private boolean hasFreshPendingPairStampFast(Context context, long nowMs) {
        if (context == null) return false;
        if (nowMs - lastStampCheckMs < PENDING_STAMP_CACHE_MS) {
            return cachedFreshPendingStamp;
        }
        lastStampCheckMs = nowMs;
        try {
            File f = new File(context.getFilesDir(), PENDING_PAIR_FILENAME);
            if (!f.exists() || f.length() <= 0 || f.length() > 1024) {
                cachedFreshPendingStamp = false;
                return false;
            }
            long age = nowMs - f.lastModified();
            cachedFreshPendingStamp = (age >= -2_000L && age <= PENDING_PAIR_TTL_MS + 2_000L);
            return cachedFreshPendingStamp;
        } catch (Throwable ignored) {
            cachedFreshPendingStamp = false;
            return false;
        }
    }

    /**
     * Handles accessibility events to proactively set focus on (or auto-confirm) the Pair button.
     *
     * <p><b>Performance guarantee:</b> Runs strictly {@code O(1)} on {@link RemoteKeyService}'s main looper
     * with ZERO synchronous {@code getRootInActiveWindow()} / {@code getWindows()} Binder IPC so
     * {@code DPAD_UP}/{@code DPAD_DOWN} key filtering in Native Settings never stalls or triggers key repeats.
     */
    public void onAccessibilityEvent(final AccessibilityService service, AccessibilityEvent event) {
        if (service == null || event == null) return;
        final int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        final CharSequence pkg = event.getPackageName();
        final CharSequence cls = event.getClassName();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg != null && !isSettingsPackage(pkg)) {
                resetDialogState();
                return;
            }
            if (isKnownNonPairingSettingsActivity(cls) && !hasFreshPendingPairStampFast(service, System.currentTimeMillis())) {
                resetDialogState();
                return;
            }
        }

        long now = System.currentTimeMillis();
        boolean hasFreshStamp = isSettingsPackage(pkg) && hasFreshPendingPairStampFast(service, now);

        if (!shouldInspectSettingsEvent(type, pkg, cls, knownPairingActivityWindow, hasFreshStamp)) {
            return;
        }

        // Rate-limit TYPE_WINDOW_CONTENT_CHANGED even when a pairing dialog IS active
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastContentCheckMs < CONTENT_CHECK_INTERVAL_MS) {
                return;
            }
            lastContentCheckMs = now;
        }

        final boolean matchedByClass = (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                && isPairingDialogPackage(pkg, cls);
        if (matchedByClass) {
            dialogVisible = true;
            knownPairingActivityWindow = true;
            dialogVisibleUntilMs = android.os.SystemClock.uptimeMillis() + 15_000L;
            lastFocusAttemptMs = now;
            enqueueAsyncInspection(service, true, true);
            scheduleRetries(service);
            return;
        }

        if (now - lastFocusAttemptMs >= FOCUS_RETRY_INTERVAL_MS || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastFocusAttemptMs = now;
            enqueueAsyncInspection(service, type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, knownPairingActivityWindow);
        }
    }

    private void enqueueAsyncInspection(final AccessibilityService service, final boolean clearIfNotPairing, final boolean knownPairing) {
        if (!inspectScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            inspectExecutor.execute(() -> {
                try {
                    boolean isPairing = findPairingDialogRoot(service, knownPairing) != null;
                    if (isPairing) {
                        dialogVisible = true;
                        dialogVisibleUntilMs = android.os.SystemClock.uptimeMillis() + 15_000L;
                        ensurePairFocus(service);
                    } else if (clearIfNotPairing) {
                        resetDialogState();
                    }
                } catch (Throwable ignored) {
                } finally {
                    inspectScheduled.set(false);
                }
            });
        } catch (Throwable ignored) {
            inspectScheduled.set(false);
        }
    }

    private void scheduleRetries(final AccessibilityService service) {
        try {
            if (retryHandler == null) {
                retryHandler = new Handler(Looper.getMainLooper());
            }
            retryHandler.removeCallbacksAndMessages(null);
            for (long delay : RETRY_DELAYS_MS) {
                retryHandler.postDelayed(() -> {
                    if (!dialogVisible) return;
                    enqueueAsyncInspection(service, false, knownPairingActivityWindow);
                }, delay);
            }
        } catch (Throwable ignored) {
        }
    }

    private void resetDialogState() {
        dialogVisible = false;
        pendingUnfocusedPairAction = false;
        dialogVisibleUntilMs = 0L;
        knownPairingActivityWindow = false;
        consumedConfirmKeyDown = false;
        if (retryHandler != null) {
            try {
                retryHandler.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Intercepts confirm keys ONLY when an unfocused Bluetooth pairing dialog was already observed by
     * {@link #onAccessibilityEvent}. Never performs synchronous {@code getWindows()} / {@code getRootInActiveWindow()}
     * Binder IPC inside {@code InputDispatcher}'s key filter callback, preventing {@code WindowManager} /
     * {@code AccessibilityManagerService} lock contention and system-wide TV input freezes.
     */
    public boolean onKeyEvent(final AccessibilityService service, KeyEvent event) {
        if (service == null || event == null) return false;
        final int keyCode = event.getKeyCode();

        // Handle BACK / ESCAPE dismissal in O(1) without Binder IPC
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (dialogVisible && event.getAction() == KeyEvent.ACTION_DOWN) {
                resetDialogState();
            }
            return false;
        }

        if (!isConfirmKey(keyCode)) return false;

        // Consume matching ACTION_UP for a confirm key intercepted on ACTION_DOWN
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (consumedConfirmKeyDown) {
                resetDialogState();
                return true;
            }
            return false;
        }

        if (!dialogVisible || !pendingUnfocusedPairAction) {
            return false;
        }
        if (android.os.SystemClock.uptimeMillis() > dialogVisibleUntilMs) {
            resetDialogState();
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                consumedConfirmKeyDown = true;
                pendingUnfocusedPairAction = false;
                try {
                    inspectExecutor.execute(() -> performAsyncPairClick(service, keyCode));
                } catch (Throwable ignored) {
                }
            }
            return true;
        }
        return false;
    }

    private void performAsyncPairClick(AccessibilityService service, int keyCode) {
        try {
            AccessibilityNodeInfo root = findPairingDialogRoot(service, knownPairingActivityWindow);
            if (root == null) return;
            List<AccessibilityNodeInfo> actionNodes = new ArrayList<>();
            findActionNodes(root, actionNodes);
            if (actionNodes.isEmpty()) return;
            List<CharSequence> texts = new ArrayList<>(actionNodes.size());
            List<CharSequence> descs = new ArrayList<>(actionNodes.size());
            for (AccessibilityNodeInfo node : actionNodes) {
                texts.add(extractNodeText(node));
                descs.add(node.getContentDescription());
            }
            int pairIndex = selectPairActionIndex(texts, descs);
            if (pairIndex < 0 || pairIndex >= actionNodes.size()) return;
            AccessibilityNodeInfo pairNode = actionNodes.get(pairIndex);
            AppLog.w("BluetoothPairingAssist: async confirm click (" + keyCode + ") on Pair node at index " + pairIndex);
            boolean clicked = pairNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!clicked) {
                performClickOnNodeOrChild(pairNode);
            }
            clearPendingPairRequest(service);
        } catch (Throwable t) {
            AppLog.w("BluetoothPairingAssist: async confirm click failed", t);
        }
    }

    public void onInterrupt() {
        resetDialogState();
    }

    /**
     * Proactively sets focus on the Pair action button — and if the user initiated pairing inside
     * SoarTV within the last 25 seconds, automatically clicks Pair so bonding completes seamlessly.
     */
    public boolean ensurePairFocus(AccessibilityService service) {
        if (service == null) return false;
        AccessibilityNodeInfo root = findPairingDialogRoot(service, knownPairingActivityWindow);
        if (root == null) return false;

        List<AccessibilityNodeInfo> actionNodes = new ArrayList<>();
        findActionNodes(root, actionNodes);
        if (actionNodes.isEmpty()) return false;

        List<CharSequence> texts = new ArrayList<>();
        List<CharSequence> descs = new ArrayList<>();
        for (AccessibilityNodeInfo node : actionNodes) {
            texts.add(extractNodeText(node));
            descs.add(node.getContentDescription());
        }

        int pairIndex = selectPairActionIndex(texts, descs);
        if (pairIndex < 0 || pairIndex >= actionNodes.size()) return false;

        AccessibilityNodeInfo pairNode = actionNodes.get(pairIndex);

        // If user explicitly initiated bonding from SoarTV's in-app scanner, verify dialog target before auto-clicking Pair!
        String pendingStamp = readFreshPendingPairStamp(service);
        if (pendingStamp != null) {
            List<CharSequence> windowTexts = new ArrayList<>();
            collectWindowTexts(root, windowTexts, 0);
            if (isPendingPairTargetAllowed(pendingStamp, windowTexts)) {
                performFocusOnNodeOrChild(pairNode);
                boolean clicked = pairNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!clicked) {
                    clicked = performClickOnNodeOrChild(pairNode);
                }
                if (clicked) {
                    AppLog.w("BluetoothPairingAssist: auto-confirmed verified SoarTV Bluetooth pairing at index " + pairIndex);
                    clearPendingPairRequest(service);
                    resetDialogState();
                    return true;
                }
            } else {
                AppLog.w("BluetoothPairingAssist: blocked auto-pair for unverified dialog target: " + windowTexts);
            }
        }

        dialogVisibleUntilMs = android.os.SystemClock.uptimeMillis() + 15_000L;
        boolean anyFocused = false;
        for (AccessibilityNodeInfo node : actionNodes) {
            if (isNodeOrDescendantFocused(node)) {
                anyFocused = true;
                break;
            }
        }
        if (anyFocused) {
            pendingUnfocusedPairAction = false;
            return false;
        }

        AppLog.w("BluetoothPairingAssist: requesting focus on Pair node at index " + pairIndex);
        boolean focusedNow = performFocusOnNodeOrChild(pairNode);
        pendingUnfocusedPairAction = !focusedNow;
        return focusedNow;
    }

    /**
     * Finds the root AccessibilityNodeInfo for the Bluetooth pairing dialog.
     */
    public static AccessibilityNodeInfo findPairingDialogRoot(AccessibilityService service) {
        return findPairingDialogRoot(service, false);
    }

    private static AccessibilityNodeInfo findPairingDialogRoot(AccessibilityService service, boolean knownPairingActivity) {
        if (service == null) return null;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null && isPairingDialogContent(root, knownPairingActivity)) {
                return root;
            }
        } catch (Throwable ignored) {}

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo win : windows) {
                    if (win == null) continue;
                    try {
                        AccessibilityNodeInfo winRoot = win.getRoot();
                        if (winRoot != null && isPairingDialogContent(winRoot, knownPairingActivity)) {
                            return winRoot;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Checks if the currently active or interactive window is the Bluetooth pairing dialog.
     */
    public static boolean checkActiveWindowForPairingDialog(AccessibilityService service) {
        return findPairingDialogRoot(service, false) != null;
    }

    /**
     * Inspects node hierarchy for pairing dialog markers.
     */
    public static boolean isPairingDialogContent(AccessibilityNodeInfo root) {
        return isPairingDialogContent(root, false);
    }

    public static boolean isPairingDialogContent(AccessibilityNodeInfo root, boolean knownPairingActivity) {
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        if (!isSettingsPackage(pkg)) return false;
        CharSequence cls = root.getClassName();
        if (cls != null && cls.toString().contains(PAIRING_DIALOG_CLS)) {
            return true;
        }
        if (scanForPairingResourceMarker(root, 0)) {
            return true;
        }
        List<CharSequence> windowTexts = new ArrayList<>();
        collectWindowTexts(root, windowTexts, 0);
        List<AccessibilityNodeInfo> actionNodes = new ArrayList<>();
        findActionNodes(root, actionNodes);
        List<CharSequence> actionTexts = new ArrayList<>(actionNodes.size());
        List<CharSequence> actionDescs = new ArrayList<>(actionNodes.size());
        for (AccessibilityNodeInfo an : actionNodes) {
            actionTexts.add(extractNodeText(an));
            actionDescs.add(an.getContentDescription());
        }
        return isPairingWindowTextAndActions(windowTexts, actionTexts, actionDescs, knownPairingActivity);
    }

    private static boolean scanForPairingResourceMarker(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 20) return false;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains(PAIRING_DIALOG_CLS)) return true;
        CharSequence id = node.getViewIdResourceName();
        if (id != null && id.toString().toLowerCase(Locale.ROOT).contains("pairing")) return true;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && scanForPairingResourceMarker(child, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static void collectWindowTexts(AccessibilityNodeInfo node, List<CharSequence> out, int depth) {
        if (node == null || depth > 20) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            out.add(t);
        }
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) {
            out.add(d);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectWindowTexts(child, out, depth + 1);
            }
        }
    }

    /**
     * Recursively collects candidate action button nodes from the node tree.
     */
    public static void findActionNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (isActionCandidate(node)) {
            out.add(node);
            return;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findActionNodes(child, out);
            }
        }
    }

    /**
     * Determines whether a node is a candidate action button (and not a container or collection).
     */
    public static boolean isActionCandidate(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (!node.isClickable() && !node.isFocusable()) return false;
        CharSequence text = extractNodeText(node);
        if (text == null && node.getContentDescription() == null) return false;
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String c = cls.toString();
            if (c.contains("RecyclerView") || c.contains("ListView") || c.contains("ScrollView")
                    || c.contains("GridView") || c.contains("VerticalGridView") || c.contains("HorizontalGridView")
                    || c.contains("AdapterView")) {
                return false;
            }
        }
        int childCount = node.getChildCount();
        if (childCount > 1) {
            int actionableChildren = 0;
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (child.isClickable() || child.isFocusable() || extractNodeText(child) != null) {
                        actionableChildren++;
                        if (actionableChildren > 1) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Checks if the node or any of its descendants currently has input focus.
     */
    public static boolean isNodeOrDescendantFocused(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isFocused()) return true;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && isNodeOrDescendantFocused(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Proactively requests ACTION_FOCUS on node or its first focusable descendant.
     */
    public static boolean performFocusOnNodeOrChild(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isFocusable() && node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            return true;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && performFocusOnNodeOrChild(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts text from the node or concatenates text from its children.
     */
    public static CharSequence extractNodeText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) return t;
        StringBuilder sb = new StringBuilder();
        collectChildText(node, sb);
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static void collectChildText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            CharSequence t = child.getText();
            if (t != null && t.length() > 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
            collectChildText(child, sb);
        }
    }

    private static boolean performClickOnNodeOrChild(AccessibilityNodeInfo node) {
        if (node == null) return false;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            if (child.isClickable() && child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            if (performClickOnNodeOrChild(child)) {
                return true;
            }
        }
        return false;
    }
}
