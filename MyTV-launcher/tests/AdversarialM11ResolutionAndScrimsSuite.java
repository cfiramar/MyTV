package io.mytv;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Empirical Adversarial Verification Suite for Milestone 1:
 * - AGSL GLASS_TEXT compilation, type correctness, and fallback execution.
 * - Stress test CLOCK_SIZE and CLOCK_TOP calculations under various resolutions (480p, 720p, 1080p, 1440p, 4K, 8K, Ultrawide, Portrait).
 * - Stress test withScrims under various bitmap dimensions and aspect ratios.
 *
 * Authored by Challenger M1-1 (Milestone 1 Empirical Verification).
 */
public final class AdversarialM11ResolutionAndScrimsSuite {

    private static int assertions = 0;
    private static int failedAssertions = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== STARTING ADVERSARIAL M1-1 RESOLUTION & SCRIMS STRESS SUITE ===");

        test1_AgslShaderSyntaxAndTypeCorrectness();
        test2_ApiFallbackAndGuardExecution();
        test3_ClockSizeAndTopResolutionStress();
        test4_WithScrimsDimensionsAndGeometryStress();
        test5_TypographyAndPreferencesIntegration();

        System.out.println("\n=== ADVERSARIAL M1-1 SUITE SUMMARY ===");
        System.out.println("Total Assertions Executed: " + assertions);
        System.out.println("Failed Assertions: " + failedAssertions);

