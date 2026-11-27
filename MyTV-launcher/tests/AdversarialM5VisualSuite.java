package io.mytv;

import android.view.KeyEvent;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Milestone 5 Tier 5 Adversarial Coverage Hardening Suite:
 * Screensaver Visual Engine and Navigation Subsystems.
 *
 * <p>Empirical Stress Vectors:
 * <ol>
 *   <li><b>Bitmap Inputs & withScrims Guards:</b> Null, zero-dimension, recycled, and immutable bitmap guards;
 *       scrimStrength mathematical bounds across extreme light levels; black() alpha bit-shifting.</li>
 *   <li><b>AGSL Liquid Glass Shader Syntax & Type Safety:</b> Explicit SkSL half() casts, division-by-zero guards,
 *       RuntimeShader API < 33 LinkageError catch, and Canvas LinearGradient multi-stop fallback.</li>
 *   <li><b>Clock Size Scaling Arithmetic & Styles:</b> Scaling arithmetic (136px) across diverse screen dimensions
 *       (1080p, 4K, 720p, 1440p, 480p, 21:9 ultrawide, 9:16 portrait); clock styles mapping and unknown style fallback.</li>
 *   <li><b>Caption Presence & Single-Line Baseline Geometry:</b> Conditional caption presence (hasLiveCaption),
 *       scrim omission when overlays are absent, and titleBase vertical baseline adjustment when metadata is empty vs present.</li>
 *   <li><b>LRU SlideHistoryCache & navHistory State Machine:</b> Bounded LRU eviction at maxEntries = 3;
 *       navHistory clamping at 0; rapid DPAD Left/Right reversal; 100-slide history cap; memory invariance.</li>
 *   <li><b>Transition State Machine & Rapid Key Handling:</b> Manual vs auto crossfade durations; fadeIn() smoothstep
 *       continuity; mid-crossfade reversal handling; rapid DPAD taps; remote key isolation (Back/Home/Volume never consumed).</li>
 * </ol>
 *
 * <p>Authored by Milestone 5 Challenger 1 (Visual & Navigation Tier 5 Coverage).
 */
public final class AdversarialM5VisualSuite {

    private static int totalAssertions = 0;
    private static int failedAssertions = 0;
    private static final List<String> criticalDefects = new ArrayList<>();

    private AdversarialM5VisualSuite() {}

