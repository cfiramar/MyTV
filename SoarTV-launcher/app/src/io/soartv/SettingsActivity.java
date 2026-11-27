package io.soartv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Launcher Mode Switch &amp; Safety Management Screen for SoarTV (launched from the TV's system app grid
 * or when switching between SoarTV Launcher and the TV's Original factory home screen).
 *
 * <p><b>Why:</b> Isolates the {@link HomeMode} launcher-replacement switch ({@code SoarTV <-> Original}),
 * live memory/speed comparison telemetry, system safety notices, and direct entry into {@link HomeActivity}'s
 * Two-Panel Settings or 4K Screensaver preview.
 *
 * <p><b>What:</b> Renders a Liquid Glass launcher management view and delegates mode transitions safely to
 * {@link HomeMode} in the main process while preserving {@link Watchdog} rollback protection.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;
    private boolean previewOnly;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.request4kDisplayMode(getWindow());
        if (openFireHome(getIntent())) return;
        Watchdog.install(this);
        Updates.schedule(this);
        Ui.init(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        previewOnly = getIntent() != null && getIntent().getBooleanExtra("screensaver", false);
        if (previewOnly) {
            getIntent().removeExtra("screensaver");
            showPreview();
        } else {
            view = new SettingsView(this);
            setContentView(view);
        }
    }

    private boolean openFireHome(Intent intent) {
        if (!HomeMode.AMAZON || intent == null || !(intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                || intent.hasCategory(Intent.CATEGORY_LAUNCHER)) || intent.getBooleanExtra("screensaver", false)) {
            return false;
        }
        startActivity(new Intent(this, FireHomeActivity.class));
        finish();
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (getIntent() != null && getIntent().getBooleanExtra("screensaver", false)) {
            previewOnly = true;
            getIntent().removeExtra("screensaver");
            if (view != null) view.pause();
            if (preview == null) showPreview();
            return;
        }
        if (view != null && preview == null) {
            view.resume();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openFireHome(intent)) return;
        setIntent(intent);
    }

    @Override protected void onPause() {
        if (preview != null) {
            preview.stop();
            preview = null;
            if (previewOnly) {
                finish();
            } else if (view != null) {
                setContentView(view);
            }
        }
        if (view != null) view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            if (preview.handleKeyEvent(event)) return true;
            int code = event.getKeyCode();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean backOrHome = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_HOME;
            if (ok || backOrHome) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    closePreview();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (view != null && view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        if (previewOnly || view == null) {
            finish();
            return;
        }
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_GAP = 8, CHOICE_H = 104;
        private static final float MAIN_TOP = 160, ROW_H = 66f, MAIN_GAP = 8;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[6];
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject();
        private JSONObject updates = new JSONObject();
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy, framePending, stalled;
        private String pollStamp = "", installed = "", errorShown;
        private StaticLayout errorLayout;
        private Toast whyToast;
        private final Spoken spoken;
        private final boolean phone;
        private long startedAt, lastNanos, pendingAt, lastSky, requestedAt;
        private float skySeconds, touchX, touchY, k = 1;
        private int w, h, hoveredRow = -1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                updates = Updates.state(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) {
                    pendingAt = 0;
                    stalled = false;
                } else if (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt >= 10_000) {
                    pendingAt = 0;
                    if (!ModeJob.started(getContext(), requestedAt)) {
                        stalled = true;
                        AppLog.w("the switch's job didn't start" + (RemoteKeys.TCL ? " (TCL's AUTO_START not allowed?)" : ""));
                        announceForAccessibility(stalledText(getContext()));
                    }
                }
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                String stamp = state.toString() + updates.toString() + ":" + pendingAt + ":" + stalled + ":" + busy()
                        + ":" + System.currentTimeMillis() / 60_000;
                if (!stamp.equals(pollStamp)) invalidate();
                pollStamp = stamp;
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            Shaders.settings(scene.palette);
            scene.setMotion(0.5f, 0.2f);
            phone = HomeMode.phone(a);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("SoarTV");
            installed = Updates.installedVersion(a);
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) { focus = id - 1; invalidate(); }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        private int rowCount() {
            return screen == HOME_CHOICE ? 2 : 5;
        }

        private String unavailable(int row) {
            if (screen == MAIN && row == 0) {
                return Capabilities.missing(getContext(), Capabilities.NEED_SWITCH);
            }
            return null;
        }

        private String rowText(int row) {
            String why = unavailable(row);
            String val = rowValueText(row);
            return why == null ? val : val + ", " + Ui.tr("Unavailable") + ". " + why;
        }

        private String rowValueText(int row) {
            if (screen == HOME_CHOICE) {
                String name = row == 0 ? "SoarTV Launcher" : Ui.tr("Original");
                boolean inUse = (row == 0) == freeActive();
                return name + (inUse ? Ui.tr(". In use") : "");
            }
            if (row == 0) {
                String busyText = busyText();
                String value = busy() ? (busyText.isEmpty() ? Ui.tr("Starting…") : busyText)
                        : freeActive() ? "SoarTV" : Ui.tr("Original");
                return Ui.trf("Home Screen, {mode}", "mode", value);
            }
            String[] names = {
                Ui.tr("Open SoarTV Home & Settings"),
                Ui.tr("Screensaver, preview"),
                Ui.tr("Check for Updates"),
                Ui.tr("TV Settings")
            };
            return row - 1 < names.length ? names[row - 1] : "";
        }

        private RectF rowBox(int row) {
            float top = screen == MAIN ? MAIN_TOP + row * (ROW_H + MAIN_GAP) : LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
            float height = screen == MAIN ? ROW_H : CHOICE_H;
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            invalidate();
            spoken.readAgain();
        }

        void pause() {
            if (running && !busy() && memoryMissing()) {
                ModeJob.schedule(getContext(), ModeJob.MEMORY, state.optString("mode"), 5_000);
            }
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
            framePending = false;
        }

        @Override public void doFrame(long frameTimeNanos) {
            framePending = false;
            if (!running) return;
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            Symbols.warm(getContext(), "sparkles", 150 * k);
            Symbols.warm(getContext(), "chevron_right", 22 * k);
            Symbols.warm(getContext(), "check", 26 * k);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private boolean memorySoon() {
            return System.currentTimeMillis() - state.optLong("since") < 180_000;
        }

        private boolean memoryMissing() {
            String mode = state.optString("mode");
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject m = metrics == null ? null : metrics.optJSONObject(mode);
            if (m == null || !state.has("since") || memorySoon()) return false;
            if (HomeMode.FREE.equals(mode) ? !freeActive() : state.optString("originalHome").isEmpty()) return false;
            return !m.has("memAvailableMB") || !m.has("homePssMB");
        }

        private static String stalledText(Context c) {
            String pkg = c.getPackageName();
            return RemoteKeys.TCL
                    ? Ui.trf("TCL blocks SoarTV from running in the background, so nothing changed. From a computer, "
                            + "run this, then try again: {command}", "command",
                            "adb shell \"appops set " + pkg + " AUTO_START allow || appops set " + pkg + " APP_AUTO_START allow\"")
                    : Ui.tr("The TV didn't let SoarTV run in the background, so nothing changed. Try again in a minute.");
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            boolean moving = screen == MAIN && busy();
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                moving |= pop[i].step(dt);
            }
            if (running && moving && !framePending) {
                framePending = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            skySeconds += dt;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(skySeconds);
            }
            scene.drawBackground(c);

            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
            c.drawText("SoarTV", cx, 612 * k, title);
            c.drawText(Ui.tr("Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);

            if (screen == MAIN) drawMain(c, seconds);
            else drawChoice(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            String mode = freeActive() ? "SoarTV" : Ui.tr("Native (Original)");
            String[] labels = {
                Ui.tr("Home Screen"),
                Ui.tr("Open SoarTV Settings"),
                Ui.tr("Screensaver"),
                Ui.tr("Updates"),
                Ui.tr("TV Settings")
            };
            String[] values = {
                working ? "" : mode,
                "",
                Ui.tr("Preview"),
                Updates.summary(updates, installed),
                ""
            };
            for (int i = 0; i < labels.length; i++) {
                float y = (MAIN_TOP + i * (ROW_H + MAIN_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = unavailable(i) != null;
                int ink = off ? blend(0x73FFFFFF, 0x731C1C1E, f) : blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (off) {
                    rowValue.setColor(blend(0x59FFFFFF, 0x591C1C1E, f));
                    c.drawText(Ui.tr("Unavailable"), right, base, rowValue);
                } else if (i == 0 && working) {
                    String text = busyText.isEmpty() ? Ui.tr("Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else if (!values[i].isEmpty()) {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(values[i], right, base, rowValue);
                } else {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                }
            }

            float y = (MAIN_TOP + labels.length * (ROW_H + MAIN_GAP) + 32) * k;
            String error = phone ? HomeMode.phoneText() : stalled ? stalledText(getContext()) : state.optString("error");
            if (working && busyText.equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))) {
                c.drawText(Ui.tr("On the TV: tick “Always allow” and press OK."), LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(stalled ? 8 : 5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
            y = drawComparison(c, y);
            drawNotices(c, y + 40 * k);
        }

        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            if (a == null && b == null) {
                String line = Ui.tr("Measured at each switch, for both home screens.");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
                return y + 82 * k;
            }
            cellValue.setColor(0x73FFFFFF);
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText("SoarTV", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            boolean soon = !busy() && memorySoon();
            JSONObject current = HomeMode.FREE.equals(state.optString("mode")) ? b : a;
            String[][] rows = {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Free memory"), "memAvailableMB"},
                    {Ui.tr("Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                boolean waits = soon && i > 0;
                c.drawText(metric(a, rows[i][1], waits && current == a), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1], waits && current == b), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 125 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
        }

        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Home Screen / Launcher"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {
                {"SoarTV Launcher", Ui.tr("Custom Liquid Glass home screen, Gaming, Smart-Home & 4K Screensaver")},
                {Ui.tr("Native Launcher (Original)"), Ui.tr("Restore the TV's factory home screen")}
            };
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key, boolean soon) {
            if (m == null || !m.has(key)) return soon ? "…" : "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return "SoarTV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "SoarTV";
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchX = e.getX();
                    touchY = e.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (Math.hypot(e.getX() - touchX, e.getY() - touchY) < 24 * k) {
                        int row = rowAt(e.getX(), e.getY());
                        if (row >= 0) {
                            focus = row;
                            activate();
                            invalidate();
                            spoken.focusMoved();
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean onHoverEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
                int row = rowAt(e.getX(), e.getY()), code = row < 0 ? -1 : screen * 1000 + row;
                if (code != hoveredRow && row >= 0 && row != focus) {
                    focus = row;
                    invalidate();
                    spoken.focusMoved();
                }
                hoveredRow = code;
            }
            return true;
        }

        @Override public boolean onGenericMotionEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v != 0) {
                    if (v < 0 && focus < rowCount() - 1) focus++;
                    else if (v > 0 && focus > 0) focus--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                }
            }
            return super.onGenericMotionEvent(e);
        }

        private int rowAt(float x, float y) {
            if (x < LIST_X * k || x > LIST_R * k) return -1;
            for (int i = 0; i < rowCount(); i++) {
                RectF r = rowBox(i);
                if (!r.isEmpty() && r.contains(x, y)) return i;
            }
            return -1;
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            screen = MAIN;
                            focus = 0;
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        private boolean tellWhy() {
            String why = unavailable(focus);
            if (why == null) return false;
            if (whyToast != null) whyToast.cancel();
            whyToast = Toast.makeText(getContext(), why, Toast.LENGTH_LONG);
            whyToast.show();
            return true;
        }

        private void activate() {
            Context c = getContext();
            if (tellWhy()) return;
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    requestedAt = HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                    stalled = false;
                }
                screen = MAIN;
                focus = 0;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                Intent i = new Intent(activity, HomeActivity.class)
                        .putExtra(HomeActivity.EXTRA_OPEN_SETTINGS, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                activity.startActivity(i);
            } else if (focus == 2) {
                pause();
                activity.showPreview();
            } else if (focus == 3) {
                if (Updates.ready(updates, installed)) {
                    Updates.install(activity);
                } else if (!Updates.busy(updates)) {
                    ModeJob.schedule(c, ModeJob.UPDATE, "", 0);
                }
            } else {
                HomeApps.open(activity, HomeApps.tvSettings(), null);
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
