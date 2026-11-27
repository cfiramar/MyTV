package io.soartv;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java + Android TV bridge powering two Companion Remote capabilities with zero PC dependency:
 * <ol>
 *   <li><b>Mirrored Interactive TV Modals ({@link ActiveModal})</b>: Serializes active TV dialogs
 *       (Trakt/Stremio QR links, pairing approval, Liquid Glass choices, Hold-OK menus, Sleep Timer,
 *       Game Launch, Sync modals) onto {@code /api/status} so the phone Companion Remote automatically
 *       pops up a matching one-tap sheet (opening QR links directly in the phone browser without a camera).</li>
 *   <li><b>Reliable TV Wake-Up</b>: Immediately dismisses any active screensaver across processes,
 *       pulses an {@code ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE} wake lock to light up the screen and
 *       reset Android's idle timer, and falls back to Zero-PC loopback ADB {@code KEYCODE_WAKEUP (224)}
 *       whenever the display is non-interactive or dreaming.</li>
 * </ol>
 */
public final class TvRemoteBridge {

    public static final String ACTION_DISMISS_SCREENSAVER = "io.soartv.ACTION_DISMISS_SCREENSAVER";
    public static final String SCREENSAVER_FLAG_FILE = "screensaver_active.flag";
    public static final String KIND_QR_LINK = "qr_link";
    public static final String KIND_ACTIONS = "actions";

    private static final long WAKE_LOCK_COOLDOWN_MS = 1_200L;
    private static final long ADB_WAKE_COOLDOWN_MS = 2_000L;
    private static final long WAKE_LOCK_HOLD_MS = 1_500L;
    private static final long SCREENSAVER_FLAG_MAX_AGE_MS = 14_400_000L; // 4 hours safety ceiling

    private static volatile long lastWakeLockMs = 0L;
    private static volatile long lastAdbWakeMs = 0L;

    private TvRemoteBridge() {}

    /** Single clickable action mirrored to the Companion Remote modal sheet. */
    public static final class ModalAction {
        public final String id;
        public final String label;
        public final String style; // "primary", "blue", "secondary", "danger"

        public ModalAction(String id, String label, String style) {
            this.id = id != null ? id.trim() : "";
            this.label = label != null ? label.trim() : "";
            this.style = (style != null && !style.trim().isEmpty()) ? style.trim() : "secondary";
        }
    }

    /** Immutable snapshot of an active user-action TV modal mirrored to the Companion Remote. */
    public static final class ActiveModal {
        public static final ActiveModal INACTIVE = new ActiveModal(
                false, "", "", "", "", "", "", "", Collections.emptyList(), false);

        public final boolean active;
        public final String id;
        public final String kind;
        public final String title;
        public final String subtitle;
        public final String url;
        public final String code;
        public final String primaryUrlLabel;
        public final List<ModalAction> actions;
        public final boolean adminOnly;

        public ActiveModal(
                boolean active,
                String id,
                String kind,
                String title,
                String subtitle,
                String url,
                String code,
                String primaryUrlLabel,
                List<ModalAction> actions,
                boolean adminOnly) {
            this.active = active;
            this.id = id != null ? id : "";
            this.kind = kind != null ? kind : KIND_ACTIONS;
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.url = isSafeExternalModalUrl(url) ? url.trim() : "";
            this.code = code != null ? code.trim() : "";
            this.primaryUrlLabel = primaryUrlLabel != null ? primaryUrlLabel.trim() : "";
            this.actions = actions != null
                    ? Collections.unmodifiableList(new ArrayList<>(actions))
                    : Collections.emptyList();
            this.adminOnly = adminOnly;
        }

        /** Converts this modal snapshot to JSON for {@code /api/status}, hiding admin-only modals from non-admins. */
        public JSONObject toJsonForRole(RemoteAuthManager.Role role) throws JSONException {
            JSONObject obj = new JSONObject();
            if (!active || (adminOnly && role != RemoteAuthManager.Role.ADMIN)) {
                obj.put("active", false);
                return obj;
            }
            obj.put("active", true);
            obj.put("id", id);
            obj.put("kind", kind);
            obj.put("title", title);
            obj.put("subtitle", subtitle);
            obj.put("url", url);
            obj.put("code", code);
            obj.put("primaryUrlLabel", primaryUrlLabel);
            JSONArray arr = new JSONArray();
            for (ModalAction act : actions) {
                if (act == null || act.id.isEmpty() || act.label.isEmpty()) continue;
                JSONObject ao = new JSONObject();
                ao.put("id", act.id);
                ao.put("label", act.label);
                ao.put("style", act.style);
                arr.put(ao);
            }
            obj.put("actions", arr);
            return obj;
        }
    }

