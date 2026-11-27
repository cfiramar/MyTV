package com.limelight.nvstream.jni;

/**
 * JNI bridge binding directly to {@code libmoonlight-core.so} (`moonlight-common-c`) so MyTV
 * streams Sunshine GameStream video, surround PCM audio, and low-latency controller input natively
 * inside {@code io.mytv.GameStreamActivity} without ever opening external Moonlight UI.
 *
 * <p><b>Why:</b> Eliminates all handoffs to {@code com.limelight}, prevents external UI screens
 * from appearing on in-game quit, and gives MyTV deterministic control over {@code MediaCodec}
 * VSYNC frame pacing and remote exit lifecycle.
 * <p><b>What:</b> Exposes exact JNI native declarations and C-to-Java callback entrypoints expected
 * by {@code libmoonlight-core.so}, guarded safely in {@code <clinit>} so Desktop JVM unit tests run
 * without {@link UnsatisfiedLinkError}.
 */
public final class MoonBridge {

    public static final int VIDEO_FORMAT_H264 = 0x0001;
    public static final int VIDEO_FORMAT_H265 = 0x0100;
    public static final int VIDEO_FORMAT_H265_MAIN10 = 0x0200;
    public static final int VIDEO_FORMAT_AV1_MAIN8 = 0x1000;
    public static final int VIDEO_FORMAT_AV1_MAIN10 = 0x2000;

    public static final int VIDEO_FORMAT_MASK_H264 = 0x000F;
    public static final int VIDEO_FORMAT_MASK_H265 = 0x0F00;
    public static final int VIDEO_FORMAT_MASK_AV1 = 0xF000;
    public static final int VIDEO_FORMAT_MASK_10BIT = 0x2200;

    public static final int BUFFER_TYPE_PICDATA = 0;
    public static final int BUFFER_TYPE_SPS = 1;
    public static final int BUFFER_TYPE_PPS = 2;
    public static final int BUFFER_TYPE_VPS = 3;

    public static final int FRAME_TYPE_PFRAME = 0;
    public static final int FRAME_TYPE_IDR = 1;

    public static final int DR_OK = 0;
    public static final int DR_NEED_IDR = -1;

    public static final int CONN_STATUS_OKAY = 0;
    public static final int CONN_STATUS_POOR = 1;

    public static final int ML_ERROR_GRACEFUL_TERMINATION = 0;
    public static final int ML_ERROR_NO_VIDEO_TRAFFIC = -100;
    public static final int ML_ERROR_NO_VIDEO_FRAME = -101;
    public static final int ML_ERROR_UNEXPECTED_EARLY_TERMINATION = -102;
    public static final int ML_ERROR_PROTECTED_CONTENT = -103;
    public static final int ML_ERROR_FRAME_CONVERSION = -104;

    public static final int CAPABILITY_DIRECT_SUBMIT = 1;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC = 2;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC = 4;
    public static final int CAPABILITY_SLICES_PER_FRAME = 8;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1 = 64;

    public static final int COLORSPACE_REC_601 = 0;
    public static final int COLORSPACE_REC_709 = 1;
    public static final int COLORSPACE_REC_2020 = 2;

    public static final int COLOR_RANGE_LIMITED = 0;
    public static final int COLOR_RANGE_FULL = 1;

    public static final int ENCFLG_NONE = 0x00000000;
    public static final int ENCFLG_AUDIO = 0x00000001;
    public static final int ENCFLG_VIDEO = 0x00000002;
    public static final int ENCFLG_ALL = 0xFFFFFFFF;

    public static final byte BUTTON_ACTION_PRESS = 0x07;
    public static final byte BUTTON_ACTION_RELEASE = 0x08;
    public static final byte BUTTON_LEFT = 0x01;
    public static final byte BUTTON_MIDDLE = 0x02;
    public static final byte BUTTON_RIGHT = 0x03;

    public static final byte KEY_ACTION_DOWN = 0x03;
    public static final byte KEY_ACTION_UP = 0x04;

    /**
     * Immutable Opus audio channel layout descriptor matching {@code Limelight.h} {@code MAKE_AUDIO_CONFIGURATION}.
     */
    public static final class AudioConfiguration {
        private static final int MAGIC_BYTE = 0xCA;
        public final int channelCount;
        public final int channelMask;

        public AudioConfiguration(int channelCount, int channelMask) {
            this.channelCount = channelCount;
            this.channelMask = channelMask;
        }

        public AudioConfiguration(int rawValue) {
            if ((rawValue & 0xFF) != MAGIC_BYTE) {
                throw new IllegalArgumentException("Invalid AudioConfiguration magic byte");
            }
            this.channelCount = (rawValue >> 8) & 0xFF;
            this.channelMask = (rawValue >> 16) & 0xFFFF;
        }