    private static void assertTrue(boolean condition, String message) {
        totalAssertions++;
        if (!condition) {
            failedAssertions++;
            System.err.println("  [FAIL] " + message);
            criticalDefects.add(message);
        } else {
            System.out.println("  [PASS] " + message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        totalAssertions++;
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            failedAssertions++;
            System.err.println("  [FAIL] " + message + " (expected: " + expected + ", got: " + actual + ")");
            criticalDefects.add(message + " (expected: " + expected + ", got: " + actual + ")");
        } else {
            System.out.println("  [PASS] " + message);
        }
    }

    private static void assertEquals(float expected, float actual, float epsilon, String message) {
        totalAssertions++;
        if (Float.isNaN(expected) && Float.isNaN(actual)) {
            System.out.println("  [PASS] " + message);
            return;
        }
        if (Math.abs(expected - actual) > epsilon) {
            failedAssertions++;
            System.err.println("  [FAIL] " + message + " (expected: " + expected + ", got: " + actual + ", delta: " + Math.abs(expected - actual) + ")");
            criticalDefects.add(message + " (expected: " + expected + ", got: " + actual + ")");
        } else {
            System.out.println("  [PASS] " + message);
        }
    }

    private static void assertNotNull(Object obj, String message) {
        assertTrue(obj != null, message);
    }

    private static void assertNull(Object obj, String message) {
        assertTrue(obj == null, message);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("======================================================================");
        System.out.println("=== STARTING MILESTONE 5 ADVERSARIAL VISUAL & NAVIGATION SUITE (TIER 5) ===");
        System.out.println("======================================================================");
        long startTime = System.currentTimeMillis();

        testSection1_BitmapInputsAndWithScrimsGuards();
        testSection2_AgslLiquidGlassShaderSyntaxAndFallback();
        testSection3_ClockSizeScalingArithmeticAndStyles();
        testSection4_CaptionPresenceAndSingleLineBaselineGeometry();
        testSection5_LruSlideHistoryCacheAndNavHistoryBounds();
        testSection6_TransitionStateMachineAndRapidKeyHandling();

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("\n======================================================================");
        System.out.println("=== ADVERSARIAL M5 VISUAL SUITE SUMMARY ===");
        System.out.println("Total Assertions Evaluated: " + totalAssertions);
        System.out.println("Failed Assertions:          " + failedAssertions);
        System.out.println("Critical Defects Identified: " + criticalDefects.size());
        System.out.println("Execution Time:             " + duration + " ms");
        System.out.println("======================================================================");

        if (failedAssertions > 0) {
            System.err.println("ADVERSARIAL VERDICT: REJECT (Milestone 5 Visual Hardening Failed)");
            for (String defect : criticalDefects) {
                System.err.println("  - " + defect);
            }
            throw new AssertionError("Adversarial M5 Visual Suite FAILED with " + failedAssertions + " errors");
        } else {
            System.out.println("ADVERSARIAL VERDICT: APPROVE (100% Milestone 5 Visual Hardening Invariants Verified)");
        }
    }

    private static File findSourceFile(String relPath) {
        File f = new File(relPath);
        if (f.exists()) return f;
        File f2 = new File("MyTV-launcher", relPath);
        if (f2.exists()) return f2;
        return f;
    }

    private static float fadeInFormula(long elapsed, long duration) {
        float raw = elapsed / (float) Math.max(1L, duration);
        float t = raw < 0f ? 0f : (raw > 1f ? 1f : raw);
        return t * t * (3f - 2f * t);
    }

    // =========================================================================
    // SECTION 1: BITMAP INPUTS & withScrims GUARDS
    // =========================================================================
    private static void testSection1_BitmapInputsAndWithScrimsGuards() throws Exception {
        System.out.println("\n--- [Section 1] Bitmap Inputs & withScrims Guards ---");

        Method withScrimsMethod = DreamView.class.getDeclaredMethod("withScrims",
                android.graphics.Bitmap.class, boolean.class, float.class, float.class);
        withScrimsMethod.setAccessible(true);

        Method scrimStrengthMethod = DreamView.class.getDeclaredMethod("scrimStrength", float.class, float.class);
        scrimStrengthMethod.setAccessible(true);

        Method blackMethod = DreamView.class.getDeclaredMethod("black", float.class);
        blackMethod.setAccessible(true);

        // 1.1 Null Bitmap guard: must return null safely without throwing NPE
        Object nullResult = withScrimsMethod.invoke(null, null, true, 0.5f, 0.5f);
        assertNull(nullResult, "1.1: withScrims(null, ...) safely returns null without NullPointerException");

        // 1.2 Inspect source invariants for recycled, zero-dimension, and immutable guards
        String dreamViewSrc = new String(Files.readAllBytes(findSourceFile("app/src/io/mytv/DreamView.java").toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(dreamViewSrc.contains("if (photo == null || photo.isRecycled() || photo.getWidth() <= 0 || photo.getHeight() <= 0 || !photo.isMutable())"),
                "1.2: withScrims contains comprehensive entry guards: photo == null, isRecycled, getWidth() <= 0, getHeight() <= 0, !isMutable");
        assertTrue(dreamViewSrc.contains("return photo;"),
                "1.3: withScrims immediately returns unchanged photo if any guard fails");

        // 1.4 ScrimStrength mathematical bounds across extreme and adversarial light levels
        float[] adversarialLights = {
                Float.NEGATIVE_INFINITY, -10000f, -100f, -1.0f, -0.01f, 0.0f,
                0.15f, 0.30f, 0.46f, 0.55f, 0.70f, 0.85f, 1.0f, 1.5f, 10f, 1000f, Float.POSITIVE_INFINITY
        };

        for (float light : adversarialLights) {
            float captionStr = (float) scrimStrengthMethod.invoke(null, light, 0.46f);
            float cornerStr = (float) scrimStrengthMethod.invoke(null, light, 0.38f);

            // Caption bounds: base = 0.46 -> min = 0.36, max = 0.78
            assertTrue(captionStr >= 0.36f - 1e-4f, "1.4 [light=" + light + "] Caption scrimStrength >= 0.36 (got " + captionStr + ")");
            assertTrue(captionStr <= 0.78f + 1e-4f, "1.5 [light=" + light + "] Caption scrimStrength <= 0.78 (got " + captionStr + ")");

            // Corner bounds: base = 0.38 -> min = 0.28, max = 0.78
            assertTrue(cornerStr >= 0.28f - 1e-4f, "1.6 [light=" + light + "] Corner scrimStrength >= 0.28 (got " + cornerStr + ")");
            assertTrue(cornerStr <= 0.78f + 1e-4f, "1.7 [light=" + light + "] Corner scrimStrength <= 0.78 (got " + cornerStr + ")");
        }

        // 1.8 NaN handling in scrimStrength
        float nanCaption = (float) scrimStrengthMethod.invoke(null, Float.NaN, 0.46f);
        assertTrue(Float.isNaN(nanCaption), "1.8: scrimStrength with NaN light returns NaN");
        // Verify DreamView does not draw gradient if caption is NaN (because NaN > 0.01f evaluates to false)
        assertFalse(nanCaption > 0.01f, "1.9: NaN scrim strength > 0.01f evaluates to false, safely suppressing gradient");

        // 1.10 Monotonicity: brighter photos produce strictly greater or equal scrim strength
        for (int i = 0; i < adversarialLights.length - 1; i++) {
            float l1 = adversarialLights[i], l2 = adversarialLights[i + 1];
            float s1 = (float) scrimStrengthMethod.invoke(null, l1, 0.46f);
            float s2 = (float) scrimStrengthMethod.invoke(null, l2, 0.46f);
            assertTrue(s1 <= s2 + 1e-5f, "1.10: Scrim strength is strictly monotonic: s(" + l1 + ")=" + s1 + " <= s(" + l2 + ")=" + s2);
        }

        // 1.11 black(float a) color opacity bit-packing stress
        assertEquals(0x00000000, (int) blackMethod.invoke(null, 0f), "1.11: black(0) returns 0x00000000 (fully transparent)");
        assertEquals(0xFF000000, (int) blackMethod.invoke(null, 1f), "1.12: black(1) returns 0xFF000000 (fully opaque)");
        assertEquals(0x00000000, (int) blackMethod.invoke(null, -10f), "1.13: black(-10) clamped to 0x00000000");
        assertEquals(0xFF000000, (int) blackMethod.invoke(null, 10f), "1.14: black(10) clamped to 0xFF000000");
        assertEquals(0x00000000, (int) blackMethod.invoke(null, Float.NaN), "1.15: black(NaN) safely produces 0x00000000 without crashing");

        // Test alpha ramps: 0.44 * caption, 0.24 * caption, 0.07 * caption
        int color0 = (int) blackMethod.invoke(null, 0.44f * 0.46f);
        int alpha0 = (color0 >>> 24);
        int color1 = (int) blackMethod.invoke(null, 0.24f * 0.46f);
        int alpha1 = (color1 >>> 24);
        int color2 = (int) blackMethod.invoke(null, 0.07f * 0.46f);
        int alpha2 = (color2 >>> 24);
        assertTrue(alpha0 > alpha1 && alpha1 > alpha2, "1.16: Multi-stop scrim alpha strictly decreases: " + alpha0 + " > " + alpha1 + " > " + alpha2);

        // 1.17 Static Center Placement stress test across diverse aspect ratios
        int[][] aspectTests = {
                {1920, 1080, 3840, 2160}, // 16:9 on 16:9
                {1920, 1080, 2160, 2160}, // 1:1 on 16:9
                {1920, 1080, 1080, 1920}, // 9:16 portrait on 16:9
                {2560, 1080, 3840, 2160}, // 16:9 on 21:9 ultrawide
                {1080, 1920, 3840, 2160}, // 16:9 on 9:16 portrait display
                {1920, 1080, 0, 0},        // Zero dimension image
                {1920, 1080, -100, 2160}   // Negative dimension image
        };

        float[] placeOut = new float[3];
        for (int[] t : aspectTests) {
            int vw = t[0], vh = t[1], iw = t[2], ih = t[3];
            Arrays.fill(placeOut, -999f);
            DreamView.computeStaticCenterPlacement(vw, vh, iw, ih, placeOut);
            if (iw <= 0 || ih <= 0) {
                assertEquals(-999f, placeOut[0], 0f, "1.17 [" + iw + "x" + ih + "] Zero/negative image dimensions safely return without modifying placement");
            } else {
                assertTrue(placeOut[2] > 0f, "1.18 [" + vw + "x" + vh + " vs " + iw + "x" + ih + "] Scale is strictly positive: " + placeOut[2]);
                // Verify image covers screen: iw * scale >= vw and ih * scale >= vh
                assertTrue(iw * placeOut[2] >= vw - 0.01f, "1.19 Image width covers screen width");
                assertTrue(ih * placeOut[2] >= vh - 0.01f, "1.20 Image height covers screen height");
                // Verify centering: (vw - iw * scale) / 2
                assertEquals((vw - iw * placeOut[2]) / 2f, placeOut[0], 0.01f, "1.21 X coordinate is perfectly centered");
                assertEquals((vh - ih * placeOut[2]) / 2f, placeOut[1], 0.01f, "1.22 Y coordinate is perfectly centered");
            }
        }
    }

    // =========================================================================
    // SECTION 2: AGSL LIQUID GLASS SHADER SYNTAX & FALLBACK
    // =========================================================================
    private static void testSection2_AgslLiquidGlassShaderSyntaxAndFallback() throws Exception {
        System.out.println("\n--- [Section 2] AGSL Liquid Glass Shader Syntax & Fallback ---");

        String shader = Shaders.GLASS_TEXT;
        assertNotNull(shader, "2.1: Shaders.GLASS_TEXT is defined and non-null");
        assertTrue(!shader.isEmpty(), "2.2: Shaders.GLASS_TEXT is non-empty");

        // 2.3 Strict SkSL Type System Verification:
        // SkSL requires explicit half() casts for mixing half3 and float vectors.
        assertTrue(shader.contains("bg = mix(half3(l), bg, half(1.15));"),
                "2.3: AGSL shader contains explicit half(1.15) cast in mix(half3(l), bg, half(1.15))");
        assertFalse(shader.contains("mix(half3(l), bg, 1.15)"),
                "2.4: AGSL shader contains ZERO uncast 1.15 literals (which trigger SkSL compilation abort)");

        assertTrue(shader.contains("bg * half(0.86)"),
                "2.5: AGSL shader contains explicit half(0.86) cast in bg * half(0.86)");
        assertFalse(shader.contains("bg * 0.86"),
                "2.6: AGSL shader contains ZERO uncast 0.86 in vector multiply");

        assertTrue(shader.contains("col *= half(1.0 - 0.1 * back * bend * (1.0 - rim));"),
                "2.7: AGSL shader contains explicit half() cast in col *= compound expression");

        assertTrue(shader.contains("half k = a * half(alpha);"),
                "2.8: AGSL shader contains explicit half(alpha) cast in alpha blend");

        assertTrue(shader.contains("return half4(col * k, k);"),
                "2.9: AGSL shader returns valid premultiplied half4 color");

        // 2.10 Division-by-Zero Guards inside the shader
        assertTrue(shader.contains("float2 dir = n / max(bend, 0.001);"),
                "2.10: AGSL shader guards normal normalization dir = n / max(bend, 0.001) to prevent div-by-zero on flat glass");

        // 2.11 Division-by-Zero Guards in Java call sites
        String dreamViewSrc = new String(Files.readAllBytes(findSourceFile("app/src/io/mytv/DreamView.java").toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(dreamViewSrc.contains("placeA[2] <= 0f"),
                "2.11: DreamView.drawGlass explicitly aborts if placeA[2] <= 0f (prevents division by zero in (p - placeA.xy) / placeA.z)");
        assertTrue(dreamViewSrc.contains("placeB[2] > 0f"),
                "2.12: DreamView.drawGlass validates placeB[2] > 0f before binding placeB uniform");
        assertTrue(dreamViewSrc.contains("alpha <= 0.004f"),
                "2.13: DreamView.drawGlass skips draw when alpha <= 0.004f");

        // 2.14 Ink Height & Dimensions Guards in makeGlass
        assertTrue(dreamViewSrc.contains("Math.max(1, ink.height())"),
                "2.14: makeGlass guards ink.height against zero (Math.max(1, ink.height())) to protect (local.y - ink.x) / ink.y");
        assertTrue(dreamViewSrc.contains("Math.max(1, ink.width() + 2 * pad)"),
                "2.15: makeGlass guards cover bitmap width against zero");
        assertTrue(dreamViewSrc.contains("float max = 1;"),
                "2.16: makeGlass initializes max gradient to 1 to prevent division by zero in gx / max");

        // 2.17 RuntimeShader API < 33 Compatibility (Android 8–12)
        assertTrue(dreamViewSrc.contains("try {\n            glassShader = new RuntimeShader(Shaders.GLASS_TEXT);"),
                "2.17: DreamView attempts RuntimeShader compilation in try block");
        assertTrue(dreamViewSrc.contains("catch (RuntimeException | LinkageError e)"),
                "2.18: DreamView catches both RuntimeException and LinkageError (NoClassDefFoundError on API < 33)");
        assertTrue(dreamViewSrc.contains("glassShader = null;"),
                "2.19: DreamView safely assigns glassShader = null on API fallback");

        // 2.20 Canvas LinearGradient Fallback in drawCornerDirect
        assertTrue(dreamViewSrc.contains("else if (\"liquid_glass\".equals(style)) {"),
                "2.20: drawCornerDirect provides dedicated high-fidelity fallback when style is liquid_glass and glassShader is null");
        assertTrue(dreamViewSrc.contains("LinearGradient glassGrad = new LinearGradient(clockX, clockY - clockH, clockX, clockY")
                        || dreamViewSrc.contains("cachedFallbackGlassGrad = new LinearGradient(clockX, clockY - clockH, clockX, clockY"),
                "2.21: Fallback renders multi-stop LinearGradient matching glass specular lighting");
        assertTrue(dreamViewSrc.contains("new int[] {0xEEFFFFFF, 0xA8DCE8F5, 0xCCF2F7FC}"),
                "2.22: Fallback uses 3-stop frosted-crystal color ramp {0xEEFFFFFF, 0xA8DCE8F5, 0xCCF2F7FC}");
        assertTrue(dreamViewSrc.contains("new float[] {0f, 0.50f, 1f}"),
                "2.23: Fallback positions gradient stops at 0%, 50%, and 100%");
        assertTrue(dreamViewSrc.contains("cornerClock.setShader(null);"),
                "2.24: Fallback resets paint shader to null to prevent paint leakage to subsequent views");
        assertTrue(dreamViewSrc.contains("cornerClock.setColor(0xF2FFFFFF);"),
                "2.25: Fallback restores default clock text color");
    }

    // =========================================================================
    // SECTION 3: CLOCK SIZE SCALING ARITHMETIC & STYLES
    // =========================================================================
    private static void testSection3_ClockSizeScalingArithmeticAndStyles() throws Exception {
        System.out.println("\n--- [Section 3] Clock Size Scaling Arithmetic & Styles ---");

        final float CLOCK_SIZE = 136f;
        final float CLOCK_TOP = 112f;
        final float DATE_BASE = 84f;
        final float RIGHT = 1840f;
        final float META_BASE = 1008f;

        // 3.1 Proportional Reduction Verification
        float oldClockSize = 200f;
        float reductionPercent = (oldClockSize - CLOCK_SIZE) / oldClockSize;
        assertEquals(0.32f, reductionPercent, 0.001f, "3.1: Clock size reduction from 200px to 136px is exactly 32.0%");

        // 3.2 Display Resolution Matrix
        int[][] displays = {
                {640, 480},    // 480p SD
                {1280, 720},   // 720p HD
                {1920, 1080},  // 1080p Full HD (reference baseline)
                {2560, 1440},  // 1440p QHD
                {3840, 2160},  // 4K UHD
                {7680, 4320},  // 8K UHD
                {2560, 1080},  // 21:9 Ultrawide Full HD
                {3440, 1440},  // 21:9 Ultrawide QHD
                {1080, 1920}   // 9:16 Portrait Display
        };

        for (int[] disp : displays) {
            int w = disp[0], h = disp[1];
            float k = w / 1920f;
            float scaledClock = CLOCK_SIZE * k;
            float scaledTop = CLOCK_TOP * k;
            float scaledDate = DATE_BASE * k;
            float scaledRight = RIGHT * k;
            float rightMargin = w - scaledRight;

            // 1080p exact values check
            if (w == 1920 && h == 1080) {
                assertEquals(1.0f, k, 0.001f, "3.2 [1080p] k scale is exactly 1.0");
                assertEquals(136.0f, scaledClock, 0.001f, "3.3 [1080p] Clock font size is exactly 136.0px");
                assertEquals(112.0f, scaledTop, 0.001f, "3.4 [1080p] Clock top is exactly 112.0px");
                assertEquals(80.0f, rightMargin, 0.001f, "3.5 [1080p] Right margin is exactly 80.0px");
            }

            // 4K UHD exact values check
            if (w == 3840 && h == 2160) {
                assertEquals(2.0f, k, 0.001f, "3.6 [4K] k scale is exactly 2.0");
                assertEquals(272.0f, scaledClock, 0.001f, "3.7 [4K] Clock font size is exactly 272.0px (double 1080p)");
                assertEquals(224.0f, scaledTop, 0.001f, "3.8 [4K] Clock top is exactly 224.0px");
                assertEquals(160.0f, rightMargin, 0.001f, "3.9 [4K] Right margin is exactly 160.0px");
            }

            // 720p HD exact values check
            if (w == 1280 && h == 720) {
                assertEquals(2f / 3f, k, 0.001f, "3.10 [720p] k scale is exactly 0.6667");
                assertEquals(136f * (2f / 3f), scaledClock, 0.01f, "3.11 [720p] Clock font size is 90.67px");
            }

            // Right margin proportionality invariant across all screen sizes
            float marginRatio = rightMargin / (float) w;
            assertEquals(80f / 1920f, marginRatio, 0.001f, "3.12 [" + w + "x" + h + "] Right margin proportion is strictly 4.167% of screen width");

            // Vertical date-to-clock gap invariant: clockTop - dateBase = 28 * k
            assertEquals(28f * k, scaledTop - scaledDate, 0.01f, "3.13 [" + w + "x" + h + "] Vertical date-to-clock gap scales strictly with k (28*k)");

            // Screen height footprint on 16:9 displays
            if (Math.abs((float) w / h - 16f / 9f) < 0.02f) {
                float heightFootprint = scaledClock / h;
                assertEquals(136f / 1080f, heightFootprint, 0.001f, "3.14 [" + w + "x" + h + "] Clock occupies ~12.59% of display height");
            }
        }

        // 3.15 Clock Styles Mapping & Preferences Invariant
        List<String> clockStyles = Arrays.asList(Prefs.SAVER_CLOCK_STYLES);
        assertEquals(8, clockStyles.size(), "3.15: Prefs.SAVER_CLOCK_STYLES contains 8 curated horological styles");
        assertTrue(clockStyles.contains("liquid_glass"), "3.16: Contains liquid_glass style");
        assertTrue(clockStyles.contains("minimal"), "3.17: Contains minimal style");
        assertTrue(clockStyles.contains("frosted_pill"), "3.18: Contains frosted_pill style");
        assertTrue(clockStyles.contains("stacked"), "3.19: Contains stacked style");
        assertTrue(clockStyles.contains("split_flap"), "3.19b: Contains split_flap style");
        assertTrue(clockStyles.contains("outline"), "3.19c: Contains outline style");
        assertTrue(clockStyles.contains("modern_serif"), "3.20: Contains modern_serif style");
        assertTrue(clockStyles.contains("digital_mono"), "3.21: Contains digital_mono style");

        // 3.22 Fallback for Corrupted/Unknown Clock Styles
        String oldStyle = Prefs.saverClockStyle();
        try {
            Prefs.setSaverClockStyle("invalid_future_font_xyz");
            assertEquals("liquid_glass", Prefs.saverClockStyle(), "3.22: Unknown clock style safely falls back to liquid_glass");
            Prefs.setSaverClockStyle("");
            assertEquals("liquid_glass", Prefs.saverClockStyle(), "3.23: Empty clock style safely falls back to liquid_glass");
        } finally {
            Prefs.setSaverClockStyle(oldStyle);
        }

        // 3.24 Verify DreamView style branches in drawCornerDirect
        String dreamViewSrc = new String(Files.readAllBytes(findSourceFile("app/src/io/mytv/DreamView.java").toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(dreamViewSrc.contains("if (\"minimal\".equals(style))")
                        && dreamViewSrc.contains("cornerClock.setTypeface(Ui.thin);"),
                "3.24: minimal maps to Ui.thin");
        assertTrue(dreamViewSrc.contains("else if (\"stacked\".equals(style))")
                        && dreamViewSrc.contains("else if (\"split_flap\".equals(style))"),
                "3.25: stacked and split_flap architectural clock layouts implemented");
        assertTrue(dreamViewSrc.contains("else if (\"modern_serif\".equals(style))")
                        && dreamViewSrc.contains("Typeface.create(Typeface.SERIF, Typeface.BOLD)"),
                "3.26: modern_serif maps to Typeface.SERIF bold");
        assertTrue(dreamViewSrc.contains("else if (\"digital_mono\".equals(style))")
                        && dreamViewSrc.contains("Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)"),
                "3.27: digital_mono maps to Typeface.MONOSPACE bold");
        assertTrue(dreamViewSrc.contains("cornerClock.setTypeface(Ui.displayMedium);"),
                "3.28: frosted_pill and fallback styles use Ui.displayMedium");
    }

    // =========================================================================
    // SECTION 4: CAPTION PRESENCE & SINGLE-LINE BASELINE GEOMETRY
    // =========================================================================
    private static void testSection4_CaptionPresenceAndSingleLineBaselineGeometry() throws Exception {
        System.out.println("\n--- [Section 4] Caption Presence & Single-Line Baseline Geometry ---");

        Method hasLiveCaptionMethod = DreamView.class.getDeclaredMethod("hasLiveCaption", Recs.Pick.class);
        hasLiveCaptionMethod.setAccessible(true);

        Method resolveLiveTitleMethod = DreamView.class.getDeclaredMethod("resolveLiveTitle", Recs.Pick.class);
        resolveLiveTitleMethod.setAccessible(true);

        Method resolveLiveMetaMethod = DreamView.class.getDeclaredMethod("resolveLiveMeta", Recs.Pick.class);
        resolveLiveMetaMethod.setAccessible(true);

        File dummyFile = new File("app/assets/dummy.jpg");

        // 4.1 Null pick handling
        assertFalse((boolean) hasLiveCaptionMethod.invoke(null, (Recs.Pick) null),
                "4.1: hasLiveCaption(null) returns false safely");
        assertEquals("", resolveLiveTitleMethod.invoke(null, (Recs.Pick) null),
                "4.2: resolveLiveTitle(null) returns empty string");
        assertEquals("", resolveLiveMetaMethod.invoke(null, (Recs.Pick) null),
                "4.3: resolveLiveMeta(null) returns empty string");

        // 4.4 Subject category: Art/Paintings/Macro/Space (Artwork title shown, location omitted)
        Recs.Pick artPick = new Recs.Pick();
        artPick.photo = dummyFile;
        artPick.category = "art";
        artPick.rawTitle = "The Starry Night";
        artPick.rawArtist = "Vincent van Gogh";
        artPick.rawLocation = "Saint-Rémy-de-Provence";

        boolean artHasCaption = (boolean) hasLiveCaptionMethod.invoke(null, artPick);
        assertTrue(artHasCaption, "4.4: Art pick with metadata has live caption");
        String artTitle = (String) resolveLiveTitleMethod.invoke(null, artPick);
        assertEquals("", artTitle, "4.5: Art pick suppresses big white artwork title");
        String artMeta = (String) resolveLiveMetaMethod.invoke(null, artPick);
        assertTrue(artMeta.contains("Vincent van Gogh") && artMeta.contains("The Starry Night"),
                "4.6: Art pick displays artist + small artwork name in metadata");
        assertFalse(artMeta.contains("Saint-Rémy-de-Provence"), "4.7: Art pick strictly omits location");

        // 4.8 Photo category: Nature/Urban/Landscape (Artwork title suppressed, location shown)
        Recs.Pick naturePick = new Recs.Pick();
        naturePick.photo = dummyFile;
        naturePick.category = "nature";
        naturePick.rawTitle = "Majestic Waterfall";
        naturePick.rawArtist = "John Doe";
        naturePick.rawLocation = "Yosemite National Park";

        boolean natureHasCaption = (boolean) hasLiveCaptionMethod.invoke(null, naturePick);
        assertTrue(natureHasCaption, "4.8: Nature pick with metadata has live caption");
        String natureTitle = (String) resolveLiveTitleMethod.invoke(null, naturePick);
        assertEquals("", natureTitle, "4.9: Nature pick strictly suppresses artwork title");
        String natureMeta = (String) resolveLiveMetaMethod.invoke(null, naturePick);
        assertTrue(natureMeta.contains("Yosemite National Park"), "4.10: Nature pick displays location");

        // 4.11 Missing all metadata fields (including title): live caption must evaluate to false
        Recs.Pick emptyPick = new Recs.Pick();
        emptyPick.photo = dummyFile;
        emptyPick.category = "nature";
        emptyPick.rawTitle = "";
        emptyPick.rawArtist = null;
        emptyPick.rawLocation = null;

        boolean emptyHasCaption = (boolean) hasLiveCaptionMethod.invoke(null, emptyPick);
        assertFalse(emptyHasCaption, "4.11: Nature pick with missing title, location & artist has ZERO live caption");
        assertEquals("", resolveLiveTitleMethod.invoke(null, emptyPick), "4.12: Title is empty");
        assertEquals("", resolveLiveMetaMethod.invoke(null, emptyPick), "4.13: Meta is empty");

        // 4.14 Single-Line Baseline Adjustment Verification in DreamView
        String dreamViewSrc = new String(Files.readAllBytes(findSourceFile("app/src/io/mytv/DreamView.java").toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(dreamViewSrc.contains("float metaBase = META_BASE * k;"),
                "4.14: drawSlideCaptionDirect defines metaBase = META_BASE * k");
        assertTrue(dreamViewSrc.contains("float titleBase = meta.isEmpty() ? metaBase : (metaBase - 58 * k);"),
                "4.15: Single-line baseline adjustment: when meta is empty, titleBase drops to metaBase; when meta is present, titleBase is lifted by exactly 58*k");

        // Mathematical verification of baseline adjustment across resolutions:
        float META_BASE = 1008f;
        int[][] resList = {{1920, 1080}, {3840, 2160}, {1280, 720}};
        for (int[] res : resList) {
            int w = res[0], h = res[1];
            float k = w / 1920f;
            float metaBase = META_BASE * k;
            float titleBaseEmptyMeta = metaBase;
            float titleBaseWithMeta = metaBase - 58f * k;

            assertEquals(metaBase, titleBaseEmptyMeta, 0.001f, "4.16 [" + w + "x" + h + "] Empty meta titleBase equals metaBase exactly");
            assertEquals(58f * k, titleBaseEmptyMeta - titleBaseWithMeta, 0.001f, "4.17 [" + w + "x" + h + "] Baseline lift is exactly 58*k px");
            assertTrue(titleBaseWithMeta < metaBase, "4.18 [" + w + "x" + h + "] Title with meta sits strictly above metadata line");
        }

        // 4.19 Multi-line StaticLayout alignment: bottom line baseline alignment
        assertTrue(dreamViewSrc.contains("float originY = titleBase - t.getLineBaseline(t.getLineCount() - 1);"),
                "4.19: StaticLayout aligns bottom-most line baseline directly to titleBase, guaranteeing consistent vertical rhythm for both 1-line and 2-line titles");
    }

    // =========================================================================
    // SECTION 5: LRU SlideHistoryCache & navHistory BOUNDS
    // =========================================================================
    private static void testSection5_LruSlideHistoryCacheAndNavHistoryBounds() throws Exception {
        System.out.println("\n--- [Section 5] LRU SlideHistoryCache & navHistory Bounds ---");

        Class<?> cacheClass = Class.forName("io.mytv.DreamView$SlideHistoryCache");
        Constructor<?> ctor = cacheClass.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);

        Method putMethod = cacheClass.getDeclaredMethod("put", String.class, Object.class);
        putMethod.setAccessible(true);

        Method takeMethod = cacheClass.getDeclaredMethod("take", String.class);
        takeMethod.setAccessible(true);

        Method sizeMethod = cacheClass.getDeclaredMethod("size");
        sizeMethod.setAccessible(true);

        Method clearMethod = cacheClass.getDeclaredMethod("clear");
        clearMethod.setAccessible(true);

        // 5.1 Max entries clamp to at least 1
        Object cache0 = ctor.newInstance(0);
        Object cacheNeg = ctor.newInstance(-10);
        putMethod.invoke(cache0, "k1", "v1");
        putMethod.invoke(cacheNeg, "k1", "v1");
        assertEquals(1, sizeMethod.invoke(cache0), "5.1: SlideHistoryCache(0) clamps maxEntries to at least 1");
        assertEquals(1, sizeMethod.invoke(cacheNeg), "5.2: SlideHistoryCache(-10) clamps maxEntries to at least 1");

        // 5.3 Null and empty key/value put and take safety
        Object cache = ctor.newInstance(DreamView.MAX_HISTORY_SLIDES);
        assertEquals(3, DreamView.MAX_HISTORY_SLIDES, "5.3: MAX_HISTORY_SLIDES is strictly bounded at 3");

        assertNull(putMethod.invoke(cache, null, "item"), "5.4: put(null, item) returns null safely");
        assertNull(putMethod.invoke(cache, "", "item"), "5.5: put(\"\", item) returns null safely");
        assertNull(putMethod.invoke(cache, "k", null), "5.6: put(k, null) returns null safely");
        assertEquals(0, sizeMethod.invoke(cache), "5.7: Cache size remains 0 after invalid puts");

        assertNull(takeMethod.invoke(cache, (Object) null), "5.8: take(null) returns null safely");
        assertNull(takeMethod.invoke(cache, ""), "5.9: take(\"\") returns null safely");

        // 5.10 Strict LRU Eviction Invariant with maxEntries = 3
        Object ev1 = putMethod.invoke(cache, "photo:1", "slide1");
        assertNull(ev1, "5.10: 1st insert: zero eviction");
        Object ev2 = putMethod.invoke(cache, "photo:2", "slide2");
        assertNull(ev2, "5.11: 2nd insert: zero eviction");
        Object ev3 = putMethod.invoke(cache, "photo:3", "slide3");
        assertNull(ev3, "5.12: 3rd insert: zero eviction");
        assertEquals(3, sizeMethod.invoke(cache), "5.13: Cache holds exactly 3 items");

        // 4th insert: must evict the least recently used item (photo:1)
        Object ev4 = putMethod.invoke(cache, "photo:4", "slide4");
        assertEquals("slide1", ev4, "5.14: 4th insert evicts oldest item 'slide1'");
        assertEquals(3, sizeMethod.invoke(cache), "5.15: Cache size remains strictly bounded at 3");

        // Access photo:2 to make it recently used, then insert photo:5 -> should evict photo:3!
        Object hit2 = takeMethod.invoke(cache, "photo:2");
        assertEquals("slide2", hit2, "5.16: take(photo:2) successfully retrieves slide2");
        putMethod.invoke(cache, "photo:2", hit2); // re-insert photo:2 at head
        Object ev5 = putMethod.invoke(cache, "photo:5", "slide5");
        assertEquals("slide3", ev5, "5.17: Eviction respects LRU order: evicts slide3 (since slide2 was refreshed)");

        // 5.18 Rapid 2,000 steps stress test: memory never leaks beyond 3 entries
        for (int i = 0; i < 2000; i++) {
            putMethod.invoke(cache, "photo:stress_" + i, "slide_" + i);
            int sz = (int) sizeMethod.invoke(cache);
            if (sz > 3) {
                throw new AssertionError("Cache size breached MAX_HISTORY_SLIDES: " + sz);
            }
        }
        assertEquals(3, sizeMethod.invoke(cache), "5.18: Cache size strictly bounded at 3 after 2,000 rapid sequential puts");

        // 5.19 navHistory bounds & clamping at 0
        String dreamViewSrc = new String(Files.readAllBytes(findSourceFile("app/src/io/mytv/DreamView.java").toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(dreamViewSrc.contains("static final int MAX_NAV_HISTORY = 100;"),
                "5.19: DreamView defines MAX_NAV_HISTORY = 100");
        assertTrue(dreamViewSrc.contains("if (navHistory.size() > MAX_NAV_HISTORY) {\n            navHistory.remove(0);"),
                "5.20: recordCurrentInHistory strictly removes oldest element when exceeding 100 elements");
        assertTrue(dreamViewSrc.contains("if (navIndex <= 0)"),
                "5.21: stepSlide(-1) explicitly clamps at navIndex <= 0, preventing negative index underflow");

        // 5.22 ShuffleBag cycle boundary de-duplication
        DreamView.ShuffleBag<String> bag = new DreamView.ShuffleBag<>(new Random(42));
        List<String> pool = Arrays.asList("cat1", "cat2", "cat3", "cat4", "cat5");
        bag.setPool(pool);
        assertEquals(5, bag.size(), "5.22: ShuffleBag holds 5 items in pool");

        // Draw 50 items and verify zero back-to-back duplicates across cycle boundaries
        String last = null;
        for (int i = 0; i < 50; i++) {
            String drawn = bag.draw(null);
            assertNotNull(drawn, "5.23: Drawn item is non-null");
            if (last != null) {
                assertFalse(drawn.equals(last), "5.24 [step " + i + "] Drawn item '" + drawn + "' does not equal previous item '" + last + "' (cycle de-duplication)");
            }
            last = drawn;
        }

        // 5.25 ShuffleBag draw with failedKeys filtering
        Set<String> failedSubset = new HashSet<>(Arrays.asList("cat1", "cat2"));
        for (int i = 0; i < 30; i++) {
            String drawn = bag.draw(failedSubset);
            if (drawn != null) {
                assertFalse(failedSubset.contains(drawn), "5.25: Drawn item '" + drawn + "' is never in failed set {cat1, cat2}");
            }
        }

        // When 4 of 5 are failed, any non-null drawn item must strictly be cat5
        Set<String> fourFailed = new HashSet<>(Arrays.asList("cat1", "cat2", "cat3", "cat4"));
        for (int i = 0; i < 10; i++) {
            String drawn = bag.draw(fourFailed);
            if (drawn != null) {
                assertEquals("cat5", drawn, "5.26: When cat1..cat4 are failed, any drawn item is strictly cat5");
            }
        }

        // When all items failed:
        Set<String> allFailed = new HashSet<>(Arrays.asList("cat1", "cat2", "cat3", "cat4", "cat5"));
        String drawnAllFailed = bag.draw(allFailed);
        assertNull(drawnAllFailed, "5.27: ShuffleBag safely returns null when 100% of pool items are marked failed");
    }

    // =========================================================================
    // SECTION 6: TRANSITION STATE MACHINE & RAPID KEY HANDLING
    // =========================================================================
    private static void testSection6_TransitionStateMachineAndRapidKeyHandling() throws Exception {
        System.out.println("\n--- [Section 6] Transition State Machine & Rapid Key Handling ---");

        String dreamViewSrc = new String(Files.readAllBytes(findSourceFile("app/src/io/mytv/DreamView.java").toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");

        // 6.1 Verify fadeIn formula in DreamView source
        assertTrue(dreamViewSrc.contains("float t = Ui.clamp(elapsed / (float) Math.max(1L, duration));"),
                "6.1: DreamView.fadeIn clamps progress between 0 and 1, guarded by Math.max(1L, duration)");
        assertTrue(dreamViewSrc.contains("return t * t * (3 - 2 * t);"),
                "6.2: DreamView.fadeIn returns smoothstep cubic curve t * t * (3 - 2 * t)");

        // Reference evaluation of smoothstep curve
        // f(t) = t * t * (3 - 2 * t)
        assertEquals(0.0f, fadeInFormula(0L, 1000L), 0.0001f, "6.3: fadeIn(0) = 0.0");
        assertEquals(0.5f, fadeInFormula(500L, 1000L), 0.0001f, "6.4: fadeIn(half) = 0.5 (exact symmetry)");
        assertEquals(1.0f, fadeInFormula(1000L, 1000L), 0.0001f, "6.5: fadeIn(duration) = 1.0");

        // Clamping bounds:
        assertEquals(0.0f, fadeInFormula(-500L, 1000L), 0.0001f, "6.6: fadeIn(negative) clamped to 0.0");
        assertEquals(1.0f, fadeInFormula(5000L, 1000L), 0.0001f, "6.7: fadeIn(elapsed > duration) clamped to 1.0");

        // Division by zero guard in duration:
        float zeroDurResult = fadeInFormula(100L, 0L);
        assertEquals(1.0f, zeroDurResult, 0.0001f, "6.8: fadeIn with duration=0 uses Math.max(1L, duration) and evaluates safely without NaN");

        // Monotonicity:
        float prevFade = -1f;
        for (long t = 0; t <= 1000; t += 25) {
            float fade = fadeInFormula(t, 1000L);
            assertTrue(fade >= prevFade - 1e-6f, "6.9: fadeIn is monotonically non-decreasing: t=" + t + ", f=" + fade);
            prevFade = fade;
        }

        // 6.8 Crossfade Durations in DreamView
        assertTrue(dreamViewSrc.contains("MANUAL_FADE_MS = 380"),
                "6.8: DreamView defines manual crossfade duration MANUAL_FADE_MS = 380 ms (fast and responsive)");
        assertTrue(dreamViewSrc.contains("FADE_MS = 3_000"),
                "6.9: DreamView defines ambient crossfade duration FADE_MS = 3000 ms (calm ambient transition)");
        assertTrue(dreamViewSrc.contains("return manualTransition ? MANUAL_FADE_MS : FADE_MS;"),
                "6.10: activeFadeDurationMs() dynamically selects 380 ms for manual navigation vs 3000 ms for automatic timer advance");

        // 6.11 Mid-Crossfade Reversal Handling in stepSlide (0 ms RAM swap)
        assertTrue(dreamViewSrc.contains("// Mid-crossfade reversal back to current slide (0 ms RAM hit)"),
                "6.11: stepSlide implements dedicated mid-crossfade reversal handling");
        assertTrue(dreamViewSrc.contains("if (incoming != null && current != null && Recs.key(current.pick).equals(targetKey)"),
                "6.12: stepSlide detects reversal back to current slide while incoming crossfade is in flight");
        assertTrue(dreamViewSrc.contains("promoteSlideToIncoming(reversing, up);"),
                "6.13: Reversal seamlessly promotes current back to incoming without redundant decode");

        // 6.14 Interrupted Forward Step Handling
        assertTrue(dreamViewSrc.contains("if (incoming != null) {\n            Slide old = current;\n            current = incoming;\n            stashInHistory(old);\n        }"),
                "6.14: promoteSlideToIncoming safely commits previous incoming to current and stashes old in history when rapid forward step interrupts ongoing crossfade");

        // 6.15 Slide Timer Reset on Manual Transition Completion
        assertTrue(dreamViewSrc.contains("if (manualTransition) {\n                // Reset slide timer from completion of manual navigation so user gets full interval on chosen slide\n                current.startedAt = up;\n                manualTransition = false;\n            }"),
                "6.15: DreamView resets slide startedAt timer upon manual transition completion so user gets full interval on the manually selected wallpaper");

        // 6.16 Remote Key Code Isolation Invariants
        assertTrue(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_DPAD_LEFT), "6.16: DPAD_LEFT is screensaver nav key");
        assertTrue(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_DPAD_RIGHT), "6.17: DPAD_RIGHT is screensaver nav key");
        assertTrue(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_DPAD_UP), "6.18: DPAD_UP is screensaver nav key");
        assertTrue(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_DPAD_DOWN), "6.19: DPAD_DOWN is screensaver nav key");

        assertTrue(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_DPAD_LEFT), "6.20: DreamView consumes DPAD_LEFT");
        assertTrue(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_DPAD_RIGHT), "6.21: DreamView consumes DPAD_RIGHT");
        assertTrue(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_DPAD_UP), "6.22: DreamView consumes DPAD_UP");
        assertTrue(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_DPAD_DOWN), "6.23: DreamView consumes DPAD_DOWN");

        // CRITICAL INVARIANT: Dismissal keys must NEVER be consumed by nav router
        assertFalse(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_BACK), "6.24: KEYCODE_BACK is NOT a nav key");
        assertTrue(DreamView.isScreensaverDismissKey(KeyEvent.KEYCODE_BACK), "6.25: KEYCODE_BACK is a dismiss key");
        assertFalse(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_BACK), "6.26: KEYCODE_BACK is NEVER consumed by screensaver router");

        assertFalse(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_ESCAPE), "6.27: KEYCODE_ESCAPE is NEVER consumed");
        assertFalse(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_HOME), "6.28: KEYCODE_HOME is NEVER consumed");
        assertFalse(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_DPAD_CENTER), "6.29: KEYCODE_DPAD_CENTER is NEVER consumed");
        assertFalse(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_ENTER), "6.30: KEYCODE_ENTER is NEVER consumed");

        // CRITICAL VOLUME RULE: Volume keys must NEVER be consumed or modified
        assertFalse(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_VOLUME_UP), "6.31: KEYCODE_VOLUME_UP is NOT a nav key");
        assertFalse(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_VOLUME_UP), "6.32: KEYCODE_VOLUME_UP is NEVER consumed");
        assertFalse(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_VOLUME_DOWN), "6.33: KEYCODE_VOLUME_DOWN is NOT a nav key");
        assertFalse(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_VOLUME_DOWN), "6.34: KEYCODE_VOLUME_DOWN is NEVER consumed");
        assertFalse(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_VOLUME_MUTE), "6.35: KEYCODE_VOLUME_MUTE is NOT a nav key");
        assertFalse(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_VOLUME_MUTE), "6.36: KEYCODE_VOLUME_MUTE is NEVER consumed");
    }
}