    /**
     * Strictly allows {@code https://} URLs for mirrored QR browser links (rejecting {@code javascript:},
     * {@code file:}, {@code intent:}, and blank values).
     */
    public static boolean isSafeExternalModalUrl(String rawUrl) {
        if (rawUrl == null) return false;
        String trimmed = rawUrl.trim();
        if (trimmed.length() < 12 || trimmed.length() > 512) return false;
        String lower = trimmed.toLowerCase(Locale.US);
        return lower.startsWith("https://") && !lower.contains(" ") && !lower.contains("\n") && !lower.contains("\r");
    }

    /**
     * Pure decision helper determining whether loopback ADB {@code KEYCODE_WAKEUP (224)} should fire.
     * Avoids redundant ADB calls when the TV is already awake and not dreaming, and enforces cooldown.
     */
    public static boolean shouldDispatchAdbWake(
            boolean isInteractive,
            boolean screensaverActive,
            boolean forceWakeRequest,
            long lastAdbWakeAtMs,
            long nowMs) {
        if (nowMs - lastAdbWakeAtMs < ADB_WAKE_COOLDOWN_MS) {
            return false;
        }
        return !isInteractive || screensaverActive || (forceWakeRequest && !isInteractive);
    }

    /** Records whether the TV screensaver is currently active so {@code :home} can detect it across processes. */
    public static void markScreensaverActive(Context context, boolean active) {
        if (context == null) return;
        try {
            File flag = new File(context.getFilesDir(), SCREENSAVER_FLAG_FILE);
            if (active) {
                try (FileOutputStream fos = new FileOutputStream(flag, false)) {
                    fos.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
                }
            } else if (flag.exists()) {
                //noinspection ResultOfMethodCallIgnored
                flag.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Returns {@code true} when {@link Screensaver} reported an active dream session recently. */
    public static boolean isScreensaverActive(Context context) {
        if (context == null) return false;
        try {
            File flag = new File(context.getFilesDir(), SCREENSAVER_FLAG_FILE);
            if (!flag.exists()) return false;
            long age = System.currentTimeMillis() - flag.lastModified();
            if (age < 0L || age > SCREENSAVER_FLAG_MAX_AGE_MS) {
                //noinspection ResultOfMethodCallIgnored
                flag.delete();
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Wakes the TV display, dismisses any running screensaver, and resets Android's idle timer whenever
     * the user interacts with the Companion Remote App. Safe to call from any thread.
     */
    @SuppressWarnings("deprecation")
    public static void wakeTvFromRemote(Context context, boolean explicitWakeCall) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final long now = System.currentTimeMillis();
        final boolean dreaming = isScreensaverActive(app);

        // 1. Immediately dismiss our own Screensaver across processes if running
        if (dreaming || explicitWakeCall) {
            try {
                Intent dismiss = new Intent(ACTION_DISMISS_SCREENSAVER);
                dismiss.setPackage(app.getPackageName());
                app.sendBroadcast(dismiss);
            } catch (Throwable ignored) {
            }
        }

        // 2. Pulse SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE to wake panel & reset idle timeout
        boolean interactive = true;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                interactive = pm.isInteractive();
                if (!interactive || dreaming || (now - lastWakeLockMs >= WAKE_LOCK_COOLDOWN_MS)) {
                    lastWakeLockMs = now;
                    PowerManager.WakeLock wl = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                    | PowerManager.ON_AFTER_RELEASE,
                            "soartv:remote_wake");
                    wl.setReferenceCounted(false);
                    wl.acquire(WAKE_LOCK_HOLD_MS);
                    try {
                        wl.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 3. When display is non-interactive or dreaming, send KEYCODE_WAKEUP (224) via Zero-PC loopback ADB
        if (shouldDispatchAdbWake(interactive, dreaming, explicitWakeCall, lastAdbWakeMs, now)) {
            lastAdbWakeMs = now;
            Thread t = new Thread(() -> {
                try (Adb adb = Adb.connectSignedOnly(app)) {
                    if (adb != null) {
                        adb.shell("input keyevent 224");
                    }
                } catch (Throwable ignored) {
                }
            }, "soartv-remote-wake");
            t.setDaemon(true);
            t.start();
        }
    }
}
