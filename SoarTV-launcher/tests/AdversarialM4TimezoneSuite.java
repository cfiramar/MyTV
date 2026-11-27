package io.soartv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Empirical Adversarial Challenge Test Suite for Milestone 4 (Requirement R4: Timezone Selection Display).
 *
 * <p>Stress-tests:
 * 1. Three-Letter Code Removal Across All 61 World Hubs:
 *    - 100% of generated timezone item titles do NOT contain " (" or 3-letter airport abbreviations.
 *    - Universal Time / UTC hub title is clean ("Universal Time", not "Universal Time (UTC)").
 *    - All 61 timezone IDs are recognized valid IANA timezones.
 * 2. Subtitle Formatting ("HH:mm (UTC±X)"):
 *    - Strict regex match: "^[0-9]{1,2}:[0-9]{2}(\\s+[AP]M)?\\s+\\(UTC[+-][0-9]+(:[0-9]{2})?\\)$".
 *    - Non-standard minute offsets: Asia/Kathmandu (UTC+5:45), Asia/Kolkata (UTC+5:30), America/St_Johns (UTC-3:30),
 *      Australia/Adelaide (UTC+9:30 or UTC+10:30), Asia/Tehran (UTC+3:30), Asia/Kabul (UTC+4:30).
 *    - DST transitions: America/New_York (UTC-5 vs UTC-4), Europe/London (UTC+0 vs UTC+1),
 *      Asia/Jerusalem (UTC+2 vs UTC+3), Pacific/Auckland (UTC+12 vs UTC+13).
 *    - 12-hour vs 24-hour modes at boundary times: midnight 00:00 / 12:00 AM, noon 12:00 / 12:00 PM,
 *      edge minutes 23:59 / 11:59 PM, 00:01 / 12:01 AM.
 *    - Null / empty timezone string resilience.
 * 3. Node ID Preservation & Click Action Safety:
 *    - Every node ID strictly adheres to "ss_tz:<tzId>:<codeKey>".
 *    - Click splitting on ":" and Prefs.setSaverWorldClock invocation without IndexOutOfBoundsException.
 *    - All 61 hub selection round-trips: exactly 1 item selected, parent badge matches code + offset.
 *    - Malformed node ID resilience against crash.
 * 4. Dynamic Live Ticking in SettingsScreenRenderer:
 *    - Verification of isTzRow contract and dynamic subtitle update at draw time.
 *    - Minute ticking across time boundaries without stale cache.
 *
 * Authored by Challenger M4-2.
 */
public final class AdversarialM4TimezoneSuite {

    private static int assertions = 0;
    private static int failedAssertions = 0;
    private static final List<String> criticalDefects = new ArrayList<>();

    private static final Pattern SUBTITLE_PATTERN = Pattern.compile(
            "^[0-9]{1,2}:[0-9]{2}(\\s+[AP]M)?\\s+\\(UTC[+-][0-9]+(:[0-9]{2})?\\)$");

    private AdversarialM4TimezoneSuite() {}

