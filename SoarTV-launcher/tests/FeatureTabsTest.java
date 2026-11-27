package io.soartv;

/**
 * Unit tests for {@link FeatureTabs} verifying tri-state mode parsing ("auto", "true", "false"),
 * auto-detection of Gaming and Smart Home configurations, Reels build gating, enabled-screen masks,
 * and D-Pad Top-Bar Left/Right stepping across hidden optional tabs.
 */
public final class FeatureTabsTest {
    private static int passed;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FeatureTabsTest FAILED: " + msg);
        passed++;
    }

    public static void main(String[] args) {
        // 1. Tri-state normalization
        check("true".equals(FeatureTabs.normalizeMode("true")), "true normalized");
        check("true".equals(FeatureTabs.normalizeMode("ON")), "ON -> true");
        check("true".equals(FeatureTabs.normalizeMode("1")), "1 -> true");
        check("false".equals(FeatureTabs.normalizeMode("false")), "false normalized");
        check("false".equals(FeatureTabs.normalizeMode("Off")), "Off -> false");
        check("false".equals(FeatureTabs.normalizeMode("0")), "0 -> false");
        check("auto".equals(FeatureTabs.normalizeMode("auto")), "auto normalized");
        check("auto".equals(FeatureTabs.normalizeMode("")), "empty -> auto");
        check("auto".equals(FeatureTabs.normalizeMode(null)), "null -> auto");

        // 2. Mode resolution with runtime override > build config > auto-detect
        check(FeatureTabs.resolveFeatureEnabled("auto", "auto", false) == false,
                "Auto + no config hides optional tab");
        check(FeatureTabs.resolveFeatureEnabled("auto", "auto", true) == true,
                "Auto + detected keys/devices shows optional tab");
        check(FeatureTabs.resolveFeatureEnabled("auto", "true", false) == true,
                "Build config true shows tab even before keys are entered");
        check(FeatureTabs.resolveFeatureEnabled("auto", "false", true) == false,
                "Build config false hides tab even if keys exist");
        check(FeatureTabs.resolveFeatureEnabled("true", "false", false) == true,
                "Runtime TV override true wins over build config false");
        check(FeatureTabs.resolveFeatureEnabled("false", "true", true) == false,
                "Runtime TV override false wins over build config true");

        // 3. Enabled screen mask & Top-Bar D-pad stepping when all optional tabs are hidden
        boolean[] coreOnly = FeatureTabs.buildEnabledScreenMask(false, false, false);
        check(coreOnly.length == HomeView.TOP_BAR_COUNT, "Mask length matches TOP_BAR_COUNT (7)");
        check(coreOnly[HomeView.SCREEN_SEARCH] && coreOnly[HomeView.SCREEN_HOME]
                && coreOnly[HomeView.SCREEN_APPS] && coreOnly[HomeView.SCREEN_SETTINGS],
                "Core screens (Search, Home, Apps, Settings) are always enabled");
        check(!coreOnly[HomeView.SCREEN_GAMING] && !coreOnly[HomeView.SCREEN_REELS]
                && !coreOnly[HomeView.SCREEN_SMARTHOME],
                "Optional screens (Gaming, Reels, SmartHome) disabled in core-only mask");

        // Stepping right from Apps (2) skips 3, 4, 5 and lands directly on Settings (6)
        check(FeatureTabs.stepEnabledScreen(HomeView.SCREEN_APPS, +1, coreOnly) == HomeView.SCREEN_SETTINGS,
                "Right from Apps jumps directly to Settings when Gaming/Reels/SmartHome are hidden");
        // Stepping left from Settings (6) skips 5, 4, 3 and lands directly on Apps (2)
        check(FeatureTabs.stepEnabledScreen(HomeView.SCREEN_SETTINGS, -1, coreOnly) == HomeView.SCREEN_APPS,
                "Left from Settings jumps directly to Apps when Gaming/Reels/SmartHome are hidden");
        // Boundary clamping at Search (0) and Settings (6)
        check(FeatureTabs.stepEnabledScreen(HomeView.SCREEN_SEARCH, -1, coreOnly) == HomeView.SCREEN_SEARCH,
                "Left from Search stays on Search");
        check(FeatureTabs.stepEnabledScreen(HomeView.SCREEN_SETTINGS, +1, coreOnly) == HomeView.SCREEN_SETTINGS,
                "Right from Settings stays on Settings");

        // 4. Partial optional tabs (e.g. Gaming OFF, Reels OFF, Smart Home ON)
        boolean[] smartHomeOnly = FeatureTabs.buildEnabledScreenMask(false, false, true);
        check(FeatureTabs.stepEnabledScreen(HomeView.SCREEN_APPS, +1, smartHomeOnly) == HomeView.SCREEN_SMARTHOME,
                "Right from Apps lands on Smart Home when Gaming & Reels are off");
        check(FeatureTabs.stepEnabledScreen(HomeView.SCREEN_SMARTHOME, -1, smartHomeOnly) == HomeView.SCREEN_APPS,
                "Left from Smart Home lands on Apps when Gaming & Reels are off");

        // 5. Screen sanitization falls back to Home when targeting a hidden optional tab
        check(FeatureTabs.sanitizeActiveScreen(HomeView.SCREEN_GAMING, coreOnly) == HomeView.SCREEN_HOME,
                "Hidden Gaming screen sanitizes safely to Home");
        check(FeatureTabs.sanitizeActiveScreen(HomeView.SCREEN_REELS, smartHomeOnly) == HomeView.SCREEN_HOME,
                "Hidden Reels screen sanitizes safely to Home");
        check(FeatureTabs.sanitizeActiveScreen(HomeView.SCREEN_SMARTHOME, smartHomeOnly) == HomeView.SCREEN_SMARTHOME,
                "Enabled Smart Home screen stays active");

        System.out.println("FeatureTabs: " + passed + " assertions passed");
    }
}