        public int toInt() {
            return (this.channelMask << 16) | (this.channelCount << 8) | MAGIC_BYTE;
        }

        public int getSurroundAudioInfo() {
            return (this.channelMask << 16) | this.channelCount;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AudioConfiguration)) return false;
            AudioConfiguration other = (AudioConfiguration) o;
            return this.channelCount == other.channelCount && this.channelMask == other.channelMask;
        }

        @Override
        public int hashCode() {
            return toInt();
        }
    }

    public static final AudioConfiguration AUDIO_CONFIGURATION_STEREO = new AudioConfiguration(2, 0x03);
    public static final AudioConfiguration AUDIO_CONFIGURATION_51_SURROUND = new AudioConfiguration(6, 0x3F);
    public static final AudioConfiguration AUDIO_CONFIGURATION_71_SURROUND = new AudioConfiguration(8, 0x63F);

    /**
     * Listener interface receiving video decode units, Opus-decoded PCM audio samples, and connection
     * lifecycle callbacks from {@code libmoonlight-core.so}.
     */
    public interface BridgeCallbacks {
        int drSetup(int videoFormat, int width, int height, int redrawRate);
        void drStart();
        void drStop();
        void drCleanup();
        int drSubmitDecodeUnit(
                byte[] decodeUnitData,
                int decodeUnitLength,
                int decodeUnitType,
                int frameNumber,
                int frameType,
                char frameHostProcessingLatency,
                long receiveTimeUs,
                long enqueueTimeUs);

        int arInit(int audioConfiguration, int sampleRate, int samplesPerFrame);
        void arStart();
        void arStop();
        void arCleanup();
        void arPlaySample(short[] pcmData);

        void clStageStarting(String stageName);
        void clStageComplete(String stageName);
        void clStageFailed(String stageName, int portFlags);
        void clConnectionStarted();
        void clConnectionTerminated(int errorCode);
        void clRumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
        void clConnectionStatusUpdate(int connectionStatus);
        void clSetHdrMode(boolean enabled, byte[] hdrMetadata);
        void clRumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);
    }

    private static volatile BridgeCallbacks activeCallbacks;
    private static final boolean NATIVE_LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("moonlight-core");
            init();
            loaded = true;
        } catch (UnsatisfiedLinkError ignored) {
            // Expected in headless Desktop JVM unit tests where ARM64 Android .so is not loaded.
        }
        NATIVE_LOADED = loaded;
    }

    private MoonBridge() {}

    public static boolean isNativeLoaded() {
        return NATIVE_LOADED;
    }

    public static void setCallbacks(BridgeCallbacks callbacks) {
        activeCallbacks = callbacks;
    }

    public static String safeGetLaunchUrlQueryParameters() {
        if (!NATIVE_LOADED) {
            return "";
        }
        try {
            String params = getLaunchUrlQueryParameters();
            return params != null ? params : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    // --- Native C methods bound inside libmoonlight-core.so ---

    public static native void init();

    public static native int startConnection(
            String address,
            String appVersion,
            String gfeVersion,
            String rtspSessionUrl,
            int serverCodecModeSupport,
            int width,
            int height,
            int fps,
            int bitrate,
            int packetSize,
            int streamingRemotely,
            int audioConfiguration,
            int supportedVideoFormats,
            int clientRefreshRateX100,
            byte[] riAesKey,
            byte[] riAesIv,
            int videoCapabilities,
            int colorSpace,
            int colorRange);

    public static native void stopConnection();
    public static native void interruptConnection();

    public static native void sendMouseMove(short deltaX, short deltaY);
    public static native void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight);
    public static native void sendMouseMoveAsMousePosition(short deltaX, short deltaY, short referenceWidth, short referenceHeight);
    public static native void sendMouseButton(byte buttonEvent, byte mouseButton);
    public static native void sendMultiControllerInput(
            short controllerNumber,
            short activeGamepadMask,
            short buttonFlags,
            byte leftTrigger,
            byte rightTrigger,
            short leftStickX,
            short leftStickY,
            short rightStickX,
            short rightStickY);
    public static native int sendTouchEvent(
            byte eventType,
            int pointerId,
            float x,
            float y,
            float pressureOrDistance,
            float contactAreaMajor,
            float contactAreaMinor,
            short rotation);
    public static native int sendPenEvent(
            byte eventType,
            byte toolType,
            byte penButtons,
            float x,
            float y,
            float pressureOrDistance,
            float contactAreaMajor,
            float contactAreaMinor,
            short rotation,
            byte tilt);
    public static native int sendControllerArrivalEvent(
            byte controllerNumber,
            short activeGamepadMask,
            byte type,
            int supportedButtonFlags,
            short capabilities);
    public static native int sendControllerTouchEvent(
            byte controllerNumber,
            byte eventType,
            int pointerId,
            float x,
            float y,
            float pressure);
    public static native int sendControllerMotionEvent(
            byte controllerNumber,
            byte motionType,
            float x,
            float y,
            float z);
    public static native void sendControllerBatteryEvent(
            byte controllerNumber,
            byte batteryState,
            byte batteryPercentage);
    public static native void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags);
    public static native void sendMouseHighResScroll(short scrollAmount);
    public static native void sendMouseHighResHScroll(short scrollAmount);
    public static native void sendUtf8Text(String text);

    public static native String getStageName(int stage);
    public static native String findExternalAddressIP4(String stunHostName, int stunPort);
    public static native int getPendingAudioDuration();
    public static native int getPendingVideoFrames();
    public static native int testClientConnectivity(String testServerHostName, int referencePort, int testFlags);
    public static native int getPortFlagsFromStage(int stage);
    public static native int getPortFlagsFromTerminationErrorCode(int errorCode);
    public static native String stringifyPortFlags(int portFlags, String separator);
    public static native long getEstimatedRttInfo();
    public static native String getLaunchUrlQueryParameters();
    public static native boolean guessControllerHasPaddles(int vendorId, int productId);
    public static native boolean guessControllerHasShareButton(int vendorId, int productId);

    // --- Upcalls invoked directly from libmoonlight-core.so ---

    public static int bridgeDrSetup(int videoFormat, int width, int height, int redrawRate) {
        BridgeCallbacks cb = activeCallbacks;
        return cb != null ? cb.drSetup(videoFormat, width, height, redrawRate) : -1;
    }

    public static void bridgeDrStart() {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.drStart();
    }

    public static void bridgeDrStop() {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.drStop();
    }

    public static void bridgeDrCleanup() {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.drCleanup();
    }

    public static int bridgeDrSubmitDecodeUnit(
            byte[] decodeUnitData,
            int decodeUnitLength,
            int decodeUnitType,
            int frameNumber,
            int frameType,
            char frameHostProcessingLatency,
            long receiveTimeUs,
            long enqueueTimeUs) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb == null) return DR_OK;
        return cb.drSubmitDecodeUnit(
                decodeUnitData,
                decodeUnitLength,
                decodeUnitType,
                frameNumber,
                frameType,
                frameHostProcessingLatency,
                receiveTimeUs,
                enqueueTimeUs);
    }

    public static int bridgeArInit(int audioConfiguration, int sampleRate, int samplesPerFrame) {
        BridgeCallbacks cb = activeCallbacks;
        return cb != null ? cb.arInit(audioConfiguration, sampleRate, samplesPerFrame) : -1;
    }

    public static void bridgeArStart() {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.arStart();
    }

    public static void bridgeArStop() {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.arStop();
    }

    public static void bridgeArCleanup() {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.arCleanup();
    }

    public static void bridgeArPlaySample(short[] pcmData) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.arPlaySample(pcmData);
    }

    public static void bridgeClStageStarting(int stage) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.clStageStarting(NATIVE_LOADED ? getStageName(stage) : ("stage_" + stage));
    }

    public static void bridgeClStageComplete(int stage) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.clStageComplete(NATIVE_LOADED ? getStageName(stage) : ("stage_" + stage));
    }

    public static void bridgeClStageFailed(int stage, int portFlags) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.clStageFailed(NATIVE_LOADED ? getStageName(stage) : ("stage_" + stage), portFlags);
    }

    public static void bridgeClConnectionStarted() {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.clConnectionStarted();
    }

    public static void bridgeClConnectionTerminated(int errorCode) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.clConnectionTerminated(errorCode);
    }

    public static void bridgeClRumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.clRumble(controllerNumber, lowFreqMotor, highFreqMotor);
    }

    public static void bridgeClConnectionStatusUpdate(int connectionStatus) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.clConnectionStatusUpdate(connectionStatus);
    }

    public static void bridgeClSetHdrMode(boolean enabled, byte[] hdrMetadata) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.clSetHdrMode(enabled, hdrMetadata);
    }

    public static void bridgeClRumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        BridgeCallbacks cb = activeCallbacks;
        if (cb != null) cb.clRumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
    }

    public static void bridgeClSetMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {
        // Motion event reporting optional on Android TV
    }

    public static void bridgeClSetControllerLED(short controllerNumber, byte r, byte g, byte b) {
        // Controller RGB LED optional on Android TV
    }
}