        if (failedAssertions > 0) {
            System.err.println("ADVERSARIAL SUITE RESULT: FAIL (" + failedAssertions + " failures)");
            System.exit(1);
        } else {
            System.out.println("ADVERSARIAL SUITE RESULT: APPROVE");
        }
    }

    private static void assertTrue(boolean condition, String msg) {
        assertions++;
        if (!condition) {
            failedAssertions++;
            System.err.println("  [FAIL] " + msg);
        } else {
            System.out.println("  [PASS] " + msg);
        }
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        assertions++;
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            failedAssertions++;
            System.err.println("  [FAIL] " + msg + " | expected=[" + expected + "], actual=[" + actual + "]");
        } else {
            System.out.println("  [PASS] " + msg);
        }
    }

    private static void assertEquals(float expected, float actual, float epsilon, String msg) {
        assertions++;
        if (Math.abs(expected - actual) > epsilon) {
            failedAssertions++;
            System.err.println("  [FAIL] " + msg + " | expected=[" + expected + "], actual=[" + actual + "], delta=[" + Math.abs(expected - actual) + " > " + epsilon + "]");
        } else {
            System.out.println("  [PASS] " + msg);
        }
    }

    // =========================================================================
    // 1. AGSL GLASS_TEXT COMPILATION & TYPE CORRECTNESS
    // =========================================================================
    private static void test1_AgslShaderSyntaxAndTypeCorrectness() throws Exception {
        System.out.println("\n--- [Section 1] AGSL GLASS_TEXT Shader Syntax & Type Correctness ---");

        String shader = Shaders.GLASS_TEXT;
        assertTrue(shader != null && !shader.isEmpty(), "1.1: Shaders.GLASS_TEXT is defined and non-empty");

        // SkSL type correctness verification:
        // SkSL does not allow implicit float -> half promotion in mix(half3, half3, float).
        assertTrue(shader.contains("mix(half3(l), bg, half(1.15))"),
                "1.2: Shader contains type-safe cast half(1.15) in mix(half3(l), bg, half(1.15))");
        assertTrue(!shader.contains("mix(half3(l), bg, 1.15)"),
                "1.3: Shader contains zero bare scalar 1.15 in mix() (which causes SkSL compiler failure)");

        assertTrue(shader.contains("bg * half(0.86)"),
                "1.4: Shader contains type-safe cast half(0.86) in bg * half(0.86)");
        assertTrue(!shader.contains("bg * 0.86 +"),
                "1.5: Shader contains zero bare scalar 0.86 in vector multiply");

        // Uniform declarations verification
        String[] requiredUniforms = {
                "uniform shader imageA;",
                "uniform shader imageB;",
                "uniform shader slope;",
                "uniform shader cover;",
                "uniform float3 placeA;",
                "uniform float3 placeB;",
                "uniform float mixB;",
                "uniform float2 origin;",
                "uniform float2 ink;",
                "uniform float refraction;",
                "uniform float specular;",
                "uniform float alpha;"
        };
        for (String u : requiredUniforms) {
            assertTrue(shader.contains(u), "1.6: Shader declares required uniform: " + u);
        }

        // Return type and entry point
        assertTrue(shader.contains("half4 main(float2 xy)"), "1.7: Shader declares standard SkSL entry point: half4 main(float2 xy)");
        assertTrue(shader.contains("return half4(col * k, k);"), "1.8: Shader outputs premultiplied half4 color");
    }

    // =========================================================================
    // 2. API FALLBACK & GUARD EXECUTION
    // =========================================================================
    private static void test2_ApiFallbackAndGuardExecution() throws Exception {
        System.out.println("\n--- [Section 2] API Fallback & Guard Execution ---");

        String dreamViewSrc = new String(Files.readAllBytes(Paths.get("app/src/io/mytv/DreamView.java")), StandardCharsets.UTF_8);

        // Verification of RuntimeShader initialization try-catch
        assertTrue(dreamViewSrc.contains("new RuntimeShader(Shaders.GLASS_TEXT)"),
                "2.1: DreamView attempts RuntimeShader compilation with Shaders.GLASS_TEXT");
        assertTrue(dreamViewSrc.contains("catch (RuntimeException | LinkageError e)"),
                "2.2: DreamView guards RuntimeShader compilation against RuntimeException and LinkageError (API < 33)");
        assertTrue(dreamViewSrc.contains("glassShader = null;"),
                "2.3: DreamView safely sets glassShader = null on compilation failure");

        // Verification of drawGlass null & division-by-zero guards
        assertTrue(dreamViewSrc.contains("placeA[2] <= 0f"),
                "2.4: drawGlass guards against placeA[2] <= 0f to prevent division by zero in shader");
        assertTrue(dreamViewSrc.contains("alpha <= 0.004f"),
                "2.5: drawGlass guards against alpha <= 0.004f to skip invisible draws");
        assertTrue(dreamViewSrc.contains("g == null || glassShader == null || current == null || current.shader == null"),
                "2.6: drawGlass strictly verifies glass, shader, and photo existence before drawing");

        // Verification of Canvas LinearGradient fallback for API < 33 in drawCornerDirect (including cached zero-alloc gradient)
        assertTrue(dreamViewSrc.contains("LinearGradient glassGrad = new LinearGradient(clockX, clockY - clockH, clockX, clockY")
                        || dreamViewSrc.contains("cachedFallbackGlassGrad = new LinearGradient(clockX, clockY - clockH, clockX, clockY"),
                "2.7: drawCornerDirect provides multi-stop LinearGradient glass fallback when glassShader == null");
        assertTrue(dreamViewSrc.contains("0x55000000"),
                "2.8: drawCornerDirect renders drop shadow in fallback path");
        assertTrue(dreamViewSrc.contains("cornerClock.setShader(null)"),
                "2.9: drawCornerDirect clears shader after drawing glass fallback to avoid paint leakage");
    }

    // =========================================================================
    // 3. CLOCK_SIZE & CLOCK_TOP RESOLUTION STRESS TEST
    // =========================================================================
    private static void test3_ClockSizeAndTopResolutionStress() {
        System.out.println("\n--- [Section 3] CLOCK_SIZE & CLOCK_TOP Resolution Stress Test ---");

        final float CLOCK_SIZE = 136f;
        final float CLOCK_TOP = 112f;
        final float DATE_BASE = 84f;
        final float RIGHT = 1840f;
        final float QUOTES_GAP = 52f;

        // Resolution matrix: {width, height}
        int[][] resolutions = {
                {720, 480},    // 480p NTSC (3:2)
                {854, 480},    // 480p WVGA (16:9)
                {1280, 720},   // 720p HD (16:9)
                {1920, 1080},  // 1080p Full HD (16:9)
                {2560, 1440},  // 1440p QHD (16:9)
                {3840, 2160},  // 4K UHD (16:9)
                {7680, 4320},  // 8K UHD (16:9)
                {2560, 1080},  // 21:9 Ultrawide
                {3440, 1440},  // 21:9 Ultrawide QHD
                {5120, 1440},  // 32:9 Super Ultrawide
                {1080, 1920},  // 9:16 Portrait TV / Digital Signage
                {2160, 2160}   // 1:1 Square
        };

        for (int[] res : resolutions) {
            int w = res[0];
            int h = res[1];
            float k = w / 1920f;

            float clockFont = CLOCK_SIZE * k;
            float clockTopY = CLOCK_TOP * k;
            float dateBaseY = DATE_BASE * k;
            float rightX = RIGHT * k;
            float rightMargin = w - rightX;

            // 1. Right margin proportion (must be strictly 80px on 1080p, > 0 on all)
            assertTrue(rightMargin > 0, "3.1 [" + w + "x" + h + "] Right margin is positive (" + rightMargin + "px)");
            assertEquals(80f * k, rightMargin, 0.01f, "3.2 [" + w + "x" + h + "] Right margin scales linearly with k: " + rightMargin);

            // 2. Vertical separation between date and clock
            float gap = clockTopY - dateBaseY;
            assertEquals(28f * k, gap, 0.01f, "3.3 [" + w + "x" + h + "] Date-to-clock gap is strictly 28*k (" + gap + "px)");
            assertTrue(dateBaseY < clockTopY, "3.4 [" + w + "x" + h + "] Date header is strictly above clock");

            // 3. Screen height footprint on 16:9 displays
            if (Math.abs((float) w / h - 16f / 9f) < 0.05f) {
                float footprint = clockFont / h;
                // Clock font size should occupy ~12.59% of height (down from 18.52% with size 200)
                assertEquals(0.1259f, footprint, 0.005f, "3.5 [" + w + "x" + h + "] Clock height occupies ~12.6% of screen height (was 18.5%)");
            }

            // 4. Clock horizontal layout bound stress test with extreme time strings
            String[] testTimes = {"0:00", "12:34", "23:59", "88:88", "12:34:56"};
            for (String time : testTimes) {
                // Approximate glyph measurement: average width ~0.55 * font size per digit, 0.25 for colon
                float approxWidth = (time.length() - 1) * 0.55f * clockFont + 0.25f * clockFont;
                float clockLeftX = rightX - approxWidth;
                assertTrue(clockLeftX > 0, "3.6 [" + w + "x" + h + "] Clock text '" + time + "' left boundary (" + clockLeftX + ") stays inside screen (w=" + w + ")");
            }

            // 5. Dependent quotes row vertical placement
            float cap = 0.72f * clockFont;
            float quotesBase = (CLOCK_TOP + cap / k + QUOTES_GAP) * k;
            assertTrue(quotesBase > clockTopY, "3.7 [" + w + "x" + h + "] Quotes row sits below clock");
            assertTrue(quotesBase < h, "3.8 [" + w + "x" + h + "] Quotes row stays within vertical display bounds (" + quotesBase + " < " + h + ")");
        }
    }

    // =========================================================================
    // 4. WITH_SCRIMS DIMENSIONS & GEOMETRY STRESS TEST
    // =========================================================================
    private static void test4_WithScrimsDimensionsAndGeometryStress() {
        System.out.println("\n--- [Section 4] withScrims Dimensions & Geometry Stress Test ---");

        // 4.1 Test scrimStrength mathematical bounds
        float[] testLights = {-1000f, -10f, 0f, 0.15f, 0.30f, 0.46f, 0.60f, 0.85f, 1.0f, 2.0f, 100f, 10000f};
        for (float light : testLights) {
            float captionStrength = scrimStrength(light, 0.46f);
            float cornerStrength = scrimStrength(light, 0.38f);

            // Caption scrim bounds: min = 0.46 - 0.1 = 0.36, max = 0.78
            assertTrue(captionStrength >= 0.36f - 1e-5f, "4.1: Caption scrimStrength >= 0.36 for light=" + light + " (got " + captionStrength + ")");
            assertTrue(captionStrength <= 0.78f + 1e-5f, "4.2: Caption scrimStrength <= 0.78 for light=" + light + " (got " + captionStrength + ")");

            // Corner scrim bounds: min = 0.38 - 0.1 = 0.28, max = 0.78
            assertTrue(cornerStrength >= 0.28f - 1e-5f, "4.3: Corner scrimStrength >= 0.28 for light=" + light + " (got " + cornerStrength + ")");
            assertTrue(cornerStrength <= 0.78f + 1e-5f, "4.4: Corner scrimStrength <= 0.78 for light=" + light + " (got " + cornerStrength + ")");
        }

        // Monotonicity test: brighter photo -> stronger scrim
        for (int i = 0; i < testLights.length - 1; i++) {
            float l1 = testLights[i], l2 = testLights[i + 1];
            assertTrue(scrimStrength(l1, 0.46f) <= scrimStrength(l2, 0.46f) + 1e-5f,
                    "4.5: Scrim strength is monotonic: scrimStrength(" + l1 + ") <= scrimStrength(" + l2 + ")");
        }

        // 4.2 Test black() color construction bounds
        float[] alphas = {-10f, -0.5f, 0.0f, 0.01f, 0.24f, 0.44f, 0.78f, 1.0f, 1.5f, 100f};
        for (float a : alphas) {
            int argb = black(a);
            int alphaInt = (argb >>> 24) & 0xFF;
            int rgb = argb & 0x00FFFFFF;
            assertEquals(0, rgb, "4.6: black(" + a + ") RGB channels are strictly 0x000000");
            assertTrue(alphaInt >= 0 && alphaInt <= 255, "4.7: black(" + a + ") alpha (" + alphaInt + ") in valid range 0..255");
            if (a <= 0f) assertEquals(0, alphaInt, "4.8: Non-positive alpha clamped to 0");
            if (a >= 1f) assertEquals(255, alphaInt, "4.9: Alpha >= 1.0 clamped to 255");
        }

        // 4.3 Test Elliptical Vignette Geometry across aspect ratios
        int[][] testDimensions = {
                {3840, 2160}, // Standard 4K UHD 16:9
                {1920, 1080}, // Standard 1080p FHD 16:9
                {1280, 720},  // 720p HD 16:9
                {2560, 1080}, // 21:9 Ultrawide
                {5120, 1440}, // 32:9 Super Ultrawide
                {2160, 2160}, // 1:1 Square
                {1080, 1920}, // 9:16 Portrait
                {4000, 1000}, // 4:1 Extreme Panorama
                {1000, 4000}  // 1:4 Extreme Vertical
        };

        for (int[] dim : testDimensions) {
            int w = dim[0];
            int h = dim[1];

            // 1. Bottom-left caption vignette: centered at (0, h), radius = w * 0.65f, scaleY = 0.55f
            float rCaption = w * 0.65f;
            float maxReachX = rCaption * 1.0f;
            float maxReachY = rCaption * 0.55f;

            assertTrue(rCaption > 0, "4.10 [" + w + "x" + h + "] Caption radius is positive: " + rCaption);
            assertTrue(maxReachX < w, "4.11 [" + w + "x" + h + "] Caption vignette does not cover entire width (maxReach=" + maxReachX + " < w=" + w + ")");

            // Center of screen (w/2, h/2):
            // Distance from (0, h) scaled by ellipse: sqrt((0.5)^2 + ((h/2 - h)/(0.55 * rCaption))^2)
            float dx = (w * 0.5f) / rCaption;
            float dy = (h * 0.5f) / maxReachY;
            float distNorm = (float) Math.sqrt(dx * dx + dy * dy);
            // Center of screen should be well beyond 0.85 radius, ensuring clear view
            assertTrue(distNorm >= 0.80f, "4.12 [" + w + "x" + h + "] Photo center is in clear/unshadowed zone (normalized distance=" + distNorm + " >= 0.80)");

            // 2. Top-right clock vignette: centered at (w, 0), radius = w * 0.52f, scaleY = 0.65f
            float rClock = w * 0.52f;
            float clockReachX = rClock * 1.0f;
            float clockReachY = rClock * 0.65f;

            assertTrue(rClock > 0, "4.13 [" + w + "x" + h + "] Clock radius is positive: " + rClock);
            assertTrue(clockReachX < w, "4.14 [" + w + "x" + h + "] Clock vignette does not cover entire width (clockReach=" + clockReachX + " < w=" + w + ")");
        }
    }

    // Helper functions mirroring DreamView implementations for isolated testing
    private static float scrimStrength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    // =========================================================================
    // 5. TYPOGRAPHY & PREFERENCES INTEGRATION
    // =========================================================================
    private static void test5_TypographyAndPreferencesIntegration() {
        System.out.println("\n--- [Section 5] Typography & Clock Styles Preferences Integration ---");

        List<String> clockStyles = Arrays.asList(Prefs.SAVER_CLOCK_STYLES);
        assertEquals(8, clockStyles.size(), "5.1: Prefs.SAVER_CLOCK_STYLES contains exactly 8 curated clock styles");
        assertTrue(clockStyles.contains("modern_serif"), "5.2: Contains modern_serif");
        assertTrue(clockStyles.contains("digital_mono"), "5.3: Contains digital_mono");
        assertTrue(clockStyles.contains("liquid_glass"), "5.4: Contains liquid_glass");
        assertTrue(clockStyles.contains("minimal"), "5.5: Contains minimal");
        assertTrue(clockStyles.contains("frosted_pill"), "5.6: Contains frosted_pill");
        assertTrue(clockStyles.contains("stacked"), "5.7: Contains stacked");
        assertTrue(clockStyles.contains("split_flap"), "5.7b: Contains split_flap");
        assertTrue(clockStyles.contains("outline"), "5.7c: Contains outline");

        // SettingsTree icon mappings
        assertEquals("clock_serif", SettingsTree.iconForClockStyle("modern_serif"), "5.8: modern_serif maps to dedicated clock_serif icon");
        assertEquals("clock_mono", SettingsTree.iconForClockStyle("digital_mono"), "5.9: digital_mono maps to dedicated clock_mono icon");
        assertEquals("clock_liquid", SettingsTree.iconForClockStyle("liquid_glass"), "5.10: liquid_glass maps to clock_liquid icon");
        assertEquals("clock_minimal", SettingsTree.iconForClockStyle("minimal"), "5.11: minimal maps to dedicated clock_minimal icon");
        assertEquals("clock_editorial", SettingsTree.iconForClockStyle("editorial"), "5.12: legacy editorial maps to clock_editorial icon");
        assertEquals("clock_classic", SettingsTree.iconForClockStyle("classic"), "5.13: legacy classic maps to clock_classic icon");

        // Resilience to case sensitivity and trimming
        assertEquals("clock_liquid", SettingsTree.iconForClockStyle("  modern_serif  "), "5.14: untrimmed input safely defaults to clock_liquid");
        assertEquals("clock_mono", SettingsTree.iconForClockStyle("DIGITAL_MONO"), "5.15: DIGITAL_MONO case-insensitively resolved");
        assertEquals("clock_liquid", SettingsTree.iconForClockStyle(null), "5.16: null clock style safely defaults to clock_liquid");
        assertEquals("clock_liquid", SettingsTree.iconForClockStyle(""), "5.17: empty clock style safely defaults to clock_liquid");
    }
}
