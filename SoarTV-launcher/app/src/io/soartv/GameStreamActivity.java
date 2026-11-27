package io.soartv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.nvstream.jni.MoonBridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Native full-screen Sunshine / GameStream player Activity powered by {@link MoonBridge}
 * (`libmoonlight-core.so`), hardware {@link MediaCodec} VSYNC pacing, and low-latency {@link AudioTrack}.
 *
 * <p><b>Why:</b> Replaces all external Moonlight (`com.limelight`) Activity handoffs so the user
 * never sees Moonlight's UI, enjoys smooth VSYNC-locked hardware decoding on Xiaomi / Google TV,
 * and gets deterministic PC game closure on TV Remote {@code Back}/{@code Home} plus direct return
 * to SoarTV's {@code Gaming} screen on in-game quit.
 * <p><b>What:</b> Orchestrates {@link SunshineStreamClient} mTLS session launch, configures low-latency
 * hardware AVC/HEVC/AV1 {@link MediaCodec} + {@link Choreographer} frame pacing, renders surround/stereo
 * PCM audio, forwards up to 4 gamepads + virtual mouse emulation + rumble, and enforces deterministic
 * teardown via {@link GamingZone#terminateActivePCGameNow}.
 */
public final class GameStreamActivity extends Activity
        implements MoonBridge.BridgeCallbacks, SurfaceHolder.Callback, Choreographer.FrameCallback {

    public static final String EXTRA_HOST = "io.soartv.stream.HOST";
    public static final String EXTRA_GAME_ID = "io.soartv.stream.GAME_ID";
    public static final String EXTRA_GAME_TITLE = "io.soartv.stream.GAME_TITLE";
    public static final String EXTRA_SUNSHINE_APP = "io.soartv.stream.SUNSHINE_APP";

    private static final int LAN_PACKET_SIZE = 1392;
    private static final int STREAMING_LOCALLY = 0;
    private static final long DRAIN_INPUT_TIMEOUT_US = 2_000L;
    private static final long DEQUEUE_OUTPUT_TIMEOUT_US = 4_000L;
    private static final long TELEMETRY_INTERVAL_MS = 2_000L;
    private static final long IDR_REQUEST_COOLDOWN_MS = 500L;
    private static final int PENDING_FRAME_RING_SLOTS = 4;
    private static final int PENDING_FRAME_INITIAL_BYTES = 524_288;
    private static final int MAX_OPERATING_RATE_CAP = 240;
    private static final long MOUSE_EMULATION_TOGGLE_HOLD_MS = 750L;
    private static final long MOUSE_TICK_INTERVAL_MS = 16L;
    private static final float STICK_DEADZONE = 0.09f;
    private static final float TRIGGER_DEADZONE = 0.04f;
    private static final float MOUSE_MAX_SPEED_PX_PER_TICK = 18.0f;

    // Limelight controller button bitmasks (Limelight.h)
    private static final short BTN_UP = 0x0001;
    private static final short BTN_DOWN = 0x0002;
    private static final short BTN_LEFT = 0x0004;
    private static final short BTN_RIGHT = 0x0008;
    private static final short BTN_PLAY = 0x0010;
    private static final short BTN_BACK = 0x0020;
    private static final short BTN_LS_CLK = 0x0040;
    private static final short BTN_RS_CLK = 0x0080;
    private static final short BTN_LB = 0x0100;
    private static final short BTN_RB = 0x0200;
    private static final short BTN_SPECIAL = 0x0400;
    private static final short BTN_A = 0x1000;
    private static final short BTN_B = 0x2000;
    private static final short BTN_X = 0x4000;
    private static final short BTN_Y = (short) 0x8000;

    /**
     * Mutable per-controller analog and button state for up to 4 simultaneous gamepads.
     */
    private static final class PadState {
        int deviceId = -1;
        short buttons;
        byte leftTrigger;
        byte rightTrigger;
        short leftStickX;
        short leftStickY;
        short rightStickX;
        short rightStickY;
        short dpadButtons;
        boolean startHeld;
        long startDownUptimeMs;
        boolean mouseToggleConsumed;
    }

    /**
     * Cooldown tracker preventing transient queue backpressure from triggering cascading IDR floods.
     */
    static final class IdrCooldownTracker {
        private final long cooldownMs;
        private long lastIdrRequestMs = -1L;

        IdrCooldownTracker(long cooldownMs) {
            this.cooldownMs = Math.max(0L, cooldownMs);
        }

        synchronized boolean tryConsumeIdrRequest(long nowMs) {
            if (lastIdrRequestMs < 0L || (nowMs - lastIdrRequestMs) >= cooldownMs) {
                lastIdrRequestMs = nowMs;
                return true;
            }
            return false;
        }

        synchronized void reset() {
            lastIdrRequestMs = -1L;
        }
    }

    /**
     * Fixed-slot reusable byte ring absorbing transient {@link MediaCodec} input backpressure off the
     * native UDP receive thread with zero steady-state heap allocations.
     */
    static final class PendingFrameRing {
        static final class FrameSlot {
            byte[] data;
            int length;
            long ptsUs;
            int flags;

            FrameSlot(int initialCapacity) {
                this.data = new byte[initialCapacity];
            }

            void ensureCapacity(int required) {
                if (data.length < required) {
                    data = new byte[required];
                }
            }
        }

        private final FrameSlot[] slots;
        private int head = 0;
        private int tail = 0;
        private int count = 0;

        PendingFrameRing(int capacity, int initialSlotBytes) {
            int safeCap = Math.max(1, capacity);
            int safeBytes = Math.max(1024, initialSlotBytes);
            this.slots = new FrameSlot[safeCap];
            for (int i = 0; i < safeCap; i++) {
                this.slots[i] = new FrameSlot(safeBytes);
            }
        }

        synchronized boolean isEmpty() {
            return count == 0;
        }

        synchronized int size() {
            return count;
        }

        synchronized boolean offer(
                byte[] vps,
                byte[] sps,
                byte[] pps,
                byte[] frameData,
                int frameLen,
                long ptsUs,
                int flags) {
            if (frameData == null || frameLen <= 0 || frameLen > frameData.length || count >= slots.length) {
                return false;
            }
            int vpsLen = vps != null ? vps.length : 0;
            int spsLen = sps != null ? sps.length : 0;
            int ppsLen = pps != null ? pps.length : 0;
            long totalLenLong = (long) vpsLen + (long) spsLen + (long) ppsLen + (long) frameLen;
            if (totalLenLong <= 0L || totalLenLong > Integer.MAX_VALUE) {
                return false;
            }
            int totalLen = (int) totalLenLong;
            FrameSlot slot = slots[tail];
            slot.ensureCapacity(totalLen);
            int offset = 0;
            if (vpsLen > 0) {
                System.arraycopy(vps, 0, slot.data, offset, vpsLen);
                offset += vpsLen;
            }
            if (spsLen > 0) {
                System.arraycopy(sps, 0, slot.data, offset, spsLen);
                offset += spsLen;
            }
            if (ppsLen > 0) {
                System.arraycopy(pps, 0, slot.data, offset, ppsLen);
                offset += ppsLen;
            }
            System.arraycopy(frameData, 0, slot.data, offset, frameLen);
            slot.length = totalLen;
            slot.ptsUs = ptsUs;
            slot.flags = flags;
            tail = (tail + 1) % slots.length;
            count++;
            notifyAll();
            return true;
        }

        synchronized FrameSlot awaitHead(long waitMs) throws InterruptedException {
            if (count == 0 && waitMs > 0L) {
                wait(waitMs);
            }
            return count > 0 ? slots[head] : null;
        }

        synchronized void popHead() {
            if (count > 0) {
                head = (head + 1) % slots.length;
                count--;
                notifyAll();
            }
        }

        synchronized void clear() {
            head = 0;
            tail = 0;
            count = 0;
            notifyAll();
        }
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean exitHandled = new AtomicBoolean(false);
    private final AtomicBoolean streamRunning = new AtomicBoolean(false);
    private final AtomicBoolean decoderRunning = new AtomicBoolean(false);
    private final AtomicBoolean firstFrameRendered = new AtomicBoolean(false);
    private final AtomicInteger decodedFramesThisSec = new AtomicInteger(0);
    private final AtomicInteger inputFramesThisWindow = new AtomicInteger(0);
    private final AtomicInteger outputFramesThisWindow = new AtomicInteger(0);
    private final AtomicLong inputBytesThisWindow = new AtomicLong(0L);
    private final AtomicInteger droppedInputFrames = new AtomicInteger(0);
    private final AtomicInteger droppedOutputFrames = new AtomicInteger(0);
    private final AtomicInteger idrRequestsCount = new AtomicInteger(0);
    private final AtomicLong decodeLatencySumUs = new AtomicLong(0L);
    private final AtomicInteger decodeLatencySamples = new AtomicInteger(0);
    private final AtomicLong maxDecodeLatencyUs = new AtomicLong(0L);
    private final PadState[] pads = new PadState[] {
            new PadState(), new PadState(), new PadState(), new PadState()
    };

    private String hostIp = "";
    private String gameId = "steam_bigpicture";
    private String gameTitle = "Game";
    private String sunshineApp = "Desktop";
    private String framePacingMode = "balanced";
    private volatile String activeCodecLabel = "HEVC";
    private volatile String activeCodecName = "";
    private volatile boolean lowLatencyActive = false;
    private int streamWidth = 1920;
    private int streamHeight = 1080;
    private int streamFps = 60;
    private int streamBitrateMbps = 30;
    private volatile long telemetryWindowStartMs = 0L;
    private boolean perfOverlayEnabled = false;
    private boolean rumbleEnabled = true;
    private boolean mouseEmulationAllowed = true;
    private volatile boolean mouseEmulationActive = false;
    private boolean remoteBackPressedDown = false;

    private SurfaceView surfaceView;
    private TextView statusPill;
    private TextView perfHud;
    private WifiManager.WifiLock wifiLock;
    private Thread connectThread;
    private Thread videoInputThread;
    private Thread videoOutputThread;

    private final Object codecLock = new Object();
    private volatile MediaCodec videoCodec;
    private volatile byte[] cachedVps;
    private volatile byte[] cachedSps;
    private volatile byte[] cachedPps;
    private final IdrCooldownTracker idrCooldownTracker = new IdrCooldownTracker(IDR_REQUEST_COOLDOWN_MS);
    private final PendingFrameRing pendingFrameRing =
            new PendingFrameRing(PENDING_FRAME_RING_SLOTS, PENDING_FRAME_INITIAL_BYTES);

    private volatile long lastVsyncNs = 0L;
    private volatile long vsyncPeriodNs = 16_666_667L;
    private long lastScheduledRenderNs = 0L;

    private final Object audioLock = new Object();
    private volatile AudioTrack audioTrack;

    private final Runnable mouseTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mouseEmulationActive || !streamRunning.get()) return;
            PadState p0 = pads[0];
            float nx = p0.leftStickX / 32767.0f;
            float ny = -p0.leftStickY / 32767.0f;
            short dx = (short) Math.round(nx * Math.abs(nx) * MOUSE_MAX_SPEED_PX_PER_TICK);
            short dy = (short) Math.round(ny * Math.abs(ny) * MOUSE_MAX_SPEED_PX_PER_TICK);
            if ((dx != 0 || dy != 0) && MoonBridge.isNativeLoaded()) {
                MoonBridge.sendMouseMove(dx, dy);
            }
            uiHandler.postDelayed(this, MOUSE_TICK_INTERVAL_MS);
        }
    };

    private final Runnable perfHudTicker = new Runnable() {
        @Override
        public void run() {
            if (exitHandled.get() || !perfOverlayEnabled || perfHud == null) return;
            int fpsNow = decodedFramesThisSec.getAndSet(0);
            long rttMs = 0L;
            if (MoonBridge.isNativeLoaded() && streamRunning.get()) {
                long rttInfo = MoonBridge.getEstimatedRttInfo();
                if (rttInfo > 0L) {
                    rttMs = (rttInfo >> 32) & 0xFFFFFFFFL;
                }
            }
            perfHud.setText(String.format(
                    Locale.US,
                    "%dx%d · %d fps · %s · RTT %d ms",
                    streamWidth,
                    streamHeight,
                    fpsNow,
                    activeCodecLabel,
                    rttMs));
            uiHandler.postDelayed(this, 1000L);
        }
    };

    private final Runnable telemetryTicker = new Runnable() {
        @Override
        public void run() {
            if (exitHandled.get() || (!decoderRunning.get() && !streamRunning.get())) return;
            long nowMs = SystemClock.uptimeMillis();
            long prevMs = telemetryWindowStartMs;
            telemetryWindowStartMs = nowMs;
            long elapsedMs = prevMs > 0L ? Math.max(250L, nowMs - prevMs) : TELEMETRY_INTERVAL_MS;

            int inFrames = inputFramesThisWindow.getAndSet(0);
            int outFrames = outputFramesThisWindow.getAndSet(0);
            long bytesIn = inputBytesThisWindow.getAndSet(0L);
            int dropIn = droppedInputFrames.getAndSet(0);
            int dropOut = droppedOutputFrames.getAndSet(0);
            int idrReq = idrRequestsCount.getAndSet(0);
            long latSumUs = decodeLatencySumUs.getAndSet(0L);
            int latSamples = decodeLatencySamples.getAndSet(0);
            long latMaxUs = maxDecodeLatencyUs.getAndSet(0L);

            long rttMs = 0L;
            if (MoonBridge.isNativeLoaded() && streamRunning.get()) {
                long rttInfo = MoonBridge.getEstimatedRttInfo();
                if (rttInfo > 0L) {
                    rttMs = (rttInfo >> 32) & 0xFFFFFFFFL;
                }
            }

            double windowSec = elapsedMs / 1000.0;
            double inFps = inFrames / windowSec;
            double outFps = outFrames / windowSec;
            double avgDecodeMs = latSamples > 0 ? (latSumUs / (double) latSamples) / 1000.0 : 0.0;
            double maxDecodeMs = latMaxUs / 1000.0;
            double bitrateMbps = (bytesIn * 8.0) / (windowSec * 1_000_000.0);

            AppLog.i(formatStreamTelemetryLine(
                    activeCodecName.isEmpty() ? activeCodecLabel : activeCodecName,
                    activeCodecLabel,
                    lowLatencyActive,
                    streamWidth,
                    streamHeight,
                    framePacingMode,
                    streamFps,
                    inFps,
                    outFps,
                    avgDecodeMs,
                    maxDecodeMs,
                    dropIn,
                    dropOut,
                    idrReq,
                    bitrateMbps,
                    streamBitrateMbps,
                    rttMs));

            if (!exitHandled.get()) {
                uiHandler.postDelayed(this, TELEMETRY_INTERVAL_MS);
            }
        }
    };

    static String formatStreamTelemetryLine(
            String codecName,
            String codecLabel,
            boolean lowLatency,
            int width,
            int height,
            String pacing,
            int reqFps,
            double inFps,
            double outFps,
            double avgDecodeMs,
            double maxDecodeMs,
            int dropIn,
            int dropOut,
            int idrCount,
            double bitrateMbps,
            int targetMbps,
            long rttMs) {
        return String.format(
                Locale.US,
                "GameStream telemetry: codec=%s(%s) lowLatency=%b res=%dx%d pacing=%s reqFps=%d "
                        + "inFps=%.1f outFps=%.1f decodeMs=%.2f maxDecodeMs=%.2f dropIn=%d dropOut=%d "
                        + "idr=%d bitrateMbps=%.2f targetMbps=%d rttMs=%d",
                codecName != null ? codecName : "",
                codecLabel != null ? codecLabel : "",
                lowLatency,
                width,
                height,
                pacing != null ? pacing : "",
                reqFps,
                inFps,
                outFps,
                avgDecodeMs,
                maxDecodeMs,
                dropIn,
                dropOut,
                idrCount,
                bitrateMbps,
                targetMbps,
                rttMs);
    }

    /**
     * Builds an explicit Intent launching {@link GameStreamActivity} inside SoarTV's isolated {@code :stream} process.
     */
    public static Intent buildIntent(
            Context context,
            String host,
            String gameId,
            String gameTitle,
            String sunshineApp) {
        Intent intent = new Intent(context, GameStreamActivity.class);
        intent.putExtra(EXTRA_HOST, host != null ? host.trim() : "");
        intent.putExtra(EXTRA_GAME_ID, gameId != null ? gameId.trim() : "steam_bigpicture");
        intent.putExtra(EXTRA_GAME_TITLE, gameTitle != null ? gameTitle.trim() : "Game");
        intent.putExtra(EXTRA_SUNSHINE_APP, sunshineApp != null && !sunshineApp.trim().isEmpty()
                ? sunshineApp.trim()
                : "Desktop");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    public static int parseResolutionWidth(String res) {
        if (res == null) return 1920;
        switch (res.trim().toLowerCase(Locale.ROOT)) {
            case "720p":
                return 1280;
            case "1440p":
                return 2560;
            case "4k":
            case "2160p":
                return 3840;
            case "1080p":
            default:
                return 1920;
        }
    }

    public static int parseResolutionHeight(String res) {
        if (res == null) return 1080;
        switch (res.trim().toLowerCase(Locale.ROOT)) {
            case "720p":
                return 720;
            case "1440p":
                return 1440;
            case "4k":
            case "2160p":
                return 2160;
            case "1080p":
            default:
                return 1080;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readIntentExtras(getIntent());
        loadStreamPreferences();

        Window win = getWindow();
        if (win != null) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            win.setBackgroundDrawable(null);
            applyImmersiveFullscreen(win);
            requestMatchingDisplayRefreshRate(win, streamFps);
        }

        acquireLowLatencyWifiLock();
        Recs.setBackgroundActivityPaused(true);
        GamingZone.trimMemoryForStreaming();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);

        surfaceView = new SurfaceView(this);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.getHolder().setKeepScreenOn(true);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusPill = buildLiquidGlassPill(20f, 28, 16);
        statusPill.setText("Connecting to " + gameTitle + "…");
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusLp.bottomMargin = dp(48);
        root.addView(statusPill, statusLp);

        perfHud = buildLiquidGlassPill(13f, 16, 8);
        perfHud.setVisibility(perfOverlayEnabled ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        hudLp.topMargin = dp(24);
        hudLp.leftMargin = dp(24);
        root.addView(perfHud, hudLp);

        setContentView(root);
        surfaceView.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntentExtras(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (getWindow() != null) {
            applyImmersiveFullscreen(getWindow());
        }
        Choreographer.getInstance().postFrameCallback(this);
        if (perfOverlayEnabled) {
            uiHandler.removeCallbacks(perfHudTicker);
            uiHandler.postDelayed(perfHudTicker, 1000L);
        }
        startTelemetryTicker();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // TV Remote Home button pressed: immediately terminate PC game and close stream.
        if (!isChangingConfigurations()) {
            exitStreamAndTerminatePcGame("tv_remote_home", false);
        }
    }

    @Override
    protected void onStop() {
        Choreographer.getInstance().removeFrameCallback(this);
        uiHandler.removeCallbacks(perfHudTicker);
        uiHandler.removeCallbacks(telemetryTicker);
        uiHandler.removeCallbacks(mouseTickRunnable);
        if (!isChangingConfigurations() && !exitHandled.get()) {
            exitStreamAndTerminatePcGame("tv_remote_home_or_stop", false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Choreographer.getInstance().removeFrameCallback(this);
        uiHandler.removeCallbacksAndMessages(null);
        releaseLowLatencyWifiLock();
        stopNativeConnectionAndRenderers();
        Recs.setBackgroundActivityPaused(false);
        super.onDestroy();
    }

    private void readIntentExtras(Intent intent) {
        if (intent == null) return;
        String h = intent.getStringExtra(EXTRA_HOST);
        hostIp = (h != null && !h.trim().isEmpty()) ? h.trim() : GamingZone.getLaptopIp(this);
        String gid = intent.getStringExtra(EXTRA_GAME_ID);
        if (gid != null && !gid.trim().isEmpty()) gameId = gid.trim();
        String gtitle = intent.getStringExtra(EXTRA_GAME_TITLE);
        if (gtitle != null && !gtitle.trim().isEmpty()) gameTitle = gtitle.trim();
        String sapp = intent.getStringExtra(EXTRA_SUNSHINE_APP);
        if (sapp != null && !sapp.trim().isEmpty()) sunshineApp = sapp.trim();
    }

    private void loadStreamPreferences() {
        Prefs.reload();
        String res = Prefs.gamingResolution();
        streamWidth = parseResolutionWidth(res);
        streamHeight = parseResolutionHeight(res);
        streamFps = Math.max(30, Math.min(120, Prefs.gamingFps()));
        streamBitrateMbps = Math.max(5, Math.min(150, Prefs.gamingBitrateMbps()));
        framePacingMode = Prefs.gamingFramePacing();
        perfOverlayEnabled = Prefs.gamingPerfOverlay();
        rumbleEnabled = Prefs.gamingRumble();
        mouseEmulationAllowed = Prefs.gamingMouseEmulation();
    }

    private void startTelemetryTicker() {
        if (exitHandled.get()) return;
        telemetryWindowStartMs = SystemClock.uptimeMillis();
        uiHandler.removeCallbacks(telemetryTicker);
        uiHandler.postDelayed(telemetryTicker, TELEMETRY_INTERVAL_MS);
    }

    private TextView buildLiquidGlassPill(float textSp, int padHdp, int padVdp) {
        TextView tv = new TextView(this);
        tv.setTextColor(0xF2FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        tv.setPadding(dp(padHdp), dp(padVdp), dp(padHdp), dp(padVdp));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(20));
        bg.setColor(0xB8111520);
        bg.setStroke(Math.max(1, dp(1)), 0x38FFFFFF);
        tv.setBackground(bg);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applyImmersiveFullscreen(Window win) {
        View decor = win.getDecorView();
        if (decor != null) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    /**
     * Locks the TV panel to an exact integer multiple of {@code targetFps} (e.g. {@code 60.00Hz} rather than
     * {@code 59.94Hz} or {@code 50Hz}) when supported by the HDMI/internal panel so decoded frames land on VSYNC.
     */
    private void requestMatchingDisplayRefreshRate(Window win, int targetFps) {
        try {
            Display display = getWindowManager().getDefaultDisplay();
            if (display == null) return;
            Display.Mode current = display.getMode();
            Display.Mode[] modes = display.getSupportedModes();
            if (current == null || modes == null) return;

            vsyncPeriodNs = Math.round(1_000_000_000.0 / Math.max(30.0f, display.getRefreshRate()));

            Display.Mode best = null;
            float bestDiff = Float.MAX_VALUE;
            for (Display.Mode m : modes) {
                if (m.getPhysicalWidth() != current.getPhysicalWidth()
                        || m.getPhysicalHeight() != current.getPhysicalHeight()) {
                    continue;
                }
                float hz = m.getRefreshRate();
                float diff = Math.abs(hz - targetFps);
                if (diff < 0.08f && diff < bestDiff) {
                    best = m;
                    bestDiff = diff;
                }
            }
            if (best != null && best.getModeId() != current.getModeId()) {
                WindowManager.LayoutParams lp = win.getAttributes();
                lp.preferredDisplayModeId = best.getModeId();
                win.setAttributes(lp);
                vsyncPeriodNs = Math.round(1_000_000_000.0 / best.getRefreshRate());
            }
        } catch (Throwable ignored) {
        }
    }

    private void acquireLowLatencyWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            int lockMode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(lockMode, "SoarTV:GameStreamLowLatency");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Throwable ignored) {
        }
    }

    private void releaseLowLatencyWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Throwable ignored) {
        }
        wifiLock = null;
    }

    // --- Choreographer VSYNC tracking ---

    @Override
    public void doFrame(long frameTimeNanos) {
        long prev = lastVsyncNs;
        if (prev > 0L) {
            long delta = frameTimeNanos - prev;
            if (delta >= 8_000_000L && delta <= 34_000_000L) {
                vsyncPeriodNs = delta;
            }
        }
        lastVsyncNs = frameTimeNanos;
        if (!exitHandled.get()) {
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // --- SurfaceHolder.Callback ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (exitHandled.get() || connectThread != null) return;
        final Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;

        connectThread = new Thread(() -> connectAndStartBridge(surface), "soartv-gamestream-connect");
        connectThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        drStop();
        drCleanup();
    }

    private void connectAndStartBridge(Surface surface) {
        if (!MoonBridge.isNativeLoaded()) {
            failStreamAndReturnToGaming("Native streaming engine unavailable on this device.");
            return;
        }
        try {
            loadStreamPreferences();
            Secrets.refreshIfDirChanged(getApplicationContext());
            int bitrateKbps = Math.max(5_000, Math.min(150_000, streamBitrateMbps * 1000));
            boolean enableHdr = Prefs.gamingHdr();
            boolean playAudioOnPc = Prefs.gamingPlayAudioOnPc();
            boolean optimizeSettings = Prefs.gamingOptimizeSettings();
            MoonBridge.AudioConfiguration audioCfg = resolveAudioConfig(Prefs.gamingAudioConfig());
            int supportedFormats = resolveSupportedVideoFormats(Prefs.gamingCodec(), enableHdr);

            GamingZone.LaptopStatus cachedSt = GamingZone.getCachedLaptopStatus(getApplicationContext());
            if (cachedSt != null && !cachedSt.sunshineRunning && cachedSt.sunshinePowerControlReady) {
                updateStatusText("Starting Sunshine on Gaming PC…");
                GamingZone.ensureSunshineRunningOnPcSync(getApplicationContext(), hostIp);
            }

            updateStatusText("Pairing & negotiating stream with Gaming PC…");
            SunshineStreamClient.LaunchSession session;
            try {
                session = SunshineStreamClient.startSunshineSession(
                        getApplicationContext(),
                        hostIp,
                        sunshineApp,
                        streamWidth,
                        streamHeight,
                        streamFps,
                        audioCfg,
                        playAudioOnPc,
                        optimizeSettings,
                        enableHdr);
            } catch (IOException firstErr) {
                if (exitHandled.get()) return;
                // Sunshine may have entered standby (--manual-start or 10-min auto-shutdown); wake it on demand first.
                updateStatusText("Starting Sunshine on Gaming PC…");
                boolean wokenOnSameIp = GamingZone.ensureSunshineRunningOnPcSync(getApplicationContext(), hostIp);
                if (!wokenOnSameIp && !exitHandled.get()) {
                    updateStatusText("Searching Wi-Fi for Gaming PC…");
                    String discoveredIp = GamingZone.discoverLaptopIpOnLanSync(getApplicationContext());
                    if (discoveredIp.isEmpty() || exitHandled.get()) {
                        throw firstErr;
                    }
                    hostIp = discoveredIp;
                    GamingZone.ensureSunshineRunningOnPcSync(getApplicationContext(), hostIp);
                }
                if (exitHandled.get()) return;
                updateStatusText("Reconnecting to Gaming PC (" + hostIp + ")…");
                session = SunshineStreamClient.startSunshineSession(
                        getApplicationContext(),
                        hostIp,
                        sunshineApp,
                        streamWidth,
                        streamHeight,
                        streamFps,
                        audioCfg,
                        playAudioOnPc,
                        optimizeSettings,
                        enableHdr);
            }

            if (exitHandled.get()) return;

            GamingZone.setActiveStreamingSession(
                    getApplicationContext(),
                    new GamingZone.ActiveStreamingSession(
                            gameId,
                            gameTitle,
                            hostIp,
                            GamingZone.LAPTOP_AGENT_PORT,
                            System.currentTimeMillis()));

            MoonBridge.setCallbacks(this);
            streamRunning.set(true);

            int clientRefreshX100 = streamFps * 100;
            int colorSpace = enableHdr ? MoonBridge.COLORSPACE_REC_2020 : MoonBridge.COLORSPACE_REC_709;
            int rc = MoonBridge.startConnection(
                    session.host,
                    session.appVersion,
                    session.gfeVersion,
                    session.rtspSessionUrl,
                    session.serverCodecModeSupport,
                    streamWidth,
                    streamHeight,
                    streamFps,
                    bitrateKbps,
                    LAN_PACKET_SIZE,
                    STREAMING_LOCALLY,
                    audioCfg.toInt(),
                    supportedFormats,
                    clientRefreshX100,
                    session.riAesKey,
                    session.riAesIv,
                    MoonBridge.CAPABILITY_DIRECT_SUBMIT,
                    colorSpace,
                    MoonBridge.COLOR_RANGE_LIMITED);

            if (rc != 0 && !exitHandled.get()) {
                streamRunning.set(false);
                failStreamAndReturnToGaming("Stream handshake failed (code " + rc + ").");
            }
        } catch (Exception e) {
            AppLog.w("GameStreamActivity connection error: " + e.getMessage());
            if (!exitHandled.get()) {
                failStreamAndReturnToGaming(e.getMessage() != null
                        ? e.getMessage()
                        : "Unable to connect to Gaming PC.");
            }
        }
    }

    private static MoonBridge.AudioConfiguration resolveAudioConfig(String pref) {
        if ("5.1".equalsIgnoreCase(pref)) {
            return MoonBridge.AUDIO_CONFIGURATION_51_SURROUND;
        }
        if ("7.1".equalsIgnoreCase(pref)) {
            return MoonBridge.AUDIO_CONFIGURATION_71_SURROUND;
        }
        return MoonBridge.AUDIO_CONFIGURATION_STEREO;
    }

    private static int resolveSupportedVideoFormats(String codecPref, boolean hdr) {
        boolean hwHevc = findHardwareDecoderName("video/hevc") != null;
        boolean hwAv1 = findHardwareDecoderName("video/av01") != null;
        String pref = codecPref == null ? "auto" : codecPref.trim().toLowerCase(Locale.ROOT);

        if ("h264".equals(pref)) {
            return MoonBridge.VIDEO_FORMAT_H264;
        }
        if ("av1".equals(pref) && hwAv1) {
            int mask = MoonBridge.VIDEO_FORMAT_AV1_MAIN8 | MoonBridge.VIDEO_FORMAT_H264;
            if (hwHevc) mask |= MoonBridge.VIDEO_FORMAT_H265;
            if (hdr) mask |= MoonBridge.VIDEO_FORMAT_AV1_MAIN10 | MoonBridge.VIDEO_FORMAT_H265_MAIN10;
            return mask;
        }
        if ("hevc".equals(pref) && hwHevc) {
            int mask = MoonBridge.VIDEO_FORMAT_H265 | MoonBridge.VIDEO_FORMAT_H264;
            if (hdr) mask |= MoonBridge.VIDEO_FORMAT_H265_MAIN10;
            return mask;
        }
        // "auto": prefer hardware HEVC (and AV1 if present) with H.264 fallback
        int mask = MoonBridge.VIDEO_FORMAT_H264;
        if (hwHevc) {
            mask |= MoonBridge.VIDEO_FORMAT_H265;
            if (hdr) mask |= MoonBridge.VIDEO_FORMAT_H265_MAIN10;
        }
        if (hwAv1) {
            mask |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
            if (hdr) mask |= MoonBridge.VIDEO_FORMAT_AV1_MAIN10;
        }
        return mask;
    }

    static boolean isSecureDecoderName(String codecName) {
        if (codecName == null) return false;
        String lower = codecName.trim().toLowerCase(Locale.ROOT);
        return lower.endsWith(".secure") || lower.contains(".secure.");
    }

    static boolean isAcceptableHardwareDecoder(
            String codecName,
            boolean isEncoder,
            boolean hwAccelerated,
            boolean swOnly,
            boolean secureFeatureSupported,
            boolean secureFeatureRequired) {
        if (codecName == null || codecName.trim().isEmpty()) return false;
        if (isEncoder) return false;
        String lower = codecName.trim().toLowerCase(Locale.ROOT);
        if (!hwAccelerated
                || swOnly
                || lower.startsWith("omx.google.")
                || lower.startsWith("c2.android.")
                || lower.contains(".sw.")
                || lower.contains("ffmpeg")) {
            return false;
        }
        if (isSecureDecoderName(lower)) return false;
        if (secureFeatureRequired || secureFeatureSupported) return false;
        return true;
    }

    private static String findHardwareDecoderName(String mimeType) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            String hybridFallback = null;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                if (isSoftwareCodec(info)) continue;
                String codecName = info.getName();
                // Strictly skip DRM ".secure" decoders (e.g. c2.mtk.hevc.decoder.secure).
                if (isSecureDecoderName(codecName)
                        || (codecName != null && codecName.toLowerCase(Locale.ROOT).endsWith(".secure"))) {
                    continue;
                }
                for (String t : info.getSupportedTypes()) {
                    if (!mimeType.equalsIgnoreCase(t)) continue;
                    boolean secureSupported = false;
                    boolean secureRequired = false;
                    try {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(t);
                        if (caps != null) {
                            secureSupported = caps.isFeatureSupported(
                                    MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback);
                            secureRequired = caps.isFeatureRequired(
                                    MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback);
                        }
                    } catch (Throwable ignored) {
                    }
                    if (secureRequired) {
                        continue;
                    }
                    if (!secureSupported) {
                        return codecName;
                    }
                    if (hybridFallback == null) {
                        hybridFallback = codecName;
                    }
                }
            }
            return hybridFallback;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isSoftwareCodec(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (!info.isHardwareAccelerated()) return true;
                if (info.isSoftwareOnly()) return true;
            } catch (Throwable ignored) {
            }
        }
        String name = info.getName().toLowerCase(Locale.ROOT);
        return name.startsWith("omx.google.")
                || name.startsWith("c2.android.")
                || name.contains(".sw.")
                || name.contains("ffmpeg");
    }

    // --- MoonBridge Video Decoder Callbacks ---

    @Override
    public int drSetup(int videoFormat, int width, int height, int redrawRate) {
        final String mime;
        if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            mime = "video/av01";
            activeCodecLabel = "AV1";
        } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            mime = "video/hevc";
            activeCodecLabel = (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0 ? "HEVC HDR" : "HEVC";
        } else {
            mime = "video/avc";
            activeCodecLabel = "H.264";
        }

        streamWidth = width;
        streamHeight = height;
        if (redrawRate > 0) {
            streamFps = redrawRate;
        }

        synchronized (codecLock) {
            cachedVps = null;
            cachedSps = null;
            cachedPps = null;
            lowLatencyActive = false;
            activeCodecName = "";
            lastScheduledRenderNs = 0L;
            idrCooldownTracker.reset();
            pendingFrameRing.clear();
            inputFramesThisWindow.set(0);
            outputFramesThisWindow.set(0);
            inputBytesThisWindow.set(0L);
            droppedInputFrames.set(0);
            droppedOutputFrames.set(0);
            idrRequestsCount.set(0);
            decodeLatencySumUs.set(0L);
            decodeLatencySamples.set(0);
            maxDecodeLatencyUs.set(0L);

            Surface surface = surfaceView != null ? surfaceView.getHolder().getSurface() : null;
            if (surface == null || !surface.isValid()) {
                return -1;
            }
            try {
                String hwName = findHardwareDecoderName(mime);
                MediaCodec codec = hwName != null
                        ? MediaCodec.createByCodecName(hwName)
                        : MediaCodec.createDecoderByType(mime);
                activeCodecName = resolveCodecName(codec, hwName, mime);

                if (!configureLowLatencyDecoder(codec, mime, width, height, redrawRate, surface)) {
                    AppLog.w("GameStreamActivity drSetup failed to configure codec " + activeCodecName);
                    try {
                        codec.release();
                    } catch (Throwable ignored) {
                    }
                    return -1;
                }
                codec.start();
                videoCodec = codec;
                decoderRunning.set(true);
                startVideoInputDrainThread();
                startVideoOutputDrainThread();
                uiHandler.post(this::startTelemetryTicker);
                return 0;
            } catch (Throwable t) {
                AppLog.w("GameStreamActivity drSetup failed: " + t.getMessage());
                safeReleaseVideoCodecLocked();
                return -1;
            }
        }
    }

    private static final int MTK_RACING_DISPLAY_SAFE_MAX_WIDTH = 2560;
    private static final int MTK_RACING_DISPLAY_SAFE_MAX_HEIGHT = 1440;
    private static final int SAFE_MAX_INPUT_BUFFER_BYTES = 4 * 1024 * 1024;

    static boolean isMediaTekDecoderOrHardware(String codecName, String hardware) {
        String cn = codecName != null ? codecName.trim().toLowerCase(Locale.ROOT) : "";
        String hw = hardware != null ? hardware.trim().toLowerCase(Locale.ROOT) : "";
        if (cn.contains(".mtk.") || cn.startsWith("omx.mtk.") || cn.startsWith("c2.mtk.")) {
            return true;
        }
        if (cn.contains(".amlogic.") || cn.contains(".aml.")
                || cn.contains(".qti.") || cn.contains(".qcom.")
                || cn.contains(".rtk.") || cn.contains(".realtek.")
                || cn.contains(".exynos.") || cn.contains(".samsung.")
                || cn.contains(".brcm.") || cn.contains(".broadcom.")
                || cn.contains(".android.") || cn.contains(".google.")) {
            return false;
        }
        return hw.startsWith("mt");
    }

    /**
     * Returns whether low-latency / vendor racing-display MediaCodec keys are safe for the
     * target decoder and resolution.
     *
     * <p><b>Why:</b> On MediaTek Android TV SoCs (e.g. mt5879 {@code libcodec2.MTK.so}),
     * enabling low-latency racing display ({@code KEY_LOW_LATENCY=1} or
     * {@code vendor.mtk.vdec.lowlatency=1}) at 4K UHD ({@code > 2560x1440}) crashes natively
     * with {@code SIGSEGV} in {@code VCodecVideo::EnableRacingDisplay -> MsVdecSetSliceCount}.
     * 1080p and 1440p keep full low-latency flags, and non-MediaTek decoders keep 4K low-latency.
     */
    static boolean shouldEnableLowLatencyKeys(String codecName, String hardware, int width, int height) {
        boolean above1440p = width > MTK_RACING_DISPLAY_SAFE_MAX_WIDTH
                || height > MTK_RACING_DISPLAY_SAFE_MAX_HEIGHT;
        if (above1440p && isMediaTekDecoderOrHardware(codecName, hardware)) {
            return false;
        }
        return true;
    }

    static int computeBoundedOperatingRate(int fps) {
        int safeFps = Math.max(30, fps);
        return Math.min(MAX_OPERATING_RATE_CAP, Math.max(safeFps * 2, 120));
    }

    static int computeBoundedOperatingRateForSize(int fps, int width, int height) {
        int safeFps = Math.max(30, fps);
        if (width > MTK_RACING_DISPLAY_SAFE_MAX_WIDTH || height > MTK_RACING_DISPLAY_SAFE_MAX_HEIGHT) {
            // 4K UHD hardware TV VDECs typically max out at 4K60; requesting 4K120 fails or stalls configure().
            return Math.min(MAX_OPERATING_RATE_CAP, safeFps);
        }
        return computeBoundedOperatingRate(safeFps);
    }

    static int computeSafeMaxInputSize(int width, int height) {
        long pixels = (long) Math.max(1, width) * (long) Math.max(1, height);
        long bounded = Math.max(1024L * 1024L, Math.min(pixels, (long) SAFE_MAX_INPUT_BUFFER_BYTES));
        return (int) bounded;
    }

    static String resolveVendorLowLatencyKey(String codecName, String hardware) {
        String cn = codecName != null ? codecName.trim().toLowerCase(Locale.ROOT) : "";
        String hw = hardware != null ? hardware.trim().toLowerCase(Locale.ROOT) : "";
        // Evaluate explicit codecName vendor tokens before falling back to Build.HARDWARE
        if (cn.contains(".mtk.") || cn.startsWith("omx.mtk.") || cn.startsWith("c2.mtk.")) {
            return "vendor.mtk.vdec.lowlatency";
        }
        if (cn.contains(".amlogic.") || cn.contains(".aml.") || cn.startsWith("omx.amlogic.")
                || cn.startsWith("c2.amlogic.") || cn.startsWith("c2.aml.")) {
            return "vendor.aml.vdec.lowlatency";
        }
        if (cn.contains(".qti.") || cn.contains(".qcom.") || cn.startsWith("omx.qcom.")
                || cn.startsWith("c2.qti.") || cn.startsWith("c2.qcom.")) {
            return "vendor.qti-ext-dec-low-latency.enable";
        }
        if (cn.contains(".rtk.") || cn.contains(".realtek.") || cn.startsWith("omx.rtk.")
                || cn.startsWith("omx.realtek.") || cn.startsWith("c2.rtk.") || cn.startsWith("c2.realtek.")) {
            return "vendor.rtk.vdec.lowlatency";
        }
        if (cn.contains(".exynos.") || cn.contains(".samsung.") || cn.startsWith("omx.exynos.")
                || cn.startsWith("c2.exynos.") || cn.startsWith("c2.samsung.")
                || cn.contains(".brcm.") || cn.contains(".broadcom.") || cn.startsWith("omx.brcm.")
                || cn.startsWith("c2.brcm.") || cn.startsWith("c2.broadcom.")
                || cn.contains(".android.") || cn.contains(".google.")) {
            return null;
        }
        if (hw.startsWith("mt")) {
            return "vendor.mtk.vdec.lowlatency";
        }
        if (hw.contains("amlogic") || hw.startsWith("aml")) {
            return "vendor.aml.vdec.lowlatency";
        }
        if (hw.contains("qcom") || hw.contains("qti")) {
            return "vendor.qti-ext-dec-low-latency.enable";
        }
        if (hw.startsWith("rtk") || hw.contains("realtek")) {
            return "vendor.rtk.vdec.lowlatency";
        }
        return null;
    }

    private static String resolveCodecName(MediaCodec codec, String preferredName, String fallbackMime) {
        if (preferredName != null && !preferredName.isEmpty()) {
            return preferredName;
        }
        try {
            if (codec != null) {
                String n = codec.getName();
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        }
        return fallbackMime != null ? fallbackMime : "unknown";
    }

    private static MediaFormat createBaseVideoFormat(String mime, int width, int height, int fps) {
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, computeSafeMaxInputSize(width, height));
        if (fps > 0) {
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        }
        return fmt;
    }

    private static void safeResetCodec(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.reset();
        } catch (Throwable ignored) {
        }
    }

    private static boolean tryConfigureDecoderFormat(MediaCodec codec, MediaFormat fmt, Surface surface) {
        try {
            codec.configure(fmt, surface, null, 0);
            return true;
        } catch (Throwable t) {
            safeResetCodec(codec);
            return false;
        }
    }

    private static boolean supportsLowLatencyFeature(MediaCodec codec, String mime) {
        if (Build.VERSION.SDK_INT < 30 || codec == null) return false;
        try {
            MediaCodecInfo info = codec.getCodecInfo();
            if (info == null) return true;
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
            if (caps == null) return true;
            return caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private MediaFormat buildLayeredVideoFormat(
            String mime,
            int width,
            int height,
            int fps,
            boolean withLowLatency,
            boolean withPriority,
            int operatingRate,
            String vendorKey) {
        MediaFormat fmt = createBaseVideoFormat(mime, width, height, fps);
        if (withLowLatency && Build.VERSION.SDK_INT >= 30) {
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        if (withPriority) {
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        if (operatingRate > 0) {
            fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate);
        }
        if (vendorKey != null && !vendorKey.isEmpty()) {
            fmt.setInteger(vendorKey, 1);
        }
        return fmt;
    }

    private boolean configureLowLatencyDecoder(
            MediaCodec codec,
            String mime,
            int width,
            int height,
            int fps,
            Surface surface) {
        if (codec == null) return false;
        MediaFormat baseFmt = createBaseVideoFormat(mime, width, height, fps);
        if (!tryConfigureDecoderFormat(codec, baseFmt, surface)) {
            return false;
        }

        boolean applyLowLatency = false;
        boolean applyPriority = false;
        int appliedOperatingRate = 0;
        String appliedVendorKey = null;
        boolean currentlyConfigured = true;
        List<String> acceptedKeys = new ArrayList<>();
        List<String> skippedKeys = new ArrayList<>();

        boolean allowLowLatency = shouldEnableLowLatencyKeys(activeCodecName, Build.HARDWARE, width, height);
        if (Build.VERSION.SDK_INT >= 30) {
            if (allowLowLatency) {
                boolean featureAdvertised = supportsLowLatencyFeature(codec, mime);
                safeResetCodec(codec);
                MediaFormat llFmt = buildLayeredVideoFormat(mime, width, height, fps, true, false, 0, null);
                if (tryConfigureDecoderFormat(codec, llFmt, surface)) {
                    applyLowLatency = true;
                    currentlyConfigured = true;
                    acceptedKeys.add(MediaFormat.KEY_LOW_LATENCY + "(feat=" + featureAdvertised + ")");
                } else {
                    currentlyConfigured = false;
                    skippedKeys.add(MediaFormat.KEY_LOW_LATENCY);
                }
            } else {
                skippedKeys.add(MediaFormat.KEY_LOW_LATENCY + "(mtk_4k_guard)");
            }
        }

        safeResetCodec(codec);
        MediaFormat priFmt = buildLayeredVideoFormat(
                mime, width, height, fps, applyLowLatency, true, 0, null);
        if (tryConfigureDecoderFormat(codec, priFmt, surface)) {
            applyPriority = true;
            currentlyConfigured = true;
            acceptedKeys.add(MediaFormat.KEY_PRIORITY + "=0");
        } else {
            currentlyConfigured = false;
            skippedKeys.add(MediaFormat.KEY_PRIORITY);
        }

        int primaryOpRate = computeBoundedOperatingRateForSize(fps, width, height);
        safeResetCodec(codec);
        MediaFormat opFmt = buildLayeredVideoFormat(
                mime, width, height, fps, applyLowLatency, applyPriority, primaryOpRate, null);
        if (tryConfigureDecoderFormat(codec, opFmt, surface)) {
            appliedOperatingRate = primaryOpRate;
            currentlyConfigured = true;
            acceptedKeys.add(MediaFormat.KEY_OPERATING_RATE + "=" + primaryOpRate);
        } else {
            int fallbackOpRate = Math.max(fps, 60);
            boolean acceptedStepDown = false;
            if (fallbackOpRate > 0 && fallbackOpRate != primaryOpRate) {
                safeResetCodec(codec);
                MediaFormat stepFmt = buildLayeredVideoFormat(
                        mime, width, height, fps, applyLowLatency, applyPriority, fallbackOpRate, null);
                if (tryConfigureDecoderFormat(codec, stepFmt, surface)) {
                    appliedOperatingRate = fallbackOpRate;
                    currentlyConfigured = true;
                    acceptedStepDown = true;
                    acceptedKeys.add(MediaFormat.KEY_OPERATING_RATE + "=" + fallbackOpRate);
                }
            }
            if (!acceptedStepDown) {
                currentlyConfigured = false;
                skippedKeys.add(MediaFormat.KEY_OPERATING_RATE + "=" + primaryOpRate);
            }
        }

        String vendorKey = allowLowLatency ? resolveVendorLowLatencyKey(activeCodecName, Build.HARDWARE) : null;
        if (vendorKey != null) {
            safeResetCodec(codec);
            MediaFormat vendorFmt = buildLayeredVideoFormat(
                    mime, width, height, fps, applyLowLatency, applyPriority, appliedOperatingRate, vendorKey);
            if (tryConfigureDecoderFormat(codec, vendorFmt, surface)) {
                appliedVendorKey = vendorKey;
                currentlyConfigured = true;
                acceptedKeys.add(vendorKey + "=1");
            } else {
                currentlyConfigured = false;
                skippedKeys.add(vendorKey);
            }
        } else if (!allowLowLatency) {
            skippedKeys.add("vendor.mtk.vdec.lowlatency(mtk_4k_guard)");
        }

        if (!currentlyConfigured) {
            MediaFormat bestFmt = buildLayeredVideoFormat(
                    mime, width, height, fps, applyLowLatency, applyPriority, appliedOperatingRate, appliedVendorKey);
            if (!tryConfigureDecoderFormat(codec, bestFmt, surface)) {
                return false;
            }
        }

        lowLatencyActive = applyLowLatency || appliedVendorKey != null;
        AppLog.i(String.format(
                Locale.US,
                "GameStream decoder configured: codec=%s mime=%s res=%dx%d fps=%d lowLatency=%b accepted=%s skipped=%s",
                activeCodecName,
                mime,
                width,
                height,
                fps,
                lowLatencyActive,
                acceptedKeys.toString(),
                skippedKeys.toString()));
        return true;
    }

    static int writeAccessUnitToInputBuffer(
            ByteBuffer inBuf,
            byte[] vps,
            byte[] sps,
            byte[] pps,
            byte[] decodeUnitData,
            int decodeUnitLength) {
        if (inBuf == null || decodeUnitData == null || decodeUnitLength <= 0
                || decodeUnitLength > decodeUnitData.length) {
            return -1;
        }
        int vpsLen = vps != null ? vps.length : 0;
        int spsLen = sps != null ? sps.length : 0;
        int ppsLen = pps != null ? pps.length : 0;
        int totalLen = vpsLen + spsLen + ppsLen + decodeUnitLength;
        if (inBuf.remaining() < totalLen) {
            return -1;
        }
        if (vpsLen > 0) inBuf.put(vps, 0, vpsLen);
        if (spsLen > 0) inBuf.put(sps, 0, spsLen);
        if (ppsLen > 0) inBuf.put(pps, 0, ppsLen);
        inBuf.put(decodeUnitData, 0, decodeUnitLength);
        return totalLen;
    }

    private int handleDroppedInputFrame() {
        droppedInputFrames.incrementAndGet();
        long nowMs = 0L;
        try {
            nowMs = SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
            nowMs = System.nanoTime() / 1_000_000L;
        }
        if (idrCooldownTracker.tryConsumeIdrRequest(nowMs)) {
            idrRequestsCount.incrementAndGet();
            return MoonBridge.DR_NEED_IDR;
        }
        return MoonBridge.DR_OK;
    }

    static boolean isValidDecodeUnit(byte[] decodeUnitData, int decodeUnitLength) {
        return decodeUnitData != null
                && decodeUnitLength > 0
                && decodeUnitLength <= decodeUnitData.length;
    }

    @Override
    public void drStart() {
    }

    @Override
    public int drSubmitDecodeUnit(
            byte[] decodeUnitData,
            int decodeUnitLength,
            int decodeUnitType,
            int frameNumber,
            int frameType,
            char frameHostProcessingLatency,
            long receiveTimeUs,
            long enqueueTimeUs) {
        if (!isValidDecodeUnit(decodeUnitData, decodeUnitLength)) {
            return MoonBridge.DR_OK;
        }
        MediaCodec codec = videoCodec;
        if (!decoderRunning.get() || codec == null) {
            return MoonBridge.DR_OK;
        }
        if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
            cachedVps = copyPrefix(decodeUnitData, decodeUnitLength);
            return MoonBridge.DR_OK;
        }
        if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
            cachedSps = copyPrefix(decodeUnitData, decodeUnitLength);
            return MoonBridge.DR_OK;
        }
        if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
            cachedPps = copyPrefix(decodeUnitData, decodeUnitLength);
            return MoonBridge.DR_OK;
        }

        inputFramesThisWindow.incrementAndGet();
        inputBytesThisWindow.addAndGet(decodeUnitLength);

        boolean isIdr = (frameType == MoonBridge.FRAME_TYPE_IDR);
        byte[] vps = isIdr ? cachedVps : null;
        byte[] sps = isIdr ? cachedSps : null;
        byte[] pps = isIdr ? cachedPps : null;
        long ptsUs = enqueueTimeUs > 0L ? enqueueTimeUs : (System.nanoTime() / 1000L);
        int flags = isIdr ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;

        try {
            if (pendingFrameRing.isEmpty()) {
                int inIndex = codec.dequeueInputBuffer(0L);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                    if (inBuf != null) {
                        inBuf.clear();
                        int totalLen = writeAccessUnitToInputBuffer(
                                inBuf, vps, sps, pps, decodeUnitData, decodeUnitLength);
                        if (totalLen > 0) {
                            codec.queueInputBuffer(inIndex, 0, totalLen, ptsUs, flags);
                            return MoonBridge.DR_OK;
                        }
                    }
                    codec.queueInputBuffer(inIndex, 0, 0, 0L, 0);
                    return handleDroppedInputFrame();
                }
            }

            if (pendingFrameRing.offer(vps, sps, pps, decodeUnitData, decodeUnitLength, ptsUs, flags)) {
                return MoonBridge.DR_OK;
            }
            return handleDroppedInputFrame();
        } catch (IllegalStateException stopped) {
            if (!decoderRunning.get()) {
                return MoonBridge.DR_OK;
            }
            return handleDroppedInputFrame();
        } catch (Throwable t) {
            AppLog.w("GameStreamActivity drSubmitDecodeUnit error: " + t.getMessage());
            return handleDroppedInputFrame();
        }
    }

    private void startVideoInputDrainThread() {
        stopVideoInputDrainThread();
        videoInputThread = new Thread(this::runVideoInputDrainLoop, "soartv-gamestream-vin");
        videoInputThread.start();
    }

    private void stopVideoInputDrainThread() {
        Thread t = videoInputThread;
        videoInputThread = null;
        pendingFrameRing.clear();
        if (t != null) {
            t.interrupt();
        }
    }

    private void runVideoInputDrainLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable ignored) {
        }
        while (decoderRunning.get() && !Thread.currentThread().isInterrupted()) {
            try {
                PendingFrameRing.FrameSlot slot = pendingFrameRing.awaitHead(25L);
                if (slot == null) continue;
                MediaCodec codec = videoCodec;
                if (codec == null || !decoderRunning.get()) break;
                int inIndex = codec.dequeueInputBuffer(DRAIN_INPUT_TIMEOUT_US);
                if (inIndex < 0) continue;
                ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                if (inBuf != null) {
                    inBuf.clear();
                    if (inBuf.remaining() >= slot.length) {
                        inBuf.put(slot.data, 0, slot.length);
                        codec.queueInputBuffer(inIndex, 0, slot.length, slot.ptsUs, slot.flags);
                    } else {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, 0);
                        droppedInputFrames.incrementAndGet();
                    }
                } else {
                    codec.queueInputBuffer(inIndex, 0, 0, 0L, 0);
                    droppedInputFrames.incrementAndGet();
                }
                pendingFrameRing.popHead();
            } catch (InterruptedException ie) {
                break;
            } catch (IllegalStateException stopped) {
                break;
            } catch (Throwable ignored) {
                pendingFrameRing.popHead();
            }
        }
    }

    private static byte[] copyPrefix(byte[] src, int len) {
        int safeLen = Math.min(src.length, Math.max(0, len));
        byte[] copy = new byte[safeLen];
        System.arraycopy(src, 0, copy, 0, safeLen);
        return copy;
    }

    private void startVideoOutputDrainThread() {
        stopVideoOutputDrainThread();
        videoOutputThread = new Thread(this::runVideoOutputDrainLoop, "soartv-gamestream-vout");
        videoOutputThread.start();
    }

    private void stopVideoOutputDrainThread() {
        Thread t = videoOutputThread;
        videoOutputThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void runVideoOutputDrainLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable ignored) {
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo peekInfo = new MediaCodec.BufferInfo();
        final boolean lowLatencyMode = "low_latency".equalsIgnoreCase(framePacingMode);
        final boolean smoothnessMode = "smoothness".equalsIgnoreCase(framePacingMode);

        while (decoderRunning.get() && !Thread.currentThread().isInterrupted()) {
            MediaCodec codecRef = videoCodec;
            if (codecRef == null) break;

            try {
                int outIndex = codecRef.dequeueOutputBuffer(info, DEQUEUE_OUTPUT_TIMEOUT_US);
                if (outIndex >= 0) {
                    // Never allow multi-frame decoder queue buildup to introduce lag or stutter:
                    // if another frame is already decoded and waiting in low-latency/balanced mode, drop the stale one.
                    if (!smoothnessMode) {
                        while (decoderRunning.get()) {
                            int newer = codecRef.dequeueOutputBuffer(peekInfo, 0L);
                            if (newer >= 0) {
                                codecRef.releaseOutputBuffer(outIndex, false);
                                droppedOutputFrames.incrementAndGet();
                                info.set(
                                        peekInfo.offset,
                                        peekInfo.size,
                                        peekInfo.presentationTimeUs,
                                        peekInfo.flags);
                                outIndex = newer;
                            } else {
                                break;
                            }
                        }
                    }

                    long nowUs = System.nanoTime() / 1000L;
                    if (info.presentationTimeUs > 0L && nowUs >= info.presentationTimeUs) {
                        long latUs = nowUs - info.presentationTimeUs;
                        if (latUs <= 2_000_000L) {
                            decodeLatencySumUs.addAndGet(latUs);
                            decodeLatencySamples.incrementAndGet();
                            long prevMax;
                            while (latUs > (prevMax = maxDecodeLatencyUs.get())) {
                                if (maxDecodeLatencyUs.compareAndSet(prevMax, latUs)) {
                                    break;
                                }
                            }
                        }
                    }

                    if (lowLatencyMode) {
                        codecRef.releaseOutputBuffer(outIndex, true);
                    } else {
                        long renderNs = computePacedRenderTimestampNs(smoothnessMode);
                        codecRef.releaseOutputBuffer(outIndex, renderNs);
                    }
                    decodedFramesThisSec.incrementAndGet();
                    outputFramesThisWindow.incrementAndGet();
                    if (firstFrameRendered.compareAndSet(false, true)) {
                        uiHandler.post(() -> {
                            if (statusPill != null) {
                                statusPill.setVisibility(View.GONE);
                            }
                        });
                    }
                }
            } catch (IllegalStateException stopped) {
                break;
            } catch (Throwable t) {
                if (decoderRunning.get()) {
                    AppLog.w("GameStreamActivity video output drain error: " + t.getMessage());
                }
            }
        }
    }

    static long computeNextPacedRenderNs(
            long nowNs,
            long anchorNs,
            long periodNs,
            long prevScheduledNs,
            boolean smoothnessMode) {
        long safePeriod = Math.max(8_000_000L, periodNs);
        long targetNs;
        if (smoothnessMode) {
            if (prevScheduledNs > nowNs && prevScheduledNs < nowNs + safePeriod * 2L) {
                targetNs = prevScheduledNs + safePeriod;
            } else if (anchorNs > 0L && nowNs >= anchorNs && (nowNs - anchorNs) < safePeriod * 4L) {
                long elapsed = (nowNs - anchorNs) / safePeriod;
                targetNs = anchorNs + (elapsed + 1L) * safePeriod;
            } else {
                targetNs = nowNs + safePeriod;
            }
            if (targetNs > nowNs + safePeriod * 2L) {
                targetNs = nowNs + safePeriod * 2L;
            }
            if (prevScheduledNs > 0L && targetNs < prevScheduledNs) {
                targetNs = prevScheduledNs;
            }
            return targetNs;
        }

        // Balanced mode: align to the next VSYNC >= nowNs without 2-frame SurfaceFlinger stalls or backward jumps.
        if (anchorNs > 0L && nowNs >= anchorNs && (nowNs - anchorNs) < safePeriod * 4L) {
            long elapsed = (nowNs - anchorNs) / safePeriod;
            long nextVsync = anchorNs + (elapsed + 1L) * safePeriod;
            long candidate = nextVsync - 1_000_000L;
            targetNs = candidate >= nowNs ? candidate : (nextVsync + safePeriod - 1_000_000L);
        } else {
            targetNs = nowNs;
        }
        long minStepNs = (safePeriod * 3L) / 4L;
        if (prevScheduledNs > 0L && targetNs < prevScheduledNs + minStepNs) {
            long stepped = prevScheduledNs + minStepNs;
            if (stepped <= nowNs + safePeriod + 2_000_000L) {
                targetNs = stepped;
            }
        }
        long maxLeadNs = nowNs + safePeriod + 2_000_000L;
        if (targetNs > maxLeadNs) {
            targetNs = maxLeadNs;
        }
        if (prevScheduledNs > 0L && targetNs < prevScheduledNs) {
            targetNs = prevScheduledNs;
        }
        return targetNs;
    }

    private long computePacedRenderTimestampNs(boolean smoothnessMode) {
        long targetNs = computeNextPacedRenderNs(
                System.nanoTime(),
                lastVsyncNs,
                vsyncPeriodNs,
                lastScheduledRenderNs,
                smoothnessMode);
        lastScheduledRenderNs = targetNs;
        return targetNs;
    }

    @Override
    public void drStop() {
        decoderRunning.set(false);
        uiHandler.removeCallbacks(telemetryTicker);
        stopVideoInputDrainThread();
        stopVideoOutputDrainThread();
    }

    @Override
    public void drCleanup() {
        decoderRunning.set(false);
        uiHandler.removeCallbacks(telemetryTicker);
        stopVideoInputDrainThread();
        stopVideoOutputDrainThread();
        synchronized (codecLock) {
            safeReleaseVideoCodecLocked();
        }
    }

    private void safeReleaseVideoCodecLocked() {
        if (videoCodec != null) {
            try {
                videoCodec.stop();
            } catch (Throwable ignored) {
            }
            try {
                videoCodec.release();
            } catch (Throwable ignored) {
            }
            videoCodec = null;
        }
    }

    // --- MoonBridge Audio Callbacks ---

    @Override
    public int arInit(int audioConfiguration, int sampleRate, int samplesPerFrame) {
        try {
            MoonBridge.AudioConfiguration cfg = new MoonBridge.AudioConfiguration(audioConfiguration);
            int channelMask;
            if (cfg.channelCount >= 8) {
                channelMask = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
            } else if (cfg.channelCount >= 6) {
                channelMask = AudioFormat.CHANNEL_OUT_5POINT1;
            } else {
                channelMask = AudioFormat.CHANNEL_OUT_STEREO;
            }

            int minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            int frameBytes = Math.max(1, samplesPerFrame * cfg.channelCount * 2);
            int bufferBytes = Math.max(minBuf, frameBytes * 4);

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build();

            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            arCleanup();
            AudioTrack created = builder.build();
            synchronized (audioLock) {
                audioTrack = created;
                created.play();
            }
            return 0;
        } catch (Throwable t) {
            AppLog.w("GameStreamActivity arInit failed: " + t.getMessage());
            return -1;
        }
    }

    @Override
    public void arStart() {
        try {
            synchronized (audioLock) {
                AudioTrack track = audioTrack;
                if (track != null) track.play();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void arStop() {
        try {
            synchronized (audioLock) {
                AudioTrack track = audioTrack;
                if (track != null) track.pause();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void arCleanup() {
        final AudioTrack track;
        synchronized (audioLock) {
            track = audioTrack;
            audioTrack = null;
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void arPlaySample(short[] pcmData) {
        AudioTrack track = audioTrack;
        if (track == null || pcmData == null || pcmData.length == 0) return;
        try {
            track.write(pcmData, 0, pcmData.length, AudioTrack.WRITE_NON_BLOCKING);
        } catch (Throwable ignored) {
        }
    }

    // --- MoonBridge Connection Lifecycle Callbacks ---

    @Override
    public void clStageStarting(String stageName) {
        updateStatusText("Starting " + gameTitle + " · " + stageName + "…");
    }

    @Override
    public void clStageComplete(String stageName) {
    }

    @Override
    public void clStageFailed(String stageName, int portFlags) {
        if (!exitHandled.get()) {
            failStreamAndReturnToGaming("Connection failed during " + stageName + ".");
        }
    }

    @Override
    public void clConnectionStarted() {
        updateStatusText("Connected · Waiting for first video frame…");
    }

    @Override
    public void clConnectionTerminated(int errorCode) {
        // Triggered automatically when the user selects Quit/Exit inside the PC game menu
        // (laptop_gaming_agent closes Sunshine's active app) or Sunshine stops streaming.
        exitStreamAndTerminatePcGame("in_game_quit_or_stream_end_" + errorCode, true);
    }

    @Override
    public void clRumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        if (!rumbleEnabled) return;
        int slot = Math.max(0, Math.min(pads.length - 1, controllerNumber));
        int devId = pads[slot].deviceId;
        if (devId < 0) return;
        try {
            InputDevice dev = InputDevice.getDevice(devId);
            if (dev == null) return;
            Vibrator vib = dev.getVibrator();
            if (vib == null || !vib.hasVibrator()) return;
            int low = lowFreqMotor & 0xFFFF;
            int high = highFreqMotor & 0xFFFF;
            int amplitude = Math.min(255, Math.max(low, high) / 257);
            if (amplitude <= 2) {
                vib.cancel();
            } else if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(120L, amplitude));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void clConnectionStatusUpdate(int connectionStatus) {
    }

    @Override
    public void clSetHdrMode(boolean enabled, byte[] hdrMetadata) {
    }

    @Override
    public void clRumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
    }

    // --- Input Routing: TV Remote Exit vs Physical Gamepad & Virtual Mouse ---

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return false;
        final int keyCode = event.getKeyCode();
        final InputDevice device = event.getDevice();
        final boolean isGamepad = GamingZone.isPhysicalGameController(device);

        // 1. TV Remote Back / Escape (never from a physical game controller) closes the PC game and returns to Gaming!
        if (!isGamepad && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                remoteBackPressedDown = true;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && remoteBackPressedDown) {
                remoteBackPressedDown = false;
                exitStreamAndTerminatePcGame("tv_remote_back", true);
                return true;
            }
            return true;
        }

        // Allow TV system volume keys to work natively without interference
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }

        if (isGamepad) {
            return handleGamepadKeyEvent(device, event);
        }

        // TV Remote D-Pad / OK convenience forwarding (e.g. clicking an in-game menu prompt with the TV remote)
        return handleRemoteMenuKeyAsController(event) || super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event == null) return false;
        InputDevice device = event.getDevice();
        if (GamingZone.isPhysicalGameController(device)
                && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return handleGamepadMotionEvent(device, event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private int resolveControllerSlot(InputDevice device) {
        int devId = device != null ? device.getId() : 0;
        for (int i = 0; i < pads.length; i++) {
            if (pads[i].deviceId == devId) return i;
        }
        for (int i = 0; i < pads.length; i++) {
            if (pads[i].deviceId < 0) {
                pads[i].deviceId = devId;
                return i;
            }
        }
        return 0;
    }

    private short activeGamepadMask() {
        short mask = 0;
        for (int i = 0; i < pads.length; i++) {
            if (pads[i].deviceId >= 0) {
                mask |= (short) (1 << i);
            }
        }
        return mask == 0 ? (short) 1 : mask;
    }

    private boolean handleGamepadKeyEvent(InputDevice device, KeyEvent event) {
        int slot = resolveControllerSlot(device);
        PadState pad = pads[slot];
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int keyCode = event.getKeyCode();

        // Long-press Start toggles virtual mouse emulation when enabled in Gaming Settings
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START && mouseEmulationAllowed) {
            if (down) {
                if (!pad.startHeld) {
                    pad.startHeld = true;
                    pad.startDownUptimeMs = SystemClock.uptimeMillis();
                    pad.mouseToggleConsumed = false;
                    uiHandler.postDelayed(() -> {
                        if (pad.startHeld && !pad.mouseToggleConsumed
                                && (SystemClock.uptimeMillis() - pad.startDownUptimeMs) >= MOUSE_EMULATION_TOGGLE_HOLD_MS) {
                            pad.mouseToggleConsumed = true;
                            toggleMouseEmulation();
                        }
                    }, MOUSE_EMULATION_TOGGLE_HOLD_MS + 20L);
                }
                return true;
            } else {
                boolean wasConsumed = pad.mouseToggleConsumed;
                pad.startHeld = false;
                pad.mouseToggleConsumed = false;
                if (!wasConsumed) {
                    // Short tap of Start -> emit clean press + release to the game
                    pad.buttons |= BTN_PLAY;
                    flushControllerState(slot);
                    uiHandler.postDelayed(() -> {
                        pad.buttons &= ~BTN_PLAY;
                        flushControllerState(slot);
                    }, 45L);
                }
                return true;
            }
        }

        if (mouseEmulationActive && slot == 0) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (MoonBridge.isNativeLoaded() && streamRunning.get()) {
                    MoonBridge.sendMouseButton(
                            down ? MoonBridge.BUTTON_ACTION_PRESS : MoonBridge.BUTTON_ACTION_RELEASE,
                            MoonBridge.BUTTON_LEFT);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                if (MoonBridge.isNativeLoaded() && streamRunning.get()) {
                    MoonBridge.sendMouseButton(
                            down ? MoonBridge.BUTTON_ACTION_PRESS : MoonBridge.BUTTON_ACTION_RELEASE,
                            MoonBridge.BUTTON_RIGHT);
                }
                return true;
            }
        }

        short flag = mapKeyCodeToButtonFlag(keyCode);
        if (flag == 0) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                pad.leftTrigger = down ? (byte) 0xFF : 0;
                flushControllerState(slot);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                pad.rightTrigger = down ? (byte) 0xFF : 0;
                flushControllerState(slot);
                return true;
            }
            return false;
        }
        if (down) {
            pad.buttons |= flag;
        } else {
            pad.buttons &= ~flag;
        }
        flushControllerState(slot);
        return true;
    }

    private boolean handleRemoteMenuKeyAsController(KeyEvent event) {
        short flag = 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                flag = BTN_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                flag = BTN_DOWN;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                flag = BTN_LEFT;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                flag = BTN_RIGHT;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                flag = BTN_A;
                break;
            default:
                return false;
        }
        PadState pad = pads[0];
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            pad.buttons |= flag;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            pad.buttons &= ~flag;
        }
        flushControllerState(0);
        return true;
    }

    private static short mapKeyCodeToButtonFlag(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return BTN_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return BTN_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return BTN_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return BTN_RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A:
                return BTN_A;
            case KeyEvent.KEYCODE_BUTTON_B:
                return BTN_B;
            case KeyEvent.KEYCODE_BUTTON_X:
                return BTN_X;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return BTN_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return BTN_LB;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return BTN_RB;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return BTN_LS_CLK;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return BTN_RS_CLK;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:
                return BTN_BACK;
            case KeyEvent.KEYCODE_BUTTON_START:
                return BTN_PLAY;
            case KeyEvent.KEYCODE_BUTTON_MODE:
                return BTN_SPECIAL;
            default:
                return 0;
        }
    }

    private boolean handleGamepadMotionEvent(InputDevice device, MotionEvent event) {
        int slot = resolveControllerSlot(device);
        PadState pad = pads[slot];

        float lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X), STICK_DEADZONE);
        float ly = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y), STICK_DEADZONE);
        float rx = firstNonZeroAxis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX);
        float ry = firstNonZeroAxis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY);
        rx = applyDeadzone(rx, STICK_DEADZONE);
        ry = applyDeadzone(ry, STICK_DEADZONE);

        float lt = Math.max(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS));

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        short hatMask = 0;
        if (hatX < -0.5f) hatMask |= BTN_LEFT;
        else if (hatX > 0.5f) hatMask |= BTN_RIGHT;
        if (hatY < -0.5f) hatMask |= BTN_UP;
        else if (hatY > 0.5f) hatMask |= BTN_DOWN;

        pad.dpadButtons = hatMask;
        pad.leftStickX = floatToShortAxis(lx);
        pad.leftStickY = floatToShortAxis(-ly);
        pad.rightStickX = floatToShortAxis(rx);
        pad.rightStickY = floatToShortAxis(-ry);
        pad.leftTrigger = (byte) Math.round(applyDeadzone(lt, TRIGGER_DEADZONE) * 255.0f);
        pad.rightTrigger = (byte) Math.round(applyDeadzone(rt, TRIGGER_DEADZONE) * 255.0f);

        if (!mouseEmulationActive || slot != 0) {
            flushControllerState(slot);
        }
        return true;
    }

    private static float firstNonZeroAxis(MotionEvent event, int primaryAxis, int fallbackAxis) {
        float v = event.getAxisValue(primaryAxis);
        if (Math.abs(v) > 0.01f) return v;
        return event.getAxisValue(fallbackAxis);
    }

    private static float applyDeadzone(float value, float deadzone) {
        if (Math.abs(value) <= deadzone) return 0.0f;
        float sign = Math.signum(value);
        float norm = (Math.abs(value) - deadzone) / (1.0f - deadzone);
        return sign * Math.min(1.0f, Math.max(0.0f, norm));
    }

    private static short floatToShortAxis(float v) {
        int scaled = Math.round(Math.max(-1.0f, Math.min(1.0f, v)) * 32767.0f);
        return (short) scaled;
    }

    private void flushControllerState(int slot) {
        if (!streamRunning.get() || !MoonBridge.isNativeLoaded()) return;
        PadState pad = pads[slot];
        short combinedButtons = (short) (pad.buttons | pad.dpadButtons);
        MoonBridge.sendMultiControllerInput(
                (short) slot,
                activeGamepadMask(),
                combinedButtons,
                pad.leftTrigger,
                pad.rightTrigger,
                pad.leftStickX,
                pad.leftStickY,
                pad.rightStickX,
                pad.rightStickY);
    }

    private void toggleMouseEmulation() {
        mouseEmulationActive = !mouseEmulationActive;
        uiHandler.removeCallbacks(mouseTickRunnable);
        if (mouseEmulationActive) {
            uiHandler.post(mouseTickRunnable);
            Toast.makeText(this, "Virtual Mouse Mode ON (hold Start to switch back)", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Gamepad Mode ON", Toast.LENGTH_SHORT).show();
        }
    }

    // --- Deterministic Teardown & Return Routing ---

    private void updateStatusText(final String message) {
        uiHandler.post(() -> {
            if (statusPill != null && !firstFrameRendered.get()) {
                statusPill.setText(message);
                statusPill.setVisibility(View.VISIBLE);
            }
        });
    }

    private void failStreamAndReturnToGaming(final String userMessage) {
        uiHandler.post(() -> {
            if (userMessage != null && !userMessage.trim().isEmpty()) {
                Toast.makeText(getApplicationContext(), userMessage, Toast.LENGTH_LONG).show();
            }
            exitStreamAndTerminatePcGame("stream_error_cleanup", true);
        });
    }

    /**
     * Atomically stops {@link MoonBridge}, terminates the active PC game + restores PC privacy via
     * {@link GamingZone#terminateActivePCGameNow}, cancels the Sunshine RTSP session, and optionally
     * navigates straight back to SoarTV's {@code Gaming} screen (`HomeActivity.EXTRA_OPEN_GAMING`).
     */
    private void exitStreamAndTerminatePcGame(final String reason, final boolean reopenGamingScreen) {
        if (!exitHandled.compareAndSet(false, true)) {
            return;
        }
        final Context appCtx = getApplicationContext();
        final String targetHost = hostIp;
        final String targetGameId = gameId;

        Recs.setBackgroundActivityPaused(false);
        ScreensaverCatalog.setBackgroundActivityPaused(false);
        GamingZone.terminateActivePCGameNow(appCtx, targetGameId, reason);

        new Thread(() -> {
            stopNativeConnectionAndRenderers();
            if (targetHost != null && !targetHost.isEmpty()) {
                SunshineStreamClient.cancelSession(appCtx, targetHost);
            }
        }, "soartv-gamestream-teardown").start();

        uiHandler.post(() -> {
            if (reopenGamingScreen) {
                try {
                    Intent backToGaming = new Intent(appCtx, HomeActivity.class);
                    backToGaming.putExtra(HomeActivity.EXTRA_OPEN_GAMING, true);
                    backToGaming.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(backToGaming);
                } catch (Throwable ignored) {
                }
            }
            if (!isFinishing()) {
                finish();
            }
            overridePendingTransition(0, 0);
        });
    }

    private void stopNativeConnectionAndRenderers() {
        boolean wasRunning = streamRunning.getAndSet(false);
        MoonBridge.setCallbacks(null);
        if (wasRunning && MoonBridge.isNativeLoaded()) {
            try {
                MoonBridge.interruptConnection();
            } catch (Throwable ignored) {
            }
            try {
                MoonBridge.stopConnection();
            } catch (Throwable ignored) {
            }
        }
        drStop();
        drCleanup();
        arStop();
        arCleanup();
    }
}
