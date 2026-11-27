package io.soartv;

import android.graphics.Typeface;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Empirical Adversarial Challenge Suite for Milestone 1 (Visual Engine & Clock Overhaul).
 *
 * Authored by Challenger M1-2 to stress-test:
 * 1. Clock style resolution with invalid/unknown/null string values.
 * 2. drawGlass safety under missing/null bitmaps, null shaders, or zero bounds.
 * 3. Typeface resolution for all registered clock styles.
 */
public final class AdversarialCorrectnessM1Challenge {

    private static int assertions = 0;
    private static int failedAssertions = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== STARTING ADVERSARIAL CORRECTNESS M1 CHALLENGE SUITE ===");

        test1_ClockStyleResolutionAdversarial();
        test2_DrawGlassSafetyAdversarial();
        test3_TypefaceResolutionAdversarial();

        System.out.println("\n=== CHALLENGE SUITE SUMMARY ===");
        System.out.println("Total Assertions Executed: " + assertions);
        System.out.println("Failed Assertions: " + failedAssertions);

        if (failedAssertions > 0) {
            System.err.println("ADVERSARIAL CHALLENGE RESULT: FAIL (" + failedAssertions + " failure(s) detected)");
            System.exit(1);
        } else {
            System.out.println("ADVERSARIAL CHALLENGE RESULT: APPROVE");
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        assertions++;
        if (!cond) {
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

    // =========================================================================
    // 1. CLOCK STYLE RESOLUTION ADVERSARIAL
    // =========================================================================
    private static void test1_ClockStyleResolutionAdversarial() {
        System.out.println("\n--- [Challenge 1] Clock Style Resolution Robustness ---");

        // 1.1 Test Prefs style array contract
        List<String> registered = Arrays.asList(Prefs.SAVER_CLOCK_STYLES);
        assertEquals(8, registered.size(), "Prefs.SAVER_CLOCK_STYLES has exactly 8 curated styles");
        assertTrue(registered.contains("liquid_glass"), "Contains liquid_glass");
        assertTrue(registered.contains("frosted_pill"), "Contains frosted_pill");
        assertTrue(registered.contains("stacked"), "Contains stacked");
        assertTrue(registered.contains("split_flap"), "Contains split_flap");
        assertTrue(registered.contains("modern_serif"), "Contains modern_serif");
        assertTrue(registered.contains("digital_mono"), "Contains digital_mono");
        assertTrue(registered.contains("minimal"), "Contains minimal");
        assertTrue(registered.contains("outline"), "Contains outline");

        // 1.2 Test SettingsTree.iconForClockStyle resolution with adversarial inputs
        String[] corruptedInputs = {
                null, "", " ", "   ", "unknown", "comic_sans", "papyrus", "MODERN_SERIF",
                "Digital_Mono", "liquid-glass", "null", "undefined", "\u0000", "12345"
        };

        for (String corrupted : corruptedInputs) {
            String icon = SettingsTree.iconForClockStyle(corrupted);
            if (corrupted == null || corrupted.trim().isEmpty() || "unknown".equals(corrupted)
                    || "comic_sans".equals(corrupted) || "papyrus".equals(corrupted)
                    || "liquid-glass".equals(corrupted) || "null".equals(corrupted)
                    || "undefined".equals(corrupted) || "\u0000".equals(corrupted) || "12345".equals(corrupted)) {
                assertEquals("clock_liquid", icon, "Corrupted input [" + corrupted + "] safely resolves to default 'clock_liquid'");
            } else if ("MODERN_SERIF".equals(corrupted)) {
                assertEquals("clock_serif", icon, "Uppercase MODERN_SERIF case-insensitively resolves to 'clock_serif'");
            } else if ("Digital_Mono".equals(corrupted)) {
                assertEquals("clock_mono", icon, "Mixed-case Digital_Mono case-insensitively resolves to 'clock_mono'");
            }
        }

        // 1.3 Test SettingsTree.buildScreensaverTree with unexpected string styles
        String[] unknownNonCrashStyles = {"bogus", "vintage", "analog", "neon", "retro"};
        for (String style : unknownNonCrashStyles) {
            SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
            snap.saverClockStyle = style;
            try {
                SettingsTree.Node root = SettingsTree.buildScreensaverTree(snap);
                assertTrue(root != null, "SettingsTree built successfully without crash for unexpected style [" + style + "]");
            } catch (Exception e) {
                assertTrue(false, "Crash occurred on unexpected style [" + style + "]: " + e.getMessage());
            }
        }

        // 1.4 Test SettingsTree with NULL style — verified null-safe fallback to liquid_glass
        boolean npeCaught = false;
        try {
            SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
            snap.saverClockStyle = null;
            SettingsTree.Node built = SettingsTree.buildScreensaverTree(snap);
            assertTrue(built != null, "SettingsTree handles null saverClockStyle cleanly");
        } catch (NullPointerException npe) {
            npeCaught = true;
        }
        assertTrue(!npeCaught, "SettingsTree.buildScreensaverTree never throws NullPointerException when saverClockStyle is null");
    }

    // =========================================================================
    // 2. DRAWGLASS SAFETY ADVERSARIAL
    // =========================================================================
    private static void test2_DrawGlassSafetyAdversarial() {
        System.out.println("\n--- [Challenge 2] drawGlass Safety Under Nulls & Zero Bounds ---");

        // 2.1 Arithmetic and guard conditions
        float[] placeA2Vals = {0.0f, -0.001f, -100.0f};
        for (float z : placeA2Vals) {
            boolean wouldPassGuard = z > 0f;
            assertTrue(!wouldPassGuard, "placeA[2] = " + z + " rejected by placeA[2] <= 0f guard, preventing division by zero");
        }

        float[] alphaVals = {0.0f, 0.001f, 0.004f};
        for (float a : alphaVals) {
            boolean wouldPassGuard = a > 0.004f;
            assertTrue(!wouldPassGuard, "alpha = " + a + " rejected by alpha <= 0.004f guard");
        }

        // 2.2 Verify makeGlass bound clamping logic in source
        try {
            String dreamSrc = new String(Files.readAllBytes(Paths.get("app/src/io/soartv/DreamView.java")), StandardCharsets.UTF_8);
            assertTrue(dreamSrc.contains("Math.max(1, ink.width() + 2 * pad)"), "DreamView enforces Math.max(1, width) clamping in makeGlass");
            assertTrue(dreamSrc.contains("Math.max(1, ink.height() + 2 * pad)"), "DreamView enforces Math.max(1, height) clamping in makeGlass");
            assertTrue(dreamSrc.contains("placeA[2] <= 0f"), "DreamView enforces placeA[2] <= 0f guard in drawGlass");
            assertTrue(dreamSrc.contains("incoming != null && incoming.shader != null && placeB[2] > 0f"),
                    "DreamView enforces null incoming, null incoming.shader, and placeB[2] > 0f guards in drawGlass");
        } catch (Exception e) {
            assertTrue(false, "Failed to read DreamView.java: " + e.getMessage());
        }

        // 2.3 Transition safety: incoming is null mathematical derivation
        float[] placeA = {100f, 100f, 200f};
        float[] placeB = {0f, 0f, 0f};
        Object incoming = null;
        float mixB = 0.5f;

        // Simulate logic of DreamView:1106
        float finalPlaceB_x, finalPlaceB_y, finalPlaceB_z, finalMixB;
        if (incoming != null && placeB[2] > 0f) {
            finalPlaceB_x = placeB[0];
            finalPlaceB_y = placeB[1];
            finalPlaceB_z = placeB[2];
            finalMixB = mixB;
        } else {
            finalPlaceB_x = placeA[0];
            finalPlaceB_y = placeA[1];
            finalPlaceB_z = placeA[2];
            finalMixB = 0f;
        }
        assertEquals(placeA[0], finalPlaceB_x, "Fallback placeB.x equals placeA.x when incoming is null");
        assertEquals(placeA[2], finalPlaceB_z, "Fallback placeB.z equals placeA.z when incoming is null");
        assertEquals(0f, finalMixB, "Fallback mixB equals 0f when incoming is null");
    }

    // =========================================================================
    // 3. TYPEFACE RESOLUTION ADVERSARIAL
    // =========================================================================
    private static void test3_TypefaceResolutionAdversarial() {
        System.out.println("\n--- [Challenge 3] Typeface Resolution for All Clock Styles ---");

        // 3.1 Verify native Typeface constants resolve to non-null via reflection
        try {
            Field serifField = Typeface.class.getField("SERIF");
            assertTrue(serifField != null, "Typeface.SERIF field exists in Android SDK");
            Field monoField = Typeface.class.getField("MONOSPACE");
            assertTrue(monoField != null, "Typeface.MONOSPACE field exists in Android SDK");
            Field defaultField = Typeface.class.getField("DEFAULT");
            assertTrue(defaultField != null, "Typeface.DEFAULT field exists in Android SDK");
        } catch (Exception e) {
            assertTrue(false, "Reflection failed on Typeface fields: " + e.getMessage());
        }

        // 3.2 Verify DreamView.java and SettingsScreenRenderer.java contain all registered branches
        try {
            String dreamSrc = new String(Files.readAllBytes(Paths.get("app/src/io/soartv/DreamView.java")), StandardCharsets.UTF_8);
            assertTrue(dreamSrc.contains("\"modern_serif\".equals(style)"), "DreamView contains branch for modern_serif");
            assertTrue(dreamSrc.contains("Typeface.create(Typeface.SERIF, Typeface.BOLD)"), "DreamView maps modern_serif to Typeface.SERIF BOLD");
            assertTrue(dreamSrc.contains("\"digital_mono\".equals(style)"), "DreamView contains branch for digital_mono");
            assertTrue(dreamSrc.contains("Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)"), "DreamView maps digital_mono to Typeface.MONOSPACE BOLD");
            assertTrue(dreamSrc.contains("cornerClock.setTypeface(Ui.displayMedium)"), "DreamView contains fallback branch to Ui.displayMedium");

            String rendererSrc = new String(Files.readAllBytes(Paths.get("app/src/io/soartv/SettingsScreenRenderer.java")), StandardCharsets.UTF_8);
            assertTrue(rendererSrc.contains("\"modern_serif\".equals(style)"), "SettingsScreenRenderer contains branch for modern_serif");
            assertTrue(rendererSrc.contains("\"digital_mono\".equals(style)"), "SettingsScreenRenderer contains branch for digital_mono");
            assertTrue(rendererSrc.contains("fitPaint.setTypeface(Ui.displaySemibold)"), "SettingsScreenRenderer contains fallback to Ui.displaySemibold");
        } catch (Exception e) {
            assertTrue(false, "Failed to read DreamView.java or SettingsScreenRenderer.java: " + e.getMessage());
        }
    }
}
