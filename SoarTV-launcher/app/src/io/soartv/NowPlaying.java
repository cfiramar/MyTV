package io.soartv;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads active media sessions AND captures actionable Android TV system notifications into SoarTV
 * while automatically muting/dismissing user-polls, feedback requests, and background service noise.
 */
public class NowPlaying extends NotificationListenerService {

    /** Immutable record of a filtered Android TV system notification surfaced inside SoarTV. */
    public static final class SystemNotice {
        public final String key;
        public final String pkg;
        public final String title;
        public final String text;
        public final long postedAt;

        public SystemNotice(String key, String pkg, String title, String text, long postedAt) {
            this.key = key == null ? "" : key;
            this.pkg = pkg == null ? "" : pkg;
            this.title = title == null ? "" : title.trim();
            this.text = text == null ? "" : text.trim();
            this.postedAt = postedAt;
        }
    }

    private static final CopyOnWriteArrayList<SystemNotice> SYSTEM_NOTICES = new CopyOnWriteArrayList<>();
    private static volatile NowPlaying activeService;

    /**
     * Returns {@code true} if the notification is a user-poll / feedback / survey prompt.
     * User-polls are always muted and never shown in SoarTV.
     */
    public static boolean isUserPoll(String pkg, String title, String text, String channelId) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        if (p.contains("analytics") || p.contains("statistic") || p.contains("anoki") || p.contains("feedback")) {
            return true;
        }
        String combined = ((title == null ? "" : title) + " "
                + (text == null ? "" : text) + " "
                + (channelId == null ? "" : channelId)).toLowerCase(Locale.ROOT);
        String[] pollKeywords = {
            "poll", "survey", "feedback", "rate your", "how satisfied",
            "tell us what you think", "help us improve", "questionnaire",
            "share your thoughts", "quick question", "would you recommend",
            "rate this", "user study", "research survey"
        };
        for (String kw : pollKeywords) {
            if (combined.contains(kw)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if a notification is either a muted user-poll or low-level background daemon noise.
     */
    public static boolean isMutedPollOrNoise(String pkg, String title, String text, String channelId) {
        if (isUserPoll(pkg, title, text, channelId)) return true;
        String t = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        String b = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        String c = channelId == null ? "" : channelId.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() && b.isEmpty()) return true;
        if (c.contains("media_playback") || c.contains("server_service") || c.contains("dial_service") || c.contains("ranker_group")) {
            return true;
        }
        if (t.contains("serial console enabled") || t.contains("settings is running")
                || t.contains("dialservice") || b.contains("running server in the background")) {
            return true;
        }
        return false;
    }

    /** Returns an unmodifiable snapshot of active, non-poll Android TV system notifications. */
    public static List<SystemNotice> getSystemNotices() {
        return Collections.unmodifiableList(new ArrayList<>(SYSTEM_NOTICES));
    }

    /** Dismisses a specific system notification by key, or removes it from the SoarTV list. */
    public static void dismissNotice(String key) {
        if (key == null || key.isEmpty()) return;
        SYSTEM_NOTICES.removeIf(n -> key.equals(n.key));
        NowPlaying svc = activeService;
        if (svc != null) {
            try {
                svc.cancelNotification(key);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Clears all dismissible system notifications from SoarTV. */
    public static void clearAllNotices() {
        List<SystemNotice> copy = new ArrayList<>(SYSTEM_NOTICES);
        SYSTEM_NOTICES.clear();
        NowPlaying svc = activeService;
        if (svc != null) {
            for (SystemNotice n : copy) {
                try {
                    svc.cancelNotification(n.key);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    @Override public void onListenerConnected() {
        activeService = this;
        refreshActiveNotifications();
    }

    @Override public void onListenerDisconnected() {
        if (activeService == this) activeService = null;
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification notif = sbn.getNotification();
        if (notif == null) return;
        Bundle extras = notif.extras;
        String title = extras != null && extras.getCharSequence(Notification.EXTRA_TITLE) != null
                ? extras.getCharSequence(Notification.EXTRA_TITLE).toString() : "";
        String text = extras != null && extras.getCharSequence(Notification.EXTRA_TEXT) != null
                ? extras.getCharSequence(Notification.EXTRA_TEXT).toString() : "";
        String channel = notif.getChannelId();
        if (isUserPoll(sbn.getPackageName(), title, text, channel)) {
            if (sbn.isClearable()) {
                try { cancelNotification(sbn.getKey()); } catch (RuntimeException ignored) {}
            }
            return;
        }
        refreshActiveNotifications();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        refreshActiveNotifications();
    }

    private void refreshActiveNotifications() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            List<SystemNotice> next = new ArrayList<>();
            for (StatusBarNotification sbn : active) {
                if (sbn == null) continue;
                Notification notif = sbn.getNotification();
                if (notif == null) continue;
                if ((notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                if (Notification.CATEGORY_TRANSPORT.equals(notif.category)) continue;
                Bundle extras = notif.extras;
                String title = extras != null && extras.getCharSequence(Notification.EXTRA_TITLE) != null
                        ? extras.getCharSequence(Notification.EXTRA_TITLE).toString() : "";
                String text = extras != null && extras.getCharSequence(Notification.EXTRA_TEXT) != null
                        ? extras.getCharSequence(Notification.EXTRA_TEXT).toString() : "";
                String channel = notif.getChannelId();
                if (isUserPoll(sbn.getPackageName(), title, text, channel)) {
                    if (sbn.isClearable()) {
                        try { cancelNotification(sbn.getKey()); } catch (RuntimeException ignored) {}
                    }
                    continue;
                }
                if (isMutedPollOrNoise(sbn.getPackageName(), title, text, channel)) continue;
                next.add(new SystemNotice(sbn.getKey(), sbn.getPackageName(), title, text, sbn.getPostTime()));
            }
            SYSTEM_NOTICES.clear();
            SYSTEM_NOTICES.addAll(next);
        } catch (RuntimeException ignored) {
        }
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Whether Android lets us read the sessions: our listener is allowed. */
    static boolean allowed(Context c) {
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean asked;

    /** A screen's watch: start() when it shows, stop() when it's hidden. text is null while nothing plays. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();   // some players report every second
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        volatile String text;
        /** The title alone (the app's package when it gives none): the line's other parts can change every second. */
        volatile String title;

        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            if (on || !Prefs.nowPlaying()) return;   // off in Home Settings: nothing watched at all
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                // On, but not allowed (it was on when Original or the watchdog took it back): asked once, as SoarTV
                // mode's switch itself never allows it. A TV that refused it shows the row unavailable instead.
                if (!asked && Capabilities.adb(context) && !Capabilities.refused(context, Capabilities.LISTENER)) {
                    asked = true;
                    HomeMode.nowPlayingAccess(context);
                }
            }
        }

        void stop() {
            if (!on) return;
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private void update() {
            String now = null, nowTitle = null;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                // A stream that buffers is still playing: the line doesn't blink back to the prices.
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING) {
                    now = describe(mc);
                    MediaMetadata m = mc.getMetadata();
                    CharSequence t = m == null ? null : m.getDescription().getTitle();
                    nowTitle = now == null ? null : TextUtils.isEmpty(t) ? mc.getPackageName() : t.toString().trim();
                    break;
                }
            }
            if (Objects.equals(now, text)) return;
            title = nowTitle;
            text = now;
            changed.run();
        }

        /** "Title · Artist · App": the parts the app gives, then its name; null when there's none of them. */
        private String describe(MediaController mc) {
            List<String> parts = new ArrayList<>();
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            if (d != null && !TextUtils.isEmpty(d.getTitle())) parts.add(d.getTitle().toString().trim());
            if (d != null && !TextUtils.isEmpty(d.getSubtitle())) parts.add(d.getSubtitle().toString().trim());
            String app = labels.get(mc.getPackageName());
            if (app == null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";   // an app without a launcher icon is hidden from us (Android 11+): no name to show
                }
                labels.put(mc.getPackageName(), app);
            }
            if (!app.isEmpty() && !parts.contains(app)) parts.add(app);
            return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
        }
    }
}
