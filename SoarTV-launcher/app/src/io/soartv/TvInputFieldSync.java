package io.soartv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bidirectional live TV input-field synchronizer bridging {@link RemoteKeyService} ({@code :keys}) and
 * {@link RemoteControlServer} ({@code :home}).
 *
 * <p><b>Why:</b> When a user focuses a search bar or text field inside Netflix, YouTube, Stremio, Play Store,
 * or SoarTV using the physical TV remote (e.g. typing {@code "mean gilrs"}) and then opens the Companion Remote
 * on their phone, the phone should immediately know a TV input field is focused, display the current text so
 * they can fix typos or copy it, and replace/submit the text back into the TV field natively with zero PC dependency.
 *
 * <p><b>What:</b>
 * <ul>
 *   <li>Performs all {@code AccessibilityNodeInfo} Binder IPC asynchronously on a single coalesced daemon thread
 *       ({@value #INSPECT_THROTTLE_MS}ms trailing throttle) so {@link RemoteKeyService}'s main looper never lags
 *       during D-pad navigation.</li>
 *   <li>Redacts password/PIN fields ({@code isPassword=true} always emits empty text) and ignores placeholder hint
 *       text ({@code isShowingHintText()}).</li>
 *   <li>Persists snapshots atomically to {@code filesDir/tv_focused_input.json} so {@code :home} can read them
 *       with zero AIDL overhead.</li>
 *   <li>Receives package-scoped non-exported {@link #ACTION_REMOTE_SET_TEXT} broadcasts to apply
 *       {@code ACTION_SET_TEXT} and optional {@code ACTION_IME_ENTER} directly into the focused TV field.</li>
 * </ul>
 */
public final class TvInputFieldSync {

    public static final String ACTION_REMOTE_SET_TEXT = "io.soartv.ACTION_REMOTE_SET_TEXT";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_SUBMIT = "submit";
    public static final String SNAPSHOT_FILENAME = "tv_focused_input.json";

    /** Freshness TTL for snapshots written by {@code :keys} (cleared immediately when focus leaves). */
    public static final long SNAPSHOT_TTL_MS = 300_000L;

    /** Maximum character count mirrored over LAN in `/api/status`. */
    public static final int MAX_TEXT_LENGTH = 512;

    /** Minimum interval between background Accessibility node inspections (trailing-coalesced). */
    public static final long INSPECT_THROTTLE_MS = 140L;

    private static final int MAX_TRAVERSE_NODES = 180;
    private static final int MAX_SNAPSHOT_BYTES = 4096;

    private static final Map<String, String> KNOWN_APP_LABELS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("com.netflix.ninja", "Netflix");
        m.put("com.google.android.youtube.tv", "YouTube");
        m.put("com.google.android.youtube.tvmusic", "YouTube Music");
        m.put("com.google.android.youtube.tvkids", "YouTube Kids");
        m.put("com.stremio.one", "Stremio");
        m.put("com.disney.disneyplus", "Disney+");
        m.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        m.put("com.apple.atve.androidtv.appletv", "Apple TV");
        m.put("com.spotify.tv.android", "Spotify");
        m.put("com.wbd.stream", "Max");
        m.put("com.hbo.hbonow", "Max");
        m.put("com.android.vending", "Google Play");
        m.put("com.google.android.katniss", "Google Search");
        m.put("com.plexapp.android", "Plex");
        m.put("org.xbmc.kodi", "Kodi");
        m.put("com.twitch.android.app", "Twitch");
        m.put("tv.twitch.android.app", "Twitch");
        m.put("io.soartv", "SoarTV");
        KNOWN_APP_LABELS = Collections.unmodifiableMap(m);
    }

    /** Immutable snapshot of the currently focused editable field on the TV. */
    public static final class InputSnapshot {
        public static final InputSnapshot INACTIVE =
                new InputSnapshot(false, "", "", "", "", false, 0L, 0L);

        public final boolean active;
        public final String text;
        public final String hint;
        public final String packageName;
        public final String appLabel;
        public final boolean isPassword;
        public final long revision;
        public final long updatedAtMs;

        public InputSnapshot(
                boolean active,
                String text,
                String hint,
                String packageName,
                String appLabel,
                boolean isPassword,
                long revision,
                long updatedAtMs) {
            this.active = active;
            this.text = text != null ? text : "";
            this.hint = hint != null ? hint : "";
            this.packageName = packageName != null ? packageName : "";
            this.appLabel = appLabel != null ? appLabel : "";
            this.isPassword = isPassword;
            this.revision = revision;
            this.updatedAtMs = updatedAtMs;
        }

        /** Returns true if the visible input metadata matches {@code other}, ignoring timestamps/revisions. */
        public boolean sameContent(InputSnapshot other) {
            if (other == null) return false;
            return this.active == other.active
                    && this.isPassword == other.isPassword
                    && this.text.equals(other.text)
                    && this.hint.equals(other.hint)
                    && this.packageName.equals(other.packageName)
                    && this.appLabel.equals(other.appLabel);
        }

        /** Serializes this snapshot to RFC 8259 JSON without depending on Android stubbed {@code org.json}. */
        public String toJsonString() {
            StringBuilder sb = new StringBuilder(192);
            sb.append('{');
            sb.append("\"active\":").append(active).append(',');
            sb.append("\"text\":\"").append(escapeJson(text)).append("\",");
            sb.append("\"hint\":\"").append(escapeJson(hint)).append("\",");
            sb.append("\"packageName\":\"").append(escapeJson(packageName)).append("\",");
            sb.append("\"appLabel\":\"").append(escapeJson(appLabel)).append("\",");
            sb.append("\"isPassword\":").append(isPassword).append(',');
            sb.append("\"revision\":").append(revision).append(',');
            sb.append("\"updatedAtMs\":").append(updatedAtMs);
            sb.append('}');
            return sb.toString();
        }

        /** Parses a snapshot written by {@link #toJsonString()}, returning {@link #INACTIVE} on malformed input. */
        public static InputSnapshot fromJsonString(String json) {
            if (json == null || json.trim().isEmpty()) return INACTIVE;
            Map<String, String> fields = parseFlatJsonObject(json);
            if (fields.isEmpty()) return INACTIVE;
            boolean active = "true".equalsIgnoreCase(fields.get("active"));
            boolean isPassword = "true".equalsIgnoreCase(fields.get("isPassword"));
            String rawText = fields.containsKey("text") ? fields.get("text") : "";
            String text = isPassword ? "" : sanitizeLength(rawText);
            String hint = sanitizeLength(fields.containsKey("hint") ? fields.get("hint") : "");
            String pkg = fields.containsKey("packageName") ? fields.get("packageName") : "";
            String label = fields.containsKey("appLabel") ? fields.get("appLabel") : "";
            long rev = parseLongSafe(fields.get("revision"), 0L);
            long updatedAt = parseLongSafe(fields.get("updatedAtMs"), 0L);
            if (!active) {
                return new InputSnapshot(false, "", "", "", "", false, rev, updatedAt);
            }
            return new InputSnapshot(true, text, hint, pkg, label, isPassword, rev, updatedAt);
        }
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "soartv-input-sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inspectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean delayedScheduled = new AtomicBoolean(false);

    private volatile long lastInspectMs = 0L;
    private volatile long revisionCounter = 0L;
    private volatile InputSnapshot lastWrittenSnapshot = InputSnapshot.INACTIVE;
    private BroadcastReceiver setTextReceiver;

    public TvInputFieldSync() {}

    /**
     * Extracts safe editable text from an input node, strictly redacting password/PIN fields and ignoring
     * placeholder hint strings ({@code showingHint=true}).
     */
    public static String extractSafeText(
            CharSequence rawText,
            CharSequence rawHint,
            boolean showingHint,
            boolean isPassword) {
        if (isPassword || showingHint || rawText == null) {
            return "";
        }
        String s = rawText.toString().replace('\r', ' ').replace('\n', ' ');
        return sanitizeLength(s);
    }

    /**
     * Resolves a human-friendly TV app name (e.g. {@code "Netflix"} for {@code "com.netflix.ninja"}).
     */
    public static String friendlyAppName(String packageName, String pmLabel) {
        String pkg = packageName != null ? packageName.trim() : "";
        String lbl = pmLabel != null ? pmLabel.trim() : "";
        if (!lbl.isEmpty() && !lbl.equals(pkg)) {
            return sanitizeLength(lbl);
        }
        String known = KNOWN_APP_LABELS.get(pkg.toLowerCase(Locale.ROOT));
        if (known != null) {
            return known;
        }
        if (pkg.isEmpty()) {
            return "TV App";
        }
        String[] parts = pkg.split("\\.");
        for (int i = parts.length - 1; i >= 0; i--) {
            String seg = parts[i].trim();
            if (seg.isEmpty() || "android".equalsIgnoreCase(seg) || "tv".equalsIgnoreCase(seg)
                    || "app".equalsIgnoreCase(seg) || "one".equalsIgnoreCase(seg)
                    || "ninja".equalsIgnoreCase(seg) || "livingroom".equalsIgnoreCase(seg)) {
                continue;
            }
            return Character.toUpperCase(seg.charAt(0)) + seg.substring(1);
        }
        return pkg;
    }

    /** Returns {@code true} when an {@link AccessibilityEvent} type may indicate input focus or text edits. */
    public static boolean isRelevantAccessibilityEvent(int eventType) {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED;
    }

    /** Returns {@code true} if {@code snapshot} is active and within {@link #SNAPSHOT_TTL_MS} of {@code nowMs}. */
    public static boolean isSnapshotFresh(InputSnapshot snapshot, long nowMs) {
        if (snapshot == null || !snapshot.active || snapshot.updatedAtMs <= 0L) {
            return false;
        }
        long age = nowMs - snapshot.updatedAtMs;
        return age >= -5_000L && age <= SNAPSHOT_TTL_MS;
    }

    /**
     * Resolves which focused input snapshot to expose on {@code /api/status}:
     * <ol>
     *   <li>If SoarTV's {@link RemoteControlServer.Host} is attached and reports an active input (Liquid Glass modal or
     *       Search screen), prefer the live host snapshot.</li>
     *   <li>Else if an external snapshot from {@link RemoteKeyService} is active and fresh:
     *       use it whenever {@code !hostAttached} (third-party app like Netflix is in the foreground), OR when
     *       {@code externalSnapshot.packageName} matches {@code selfPackage} (an Android {@code AlertDialog}
     *       {@code EditText} opened inside SoarTV while {@code HomeView} is still attached).</li>
     *   <li>Otherwise return {@link InputSnapshot#INACTIVE} so stale third-party snapshots never leak onto SoarTV Home.</li>
     * </ol>
     */
    public static InputSnapshot resolveActiveSnapshot(
            boolean hostAttached,
            InputSnapshot hostSnapshot,
            InputSnapshot externalSnapshot,
            String selfPackage,
            long nowMs) {
        if (hostAttached && hostSnapshot != null && hostSnapshot.active) {
            return hostSnapshot;
        }
        if (isSnapshotFresh(externalSnapshot, nowMs)) {
            if (!hostAttached) {
                return externalSnapshot;
            }
            if (selfPackage != null && !selfPackage.isEmpty()
                    && selfPackage.equals(externalSnapshot.packageName)) {
                return externalSnapshot;
            }
        }
        return InputSnapshot.INACTIVE;
    }

    /** Identifies short submit/search action labels for fallback submit clicking on API &lt; 30 or custom keyboards. */
    public static boolean isSubmitButtonLabel(CharSequence label) {
        if (label == null) return false;
        String s = label.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 16) return false;
        return s.equals("search") || s.equals("go") || s.equals("done")
                || s.equals("submit") || s.equals("send") || s.equals("enter")
                || s.equals("next") || s.equals("חיפוש") || s.equals("אישור") || s.equals("שלח");
    }

    /** Atomically persists {@code snapshot} to {@code filesDir/tv_focused_input.json}. */
    public static void writeSnapshot(File filesDir, InputSnapshot snapshot) {
        if (filesDir == null || snapshot == null) return;
        File target = new File(filesDir, SNAPSHOT_FILENAME);
        File tmp = new File(filesDir, SNAPSHOT_FILENAME + ".tmp");
        byte[] bytes = snapshot.toJsonString().getBytes(StandardCharsets.UTF_8);
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(bytes);
                fos.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target, false)) {
                    fos.write(bytes);
                    fos.getFD().sync();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable t) {
            AppLog.w("TvInputFieldSync: failed writing snapshot", t);
        }
    }

    /** Reads the latest cross-process snapshot from {@code filesDir/tv_focused_input.json}. */
    public static InputSnapshot readSnapshot(File filesDir) {
        if (filesDir == null) return InputSnapshot.INACTIVE;
        File target = new File(filesDir, SNAPSHOT_FILENAME);
        if (!target.exists() || target.length() <= 0 || target.length() > MAX_SNAPSHOT_BYTES) {
            return InputSnapshot.INACTIVE;
        }
        try {
            byte[] buf = new byte[(int) target.length()];
            int read;
            try (FileInputStream fis = new FileInputStream(target)) {
                read = fis.read(buf);
            }
            if (read <= 0) return InputSnapshot.INACTIVE;
            return InputSnapshot.fromJsonString(new String(buf, 0, read, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            AppLog.w("TvInputFieldSync: failed reading snapshot", t);
            return InputSnapshot.INACTIVE;
        }
    }

    /** Convenience overload reading from {@code context.getFilesDir()}. */
    public static InputSnapshot readSnapshot(Context context) {
        return context != null ? readSnapshot(context.getFilesDir()) : InputSnapshot.INACTIVE;
    }

    /**
     * Dispatches an explicit package-scoped broadcast from {@link RemoteControlServer} ({@code :home})
     * to {@link RemoteKeyService} ({@code :keys}) to replace/submit text in the focused TV input field.
     */
    public static void dispatchSetTextBroadcast(Context context, String text, boolean submit) {
        if (context == null) return;
        try {
            Intent intent = new Intent(ACTION_REMOTE_SET_TEXT);
            intent.setPackage(context.getPackageName());
            intent.putExtra(EXTRA_TEXT, text != null ? text : "");
            intent.putExtra(EXTRA_SUBMIT, submit);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            AppLog.w("TvInputFieldSync: failed dispatching set-text broadcast", t);
        }
    }

    /** Attaches the internal broadcast receiver when {@link RemoteKeyService} connects. */
    public void attach(AccessibilityService service) {
        if (service == null) return;
        detachReceiver(service);
        setTextReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_REMOTE_SET_TEXT.equals(intent.getAction())) return;
                String text = intent.getStringExtra(EXTRA_TEXT);
                boolean submit = intent.getBooleanExtra(EXTRA_SUBMIT, false);
                worker.execute(() -> applyRemoteText(service, text != null ? text : "", submit));
            }
        };
        try {
            IntentFilter filter = new IntentFilter(ACTION_REMOTE_SET_TEXT);
            if (Build.VERSION.SDK_INT >= 33) {
                service.registerReceiver(setTextReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                service.registerReceiver(setTextReceiver, filter);
            }
        } catch (Throwable t) {
            AppLog.w("TvInputFieldSync: registerReceiver failed", t);
            setTextReceiver = null;
        }
        scheduleInspect(service);
    }

    /** Cleans up the receiver and clears active input state when {@link RemoteKeyService} disconnects. */
    public void detach(AccessibilityService service) {
        detachReceiver(service);
        if (service != null) {
            publishSnapshot(service.getFilesDir(), InputSnapshot.INACTIVE);
        }
    }

    private void detachReceiver(AccessibilityService service) {
        if (service != null && setTextReceiver != null) {
            try {
                service.unregisterReceiver(setTextReceiver);
            } catch (Throwable ignored) {
            }
        }
        setTextReceiver = null;
    }

    /**
     * Called from {@link RemoteKeyService#onAccessibilityEvent(AccessibilityEvent)}.
     * Performs zero synchronous Binder IPC on the main looper; coalesces inspection onto {@link #worker}.
     */
    public void onAccessibilityEvent(AccessibilityService service, AccessibilityEvent event) {
        if (service == null || event == null) return;
        if (!isRelevantAccessibilityEvent(event.getEventType())) return;
        scheduleInspect(service);
    }

    public void onInterrupt() {
        inspectScheduled.set(false);
    }

    private void scheduleInspect(AccessibilityService service) {
        long now = System.currentTimeMillis();
        long elapsed = now - lastInspectMs;
        if (elapsed >= INSPECT_THROTTLE_MS) {
            if (inspectScheduled.compareAndSet(false, true)) {
                worker.execute(() -> runInspect(service));
            }
        } else if (delayedScheduled.compareAndSet(false, true)) {
            long delay = Math.max(20L, INSPECT_THROTTLE_MS - elapsed);
            try {
                worker.schedule(() -> {
                    delayedScheduled.set(false);
                    if (inspectScheduled.compareAndSet(false, true)) {
                        runInspect(service);
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                delayedScheduled.set(false);
            }
        }
    }

    private void runInspect(AccessibilityService service) {
        try {
            lastInspectMs = System.currentTimeMillis();
            AccessibilityNodeInfo editable = findActiveEditableNode(service);
            if (editable == null) {
                publishSnapshot(service.getFilesDir(), InputSnapshot.INACTIVE);
                return;
            }
            try {
                InputSnapshot snap = snapshotFromNode(service, editable, null);
                publishSnapshot(service.getFilesDir(), snap);
            } finally {
                safeRecycle(editable);
            }
        } catch (Throwable t) {
            AppLog.w("TvInputFieldSync: inspect failed", t);
        } finally {
            inspectScheduled.set(false);
        }
    }

    private void applyRemoteText(AccessibilityService service, String text, boolean submit) {
        AccessibilityNodeInfo editable = null;
        try {
            editable = findActiveEditableNode(service);
            if (editable == null) {
                return;
            }
            String cleanText = sanitizeLength(text);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText);
            boolean updated = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (updated) {
                Bundle sel = new Bundle();
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cleanText.length());
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cleanText.length());
                editable.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
                InputSnapshot snap = snapshotFromNode(service, editable, cleanText);
                publishSnapshot(service.getFilesDir(), snap);
            }
            if (submit) {
                boolean submitted = false;
                if (Build.VERSION.SDK_INT >= 30) {
                    submitted = editable.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                }
                if (!submitted) {
                    clickSubmitButtonInActiveWindow(service);
                }
            }
        } catch (Throwable t) {
            AppLog.w("TvInputFieldSync: applyRemoteText failed", t);
        } finally {
            safeRecycle(editable);
        }
    }

    private InputSnapshot snapshotFromNode(
            AccessibilityService service,
            AccessibilityNodeInfo node,
            String overrideText) {
        boolean isPassword = node.isPassword();
        boolean showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText();
        CharSequence hintCs = Build.VERSION.SDK_INT >= 26 ? node.getHintText() : null;
        String safeText = overrideText != null
                ? (isPassword ? "" : sanitizeLength(overrideText))
                : extractSafeText(node.getText(), hintCs, showingHint, isPassword);
        String safeHint = hintCs != null ? sanitizeLength(hintCs.toString()) : "";
        String pkg = node.getPackageName() != null ? node.getPackageName().toString() : "";
        String pmLabel = resolvePmLabel(service, pkg);
        String label = friendlyAppName(pkg, pmLabel);
        long now = System.currentTimeMillis();
        return new InputSnapshot(true, safeText, safeHint, pkg, label, isPassword, 0L, now);
    }

    private void publishSnapshot(File filesDir, InputSnapshot candidate) {
        InputSnapshot prev = lastWrittenSnapshot;
        if (prev != null && prev.sameContent(candidate)) {
            if (!candidate.active) {
                return;
            }
            // Refresh timestamp periodically so TTL never expires while user keeps a field focused
            if (candidate.updatedAtMs - prev.updatedAtMs < 60_000L) {
                return;
            }
            InputSnapshot refreshed = new InputSnapshot(
                    candidate.active,
                    candidate.text,
                    candidate.hint,
                    candidate.packageName,
                    candidate.appLabel,
                    candidate.isPassword,
                    prev.revision,
                    candidate.updatedAtMs
            );
            lastWrittenSnapshot = refreshed;
            writeSnapshot(filesDir, refreshed);
            return;
        }
        long nextRev = ++revisionCounter;
        InputSnapshot stamped = candidate.active
                ? new InputSnapshot(
                        true,
                        candidate.text,
                        candidate.hint,
                        candidate.packageName,
                        candidate.appLabel,
                        candidate.isPassword,
                        nextRev,
                        candidate.updatedAtMs)
                : new InputSnapshot(false, "", "", "", "", false, nextRev, System.currentTimeMillis());
        lastWrittenSnapshot = stamped;
        writeSnapshot(filesDir, stamped);
    }

    /**
     * Locates the currently active editable input node on screen.
     * Checks {@code FOCUS_INPUT}, {@code FOCUS_ACCESSIBILITY}, and finally performs a bounded BFS over
     * the active foreground application window so apps like Netflix / YouTube Leanback (where D-pad focus
     * rests on the on-screen A-Z grid beside the visible search box) are detected seamlessly.
     */
    private static AccessibilityNodeInfo findActiveEditableNode(AccessibilityService service) {
        AccessibilityNodeInfo focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (isUsableEditableNode(focus)) {
            return focus;
        }
        safeRecycle(focus);

        AccessibilityNodeInfo a11yFocus = service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (isUsableEditableNode(a11yFocus)) {
            return a11yFocus;
        }
        safeRecycle(a11yFocus);

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo found = searchEditableInTree(root);
            safeRecycle(root);
            if (found != null) {
                return found;
            }
        }

        java.util.List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo win : windows) {
                if (win == null || win.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo winRoot = win.getRoot();
                if (winRoot == null) continue;
                AccessibilityNodeInfo found = searchEditableInTree(winRoot);
                safeRecycle(winRoot);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo searchEditableInTree(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo firstVisibleFallback = null;
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_TRAVERSE_NODES) {
            AccessibilityNodeInfo cur = queue.poll();
            if (cur == null) continue;
            visited++;

            if (isUsableEditableNode(cur)) {
                if (cur.isFocused()) {
                    safeRecycle(firstVisibleFallback);
                    while (!queue.isEmpty()) safeRecycle(queue.poll());
                    return cur;
                }
                if (firstVisibleFallback == null) {
                    firstVisibleFallback = AccessibilityNodeInfo.obtain(cur);
                }
            }

            int childCount = cur.getChildCount();
            for (int i = 0; i < childCount && visited + queue.size() < MAX_TRAVERSE_NODES; i++) {
                AccessibilityNodeInfo child = cur.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
            safeRecycle(cur);
        }

        while (!queue.isEmpty()) safeRecycle(queue.poll());
        return firstVisibleFallback;
    }

    private static boolean isUsableEditableNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isEnabled() || !node.isVisibleToUser()) {
            return false;
        }
        if (node.isEditable()) {
            return true;
        }
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("EditText")) {
            return true;
        }
        return false;
    }

    private static void clickSubmitButtonInActiveWindow(AccessibilityService service) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        safeRecycle(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_TRAVERSE_NODES) {
            AccessibilityNodeInfo cur = queue.poll();
            if (cur == null) continue;
            visited++;
            if (cur.isClickable() && cur.isEnabled() && cur.isVisibleToUser()
                    && (isSubmitButtonLabel(cur.getText()) || isSubmitButtonLabel(cur.getContentDescription()))) {
                cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                safeRecycle(cur);
                while (!queue.isEmpty()) safeRecycle(queue.poll());
                return;
            }
            int childCount = cur.getChildCount();
            for (int i = 0; i < childCount && visited + queue.size() < MAX_TRAVERSE_NODES; i++) {
                AccessibilityNodeInfo child = cur.getChild(i);
                if (child != null) queue.add(child);
            }
            safeRecycle(cur);
        }
        while (!queue.isEmpty()) safeRecycle(queue.poll());
    }

    private static String resolvePmLabel(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return "";
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(info);
            return cs != null ? cs.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void safeRecycle(AccessibilityNodeInfo node) {
        if (node != null) {
            try {
                node.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String sanitizeLength(String s) {
        if (s == null) return "";
        return s.length() > MAX_TEXT_LENGTH ? s.substring(0, MAX_TEXT_LENGTH) : s;
    }

    private static String escapeJson(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static Map<String, String> parseFlatJsonObject(String json) {
        Map<String, String> out = new HashMap<>();
        int n = json.length();
        int i = 0;
        while (i < n && json.charAt(i) != '{') i++;
        if (i >= n) return out;
        i++; // skip '{'

        while (i < n) {
            while (i < n && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
            if (i >= n || json.charAt(i) == '}') break;
            if (json.charAt(i) != '"') break;
            int[] next = new int[1];
            String key = readJsonString(json, i, next);
            if (key == null) break;
            i = next[0];
            while (i < n && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= n || json.charAt(i) != ':') break;
            i++;
            while (i < n && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= n) break;

            String val;
            if (json.charAt(i) == '"') {
                val = readJsonString(json, i, next);
                if (val == null) break;
                i = next[0];
            } else {
                int start = i;
                while (i < n && json.charAt(i) != ',' && json.charAt(i) != '}' && !Character.isWhitespace(json.charAt(i))) {
                    i++;
                }
                val = json.substring(start, i);
            }
            out.put(key, val);
        }
        return out;
    }

    private static String readJsonString(String s, int startQuote, int[] nextIdx) {
        int n = s.length();
        if (startQuote >= n || s.charAt(startQuote) != '"') return null;
        StringBuilder sb = new StringBuilder();
        int i = startQuote + 1;
        while (i < n) {
            char c = s.charAt(i++);
            if (c == '"') {
                nextIdx[0] = i;
                return sb.toString();
            }
            if (c == '\\' && i < n) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 <= n) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ignored) {
                            }
                            i += 4;
                        }
                        break;
                    default:
                        sb.append(esc);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private static long parseLongSafe(String raw, long fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