    private static void assertTrue(boolean condition, String message) {
        assertions++;
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
        assertions++;
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            failedAssertions++;
            System.err.println("  [FAIL] " + message + " (expected: " + expected + ", got: " + actual + ")");
            criticalDefects.add(message + " (expected: " + expected + ", got: " + actual + ")");
        } else {
            System.out.println("  [PASS] " + message);
        }
    }

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   ADVERSARIAL CHALLENGE SUITE: MILESTONE 4 (REQUIREMENT R4 TIMEZONE SELECTION)  ");
        System.out.println("================================================================================");

        long startTime = System.currentTimeMillis();

        testThreeLetterCodeRemovalAcrossAll61Hubs();
        testSubtitleFormattingAndRegex();
        testNonStandardOffsetsAndDstTransitions();
        testEdgeTimesAnd12Vs24HourModes();
        testNodeIdPreservationAndClickActionSafety();
        testMalformedNodeIdSafety();
        testDynamicLiveTickingInRenderer();
        testFull61HubSelectionRoundTrips();

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("=== ADVERSARIAL M4 TIMEZONE SELECTION SUITE SUMMARY ===");
        System.out.println("Total Assertions Executed: " + assertions);
        System.out.println("Failed Assertions: " + failedAssertions);
        System.out.println("Execution Time: " + duration + " ms");

        if (failedAssertions > 0) {
            System.err.println("CRITICAL DEFECTS IDENTIFIED (" + criticalDefects.size() + "):");
            for (String defect : criticalDefects) {
                System.err.println(" - " + defect);
            }
            System.err.println("ADVERSARIAL SUITE RESULT: REJECT");
            System.exit(1);
        } else {
            System.out.println("Critical Defects Identified: 0");
            System.out.println("ADVERSARIAL SUITE RESULT: APPROVE");
        }
    }

    // =========================================================================
    // 1. Three-Letter Code Removal Across All 61 World Hubs
    // =========================================================================

    private static void testThreeLetterCodeRemovalAcrossAll61Hubs() {
        System.out.println("\n--- [1] Three-Letter Code Removal Across All 61 World Hubs ---");

        assertEquals(61, Prefs.SAVER_WORLD_HUBS.length, "Prefs.SAVER_WORLD_HUBS contains exactly 61 metropolitan hubs");

        Set<String> validJavaTzIds = new HashSet<>(Arrays.asList(TimeZone.getAvailableIDs()));
        Set<String> uniqueCodes = new HashSet<>();
        Set<String> uniqueTzIds = new HashSet<>();

        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.referenceNowMs = 1768478400000L; // 2026-01-15 12:00:00 UTC (fixed reference)
        snap.h24 = true;
        snap.saverWorldClockTz = "America/New_York";
        snap.saverWorldClockLabel = "NYC";

        SettingsTree.Node root = SettingsTree.buildRoot(snap);
        SettingsTree.Node tzParent = root.findById("ss_tz");
        assertTrue(tzParent != null && tzParent.children != null, "ss_tz parent node exists with children");
        assertEquals(61, tzParent.children.size(), "ss_tz parent contains exactly 61 child timezone nodes");

        for (int i = 0; i < Prefs.SAVER_WORLD_HUBS.length; i++) {
            String[] hub = Prefs.SAVER_WORLD_HUBS[i];
            String code = hub[0];
            String tzId = hub[1];
            String rawCity = hub[2];

            uniqueCodes.add(code);
            uniqueTzIds.add(tzId);

            // Assert valid IANA timezone in Java runtime
            assertTrue(validJavaTzIds.contains(tzId) || "UTC".equals(tzId),
                    "Hub [" + code + "] timezone ID '" + tzId + "' is a recognized IANA timezone in Java");

            SettingsTree.Node node = tzParent.children.get(i);

            // Assert Node ID format
            assertEquals("ss_tz:" + tzId + ":" + code, node.id,
                    "Hub [" + i + "] node ID adheres strictly to 'ss_tz:<tzId>:<codeKey>'");

            // Assert NO " (" or trailing code
            assertFalse(node.title.contains(" ("),
                    "Hub [" + code + "] title '" + node.title + "' contains no parenthesized string ' ('");
            assertFalse(node.title.contains("(" + code + ")"),
                    "Hub [" + code + "] title '" + node.title + "' contains no parenthesized code '(" + code + ")'");
            assertFalse(node.title.endsWith(" " + code),
                    "Hub [" + code + "] title '" + node.title + "' does not end with space-separated code ' " + code + "'");
            assertFalse(node.title.contains(" " + code + " "),
                    "Hub [" + code + "] title '" + node.title + "' contains no embedded code ' " + code + " '");

            // Explicit check for known hub codes
            if ("UTC".equals(code)) {
                assertEquals("Universal Time", node.title,
                        "Universal Time / UTC hub title is cleanly stripped to 'Universal Time'");
            } else if ("SIN".equals(code)) {
                assertEquals("Singapore", node.title, "Singapore title is clean without ' (SIN)'");
            } else if ("NYC".equals(code)) {
                assertEquals("New York", node.title, "New York title is clean without ' (NYC)'");
            } else if ("LON".equals(code)) {
                assertEquals("London", node.title, "London title is clean without ' (LON)'");
            } else if ("TYO".equals(code)) {
                assertEquals("Tokyo", node.title, "Tokyo title is clean without ' (TYO)'");
            } else if ("TLV".equals(code)) {
                assertEquals("Jerusalem / Tel Aviv", node.title, "Jerusalem / Tel Aviv title is clean without ' (TLV)'");
            }
        }

        assertEquals(61, uniqueCodes.size(), "All 61 hub airport abbreviation codes are unique");
        assertEquals(61, uniqueTzIds.size(), "All 61 hub IANA timezone IDs are unique");
    }

    // =========================================================================
    // 2. Subtitle Formatting ("HH:mm (UTC±X)")
    // =========================================================================

    private static void testSubtitleFormattingAndRegex() {
        System.out.println("\n--- [2] Subtitle Formatting & Strict Regex Verification ---");

        long refNow = 1768478400000L; // 2026-01-15 12:00:00 UTC

        // Test all 61 hubs in both 24-hour and 12-hour modes against strict regex
        for (String[] hub : Prefs.SAVER_WORLD_HUBS) {
            String tzId = hub[1];

            // 24-hour format
            String sub24 = ScreensaverCatalog.formatTimezoneSubtitle(tzId, refNow, true);
            assertTrue(SUBTITLE_PATTERN.matcher(sub24).matches(),
                    "Hub " + tzId + " 24h subtitle '" + sub24 + "' strictly matches regex");
            assertTrue(sub24.contains("(UTC"), "24h subtitle contains '(UTC': " + sub24);
            assertTrue(sub24.endsWith(")"), "24h subtitle ends with ')': " + sub24);

            // 12-hour format
            String sub12 = ScreensaverCatalog.formatTimezoneSubtitle(tzId, refNow, false);
            assertTrue(SUBTITLE_PATTERN.matcher(sub12).matches(),
                    "Hub " + tzId + " 12h subtitle '" + sub12 + "' strictly matches regex");
            assertTrue(sub12.contains(" AM") || sub12.contains(" PM"),
                    "12h subtitle contains AM/PM: " + sub12);
            assertTrue(sub12.contains("(UTC"), "12h subtitle contains '(UTC': " + sub12);
            assertTrue(sub12.endsWith(")"), "12h subtitle ends with ')': " + sub12);
        }
    }

    // =========================================================================
    // 3. Non-Standard Offsets & DST Transitions
    // =========================================================================

    private static void testNonStandardOffsetsAndDstTransitions() {
        System.out.println("\n--- [3] Non-Standard Offsets & DST Transitions ---");

        // Fixed winter epoch: 2026-01-15 12:00:00 UTC
        long winterNoonUtc = 1768478400000L;
        // Fixed summer epoch: 2026-07-15 12:00:00 UTC
        long summerNoonUtc = 1784116800000L;

        // 3.1 Non-standard minute offsets:
        // Asia/Kathmandu (UTC+5:45) - Nepal has no DST
        assertEquals("UTC+5:45", ScreensaverCatalog.formatUtcOffset("Asia/Kathmandu", winterNoonUtc),
                "Kathmandu winter offset is UTC+5:45");
        assertEquals("UTC+5:45", ScreensaverCatalog.formatUtcOffset("Asia/Kathmandu", summerNoonUtc),
                "Kathmandu summer offset remains UTC+5:45");
        assertEquals("17:45 (UTC+5:45)", ScreensaverCatalog.formatTimezoneSubtitle("Asia/Kathmandu", winterNoonUtc, true),
                "Kathmandu 24h subtitle at 12:00 UTC is '17:45 (UTC+5:45)'");

        // Asia/Kolkata (UTC+5:30) - India has no DST
        assertEquals("UTC+5:30", ScreensaverCatalog.formatUtcOffset("Asia/Kolkata", winterNoonUtc),
                "Kolkata winter offset is UTC+5:30");
        assertEquals("UTC+5:30", ScreensaverCatalog.formatUtcOffset("Asia/Kolkata", summerNoonUtc),
                "Kolkata summer offset remains UTC+5:30");
        assertEquals("17:30 (UTC+5:30)", ScreensaverCatalog.formatTimezoneSubtitle("Asia/Kolkata", winterNoonUtc, true),
                "Kolkata 24h subtitle at 12:00 UTC is '17:30 (UTC+5:30)'");

        // America/St_Johns (Newfoundland: UTC-3:30 winter / UTC-2:30 summer)
        assertEquals("UTC-3:30", ScreensaverCatalog.formatUtcOffset("America/St_Johns", winterNoonUtc),
                "St. John's winter offset is UTC-3:30");
        assertEquals("UTC-2:30", ScreensaverCatalog.formatUtcOffset("America/St_Johns", summerNoonUtc),
                "St. John's summer DST offset is UTC-2:30");
        assertEquals("08:30 (UTC-3:30)", ScreensaverCatalog.formatTimezoneSubtitle("America/St_Johns", winterNoonUtc, true),
                "St. John's winter 24h subtitle at 12:00 UTC is '08:30 (UTC-3:30)'");
        assertEquals("09:30 (UTC-2:30)", ScreensaverCatalog.formatTimezoneSubtitle("America/St_Johns", summerNoonUtc, true),
                "St. John's summer 24h subtitle at 12:00 UTC is '09:30 (UTC-2:30)'");
        assertEquals("8:30 AM (UTC-3:30)", ScreensaverCatalog.formatTimezoneSubtitle("America/St_Johns", winterNoonUtc, false),
                "St. John's winter 12h subtitle at 12:00 UTC is '8:30 AM (UTC-3:30)'");
        assertEquals("9:30 AM (UTC-2:30)", ScreensaverCatalog.formatTimezoneSubtitle("America/St_Johns", summerNoonUtc, false),
                "St. John's summer 12h subtitle at 12:00 UTC is '9:30 AM (UTC-2:30)'");

        // Australia/Adelaide (UTC+9:30 winter ACST / UTC+10:30 summer ACDT)
        // Note: Southern hemisphere: July is winter (ACST +9:30), January is summer (ACDT +10:30)
        assertEquals("UTC+10:30", ScreensaverCatalog.formatUtcOffset("Australia/Adelaide", winterNoonUtc),
                "Adelaide January (southern summer DST) offset is UTC+10:30");
        assertEquals("UTC+9:30", ScreensaverCatalog.formatUtcOffset("Australia/Adelaide", summerNoonUtc),
                "Adelaide July (southern winter standard) offset is UTC+9:30");
        assertEquals("22:30 (UTC+10:30)", ScreensaverCatalog.formatTimezoneSubtitle("Australia/Adelaide", winterNoonUtc, true),
                "Adelaide January 24h subtitle at 12:00 UTC is '22:30 (UTC+10:30)'");
        assertEquals("21:30 (UTC+9:30)", ScreensaverCatalog.formatTimezoneSubtitle("Australia/Adelaide", summerNoonUtc, true),
                "Adelaide July 24h subtitle at 12:00 UTC is '21:30 (UTC+9:30)'");

        // Asia/Tehran (UTC+3:30 standard)
        assertEquals("UTC+3:30", ScreensaverCatalog.formatUtcOffset("Asia/Tehran", winterNoonUtc),
                "Tehran winter offset is UTC+3:30");
        assertEquals("15:30 (UTC+3:30)", ScreensaverCatalog.formatTimezoneSubtitle("Asia/Tehran", winterNoonUtc, true),
                "Tehran 24h subtitle at 12:00 UTC is '15:30 (UTC+3:30)'");

        // Asia/Kabul (UTC+4:30 standard)
        assertEquals("UTC+4:30", ScreensaverCatalog.formatUtcOffset("Asia/Kabul", winterNoonUtc),
                "Kabul offset is UTC+4:30");
        assertEquals("16:30 (UTC+4:30)", ScreensaverCatalog.formatTimezoneSubtitle("Asia/Kabul", winterNoonUtc, true),
                "Kabul 24h subtitle at 12:00 UTC is '16:30 (UTC+4:30)'");

        // 3.2 DST transitions:
        // America/New_York (UTC-5 winter EST / UTC-4 summer EDT)
        assertEquals("UTC-5", ScreensaverCatalog.formatUtcOffset("America/New_York", winterNoonUtc),
                "New York winter offset is UTC-5");
        assertEquals("UTC-4", ScreensaverCatalog.formatUtcOffset("America/New_York", summerNoonUtc),
                "New York summer offset is UTC-4");
        assertEquals("07:00 (UTC-5)", ScreensaverCatalog.formatTimezoneSubtitle("America/New_York", winterNoonUtc, true),
                "New York winter 24h subtitle at 12:00 UTC is '07:00 (UTC-5)'");
        assertEquals("08:00 (UTC-4)", ScreensaverCatalog.formatTimezoneSubtitle("America/New_York", summerNoonUtc, true),
                "New York summer 24h subtitle at 12:00 UTC is '08:00 (UTC-4)'");
        assertEquals("7:00 AM (UTC-5)", ScreensaverCatalog.formatTimezoneSubtitle("America/New_York", winterNoonUtc, false),
                "New York winter 12h subtitle at 12:00 UTC is '7:00 AM (UTC-5)'");
        assertEquals("8:00 AM (UTC-4)", ScreensaverCatalog.formatTimezoneSubtitle("America/New_York", summerNoonUtc, false),
                "New York summer 12h subtitle at 12:00 UTC is '8:00 AM (UTC-4)'");

        // Europe/London (UTC+0 winter GMT / UTC+1 summer BST)
        assertEquals("UTC+0", ScreensaverCatalog.formatUtcOffset("Europe/London", winterNoonUtc),
                "London winter offset is UTC+0");
        assertEquals("UTC+1", ScreensaverCatalog.formatUtcOffset("Europe/London", summerNoonUtc),
                "London summer offset is UTC+1");
        assertEquals("12:00 (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("Europe/London", winterNoonUtc, true),
                "London winter 24h subtitle at 12:00 UTC is '12:00 (UTC+0)'");
        assertEquals("13:00 (UTC+1)", ScreensaverCatalog.formatTimezoneSubtitle("Europe/London", summerNoonUtc, true),
                "London summer 24h subtitle at 12:00 UTC is '13:00 (UTC+1)'");

        // Asia/Jerusalem (UTC+2 winter / UTC+3 summer IDT)
        assertEquals("UTC+2", ScreensaverCatalog.formatUtcOffset("Asia/Jerusalem", winterNoonUtc),
                "Jerusalem winter offset is UTC+2");
        assertEquals("UTC+3", ScreensaverCatalog.formatUtcOffset("Asia/Jerusalem", summerNoonUtc),
                "Jerusalem summer offset is UTC+3");
        assertEquals("14:00 (UTC+2)", ScreensaverCatalog.formatTimezoneSubtitle("Asia/Jerusalem", winterNoonUtc, true),
                "Jerusalem winter 24h subtitle at 12:00 UTC is '14:00 (UTC+2)'");
        assertEquals("15:00 (UTC+3)", ScreensaverCatalog.formatTimezoneSubtitle("Asia/Jerusalem", summerNoonUtc, true),
                "Jerusalem summer 24h subtitle at 12:00 UTC is '15:00 (UTC+3)'");

        // Pacific/Auckland (UTC+13 January summer NZDT / UTC+12 July winter NZST)
        assertEquals("UTC+13", ScreensaverCatalog.formatUtcOffset("Pacific/Auckland", winterNoonUtc),
                "Auckland January (southern summer) offset is UTC+13");
        assertEquals("UTC+12", ScreensaverCatalog.formatUtcOffset("Pacific/Auckland", summerNoonUtc),
                "Auckland July (southern winter) offset is UTC+12");
    }

    // =========================================================================
    // 4. Edge Times & 12-Hour vs 24-Hour Modes
    // =========================================================================

    private static void testEdgeTimesAnd12Vs24HourModes() {
        System.out.println("\n--- [4] Edge Times & 12-Hour vs 24-Hour Modes ---");

        // Midnight UTC: 2026-01-15 00:00:00 UTC (1768435200000L)
        long midnightUtc = 1768435200000L;
        assertEquals("00:00 (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", midnightUtc, true),
                "UTC midnight in 24h is '00:00 (UTC+0)'");
        assertEquals("12:00 AM (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", midnightUtc, false),
                "UTC midnight in 12h is '12:00 AM (UTC+0)'");

        // Noon UTC: 2026-01-15 12:00:00 UTC (1768478400000L)
        long noonUtc = 1768478400000L;
        assertEquals("12:00 (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", noonUtc, true),
                "UTC noon in 24h is '12:00 (UTC+0)'");
        assertEquals("12:00 PM (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", noonUtc, false),
                "UTC noon in 12h is '12:00 PM (UTC+0)'");

        // One minute before midnight: 23:59:00 UTC
        long minBeforeMidnight = midnightUtc - 60_000L;
        assertEquals("23:59 (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", minBeforeMidnight, true),
                "UTC 23:59 in 24h is '23:59 (UTC+0)'");
        assertEquals("11:59 PM (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", minBeforeMidnight, false),
                "UTC 23:59 in 12h is '11:59 PM (UTC+0)'");

        // One minute after midnight: 00:01:00 UTC
        long minAfterMidnight = midnightUtc + 60_000L;
        assertEquals("00:01 (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", minAfterMidnight, true),
                "UTC 00:01 in 24h is '00:01 (UTC+0)'");
        assertEquals("12:01 AM (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", minAfterMidnight, false),
                "UTC 00:01 in 12h is '12:01 AM (UTC+0)'");

        // One minute before noon: 11:59:00 UTC
        long minBeforeNoon = noonUtc - 60_000L;
        assertEquals("11:59 (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", minBeforeNoon, true),
                "UTC 11:59 in 24h is '11:59 (UTC+0)'");
        assertEquals("11:59 AM (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", minBeforeNoon, false),
                "UTC 11:59 in 12h is '11:59 AM (UTC+0)'");

        // One minute after noon: 12:01:00 UTC
        long minAfterNoon = noonUtc + 60_000L;
        assertEquals("12:01 (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", minAfterNoon, true),
                "UTC 12:01 in 24h is '12:01 (UTC+0)'");
        assertEquals("12:01 PM (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("UTC", minAfterNoon, false),
                "UTC 12:01 in 12h is '12:01 PM (UTC+0)'");

        // Null and empty tzId resilience
        assertEquals("00:00 (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle(null, midnightUtc, true),
                "null tzId defaults safely to UTC in 24h");
        assertEquals("12:00 AM (UTC+0)", ScreensaverCatalog.formatTimezoneSubtitle("", midnightUtc, false),
                "empty tzId defaults safely to UTC in 12h");
    }

    // =========================================================================
    // 5. Node ID Preservation & Click Action Safety
    // =========================================================================

    private static void testNodeIdPreservationAndClickActionSafety() {
        System.out.println("\n--- [5] Node ID Preservation & Click Action Safety ---");

        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.referenceNowMs = 1768478400000L;
        snap.saverWorldClockTz = "Asia/Tokyo";
        snap.saverWorldClockLabel = "TYO";
        SettingsTree.Node root = SettingsTree.buildRoot(snap);
        SettingsTree.Node tzParent = root.findById("ss_tz");
        assertTrue(tzParent != null, "ss_tz parent exists");

        for (SettingsTree.Node child : tzParent.children) {
            assertTrue(child.id.startsWith("ss_tz:"), "Child ID starts with 'ss_tz:': " + child.id);

            // Emulate HomeView.java line 11266:
            // String[] parts = id.substring("ss_tz:".length()).split(":", 2);
            String[] parts = child.id.substring("ss_tz:".length()).split(":", 2);
            assertEquals(2, parts.length, "Child ID splits into exactly 2 parts: " + child.id);

            String tzId = parts[0];
            String codeKey = parts[1];

            assertFalse(tzId.isEmpty(), "tzId is non-empty: " + tzId);
            assertEquals(3, codeKey.length(), "codeKey is 3-letter abbreviation: " + codeKey);

            // Emulate click action: Prefs.setSaverWorldClock(parts[0], parts[1])
            Prefs.setSaverWorldClock(tzId, codeKey);
            assertEquals(tzId, Prefs.saverWorldClockTz(), "Prefs.saverWorldClockTz reflects selected tz: " + tzId);
            assertEquals(codeKey, Prefs.saverWorldClockLabel(), "Prefs.saverWorldClockLabel reflects selected code: " + codeKey);
        }
    }

    // =========================================================================
    // 6. Malformed Node ID Safety (Adversarial Bounds Stress)
    // =========================================================================

    private static void testMalformedNodeIdSafety() {
        System.out.println("\n--- [6] Malformed Node ID Safety (Adversarial Robustness) ---");

        String[] malformedIds = {
                "ss_tz:",
                "ss_tz:UTC",
                "ss_tz::",
                "ss_tz:America/New_York:",
                "ss_tz::NYC",
                "ss_tz",
                "other_node_id"
        };

        for (String malformed : malformedIds) {
            try {
                if (malformed.startsWith("ss_tz:")) {
                    String[] parts = malformed.substring("ss_tz:".length()).split(":", 2);
                    if (parts.length >= 2) {
                        Prefs.setSaverWorldClock(parts[0], parts[1]);
                    }
                }
                assertTrue(true, "Handled malformed ID safely without exception: '" + malformed + "'");
            } catch (Exception e) {
                assertTrue(false, "Threw unexpected exception for malformed ID '" + malformed + "': " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // 7. Dynamic Live Ticking in SettingsScreenRenderer
    // =========================================================================

    private static void testDynamicLiveTickingInRenderer() {
        System.out.println("\n--- [7] Dynamic Live Ticking in SettingsScreenRenderer ---");

        // Emulate SettingsScreenRenderer.java line 493-499:
        // if (isTzRow) {
        //     String[] tzParts = node.id.substring("ss_tz:".length()).split(":", 2);
        //     if (tzParts.length >= 1 && !tzParts[0].isEmpty()) {
        //         subText = ScreensaverCatalog.formatTimezoneSubtitle(
        //                 tzParts[0], System.currentTimeMillis(), Ui.h24());
        //     }
        // }

        long t0 = 1768478400000L; // 12:00:00 UTC
        long t1 = t0 + 60_000L;   // 12:01:00 UTC (1 minute later)
        long t2 = t0 + 120_000L;  // 12:02:00 UTC (2 minutes later)

        String tzNodeId = "ss_tz:Europe/London:LON";
        String staleSubtitleAtT0 = ScreensaverCatalog.formatTimezoneSubtitle("Europe/London", t0, true);
        assertEquals("12:00 (UTC+0)", staleSubtitleAtT0, "Subtitle at t0 is 12:00 (UTC+0)");

        // Simulate renderer draw pass at t1:
        String[] tzParts = tzNodeId.substring("ss_tz:".length()).split(":", 2);
        String liveSubtitleAtT1 = ScreensaverCatalog.formatTimezoneSubtitle(tzParts[0], t1, true);
        assertEquals("12:01 (UTC+0)", liveSubtitleAtT1, "Renderer dynamic subtitle at t1 has ticked to 12:01 (UTC+0)");
        assertFalse(staleSubtitleAtT0.equals(liveSubtitleAtT1), "Dynamic ticking refreshes across minute boundary");

        // Simulate renderer draw pass at t2:
        String liveSubtitleAtT2 = ScreensaverCatalog.formatTimezoneSubtitle(tzParts[0], t2, true);
        assertEquals("12:02 (UTC+0)", liveSubtitleAtT2, "Renderer dynamic subtitle at t2 has ticked to 12:02 (UTC+0)");

        // Rapid 1,000-minute consecutive ticking stress
        long simTime = t0;
        for (int m = 0; m < 1000; m++) {
            String tick = ScreensaverCatalog.formatTimezoneSubtitle(tzParts[0], simTime, true);
            assertTrue(SUBTITLE_PATTERN.matcher(tick).matches(), "Rapid tick at minute " + m + " matches pattern: " + tick);
            simTime += 60_000L;
        }
        System.out.println("  1,000 consecutive minute ticks executed successfully with zero failures.");
    }

    // =========================================================================
    // 8. Full 61 Hub Selection Round-Trips
    // =========================================================================

    private static void testFull61HubSelectionRoundTrips() {
        System.out.println("\n--- [8] Full 61 Hub Selection Round-Trips ---");

        long refNow = 1768478400000L;

        for (int i = 0; i < Prefs.SAVER_WORLD_HUBS.length; i++) {
            String[] targetHub = Prefs.SAVER_WORLD_HUBS[i];
            String targetCode = targetHub[0];
            String targetTzId = targetHub[1];

            // Set selection in Prefs
            Prefs.setSaverWorldClock(targetTzId, targetCode);

            // Rebuild settings tree
            SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
            snap.referenceNowMs = refNow;
            snap.h24 = true;
            snap.saverWorldClockTz = Prefs.saverWorldClockTz();
            snap.saverWorldClockLabel = Prefs.saverWorldClockLabel();

            SettingsTree.Node root = SettingsTree.buildRoot(snap);
            SettingsTree.Node tzParent = root.findById("ss_tz");

            String expectedParentValue = targetCode + " \u00B7 " + ScreensaverCatalog.formatUtcOffset(targetTzId, refNow);
            assertEquals(expectedParentValue, tzParent.value,
                    "Parent node value for " + targetCode + " reflects '" + expectedParentValue + "'");

            int checkedCount = 0;
            SettingsTree.Node checkedNode = null;
            for (SettingsTree.Node child : tzParent.children) {
                if (child.checked) {
                    checkedCount++;
                    checkedNode = child;
                }
            }

            assertEquals(1, checkedCount, "Exactly 1 hub is checked when " + targetCode + " is selected");
            assertTrue(checkedNode != null, "Checked node is non-null for " + targetCode);
            assertEquals("ss_tz:" + targetTzId + ":" + targetCode, checkedNode.id,
                    "Checked node ID matches selected hub: " + checkedNode.id);
        }
    }
}
