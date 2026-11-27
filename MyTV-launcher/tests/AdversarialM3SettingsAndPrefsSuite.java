package io.mytv;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Empirical Adversarial Challenge Test Suite for Milestone 3 (Multi-Category Settings & Preferences):
 * Validates Minimum 1 Category Invariant, Preference Persistence & Serialization,
 * Backward Compatibility, Category Icon Disjointness & Supported Symbols,
 * ScreensaverCatalog Normalization, and SettingsTree Checkbox Model.
 *
 * Authored by Challenger M3-1.
 */
public final class AdversarialM3SettingsAndPrefsSuite {

    private static int assertions = 0;
    private static int failedAssertions = 0;
    private static final List<String> criticalDefects = new ArrayList<>();

    private AdversarialM3SettingsAndPrefsSuite() {}

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

    public static void main(String[] args) throws Exception {
        System.out.println("=== STARTING ADVERSARIAL M3 SETTINGS & PREFERENCES SUITE ===");
        long start = System.currentTimeMillis();

        testSection1_MinimumOneCategoryConstraint();
        testSection2_PreferencePersistenceAndSerialization();
        testSection3_IconDisjointnessAndSupportedSymbols();
        testSection4_ScreensaverCatalogCategoryNormalization();
        testSection5_SettingsTreeCheckboxNodeRepresentation();

        long duration = System.currentTimeMillis() - start;
        System.out.println("\n=== ADVERSARIAL M3 SETTINGS & PREFERENCES SUMMARY ===");
        System.out.println("Total Assertions Executed: " + assertions);
        System.out.println("Failed Assertions: " + failedAssertions);
        System.out.println("Critical Defects Identified: " + criticalDefects.size());
        for (String d : criticalDefects) {
            System.err.println("  * " + d);
        }
        System.out.println("Execution Time: " + duration + " ms");

        if (failedAssertions > 0) {
            System.err.println("ADVERSARIAL SUITE RESULT: REJECT (" + failedAssertions + " failures)");
            System.exit(1);
        } else {
            System.out.println("ADVERSARIAL SUITE RESULT: APPROVE");
        }
    }

    // =========================================================================
    // SECTION 1: Minimum 1 Category Constraint & Concurrency Stress
    // =========================================================================
    private static void testSection1_MinimumOneCategoryConstraint() throws Exception {
        System.out.println("\n--- [Section 1] Minimum 1 Category Constraint & Invariant Preservation ---");

        // Reset prefs to clean slate
        Prefs.setSaverCategories(null);
        Set<String> initial = Prefs.saverCategories();
        assertEquals(6, initial.size(), "1.1: Default active categories set has all 6 core categories");

        // 1.2 Sequential deselect to 1 remaining category ('art')
        // Initial: [nature, urban, macro, space, paintings, art]
        assertTrue(Prefs.toggleSaverCategory("nature"), "1.2: Toggle nature off succeeds");
        assertEquals(5, Prefs.saverCategories().size(), "1.3: 5 categories remain active");
        assertFalse(Prefs.saverCategories().contains("nature"), "1.4: Nature is no longer active");

        assertTrue(Prefs.toggleSaverCategory("urban"), "1.5: Toggle urban off succeeds");
        assertEquals(4, Prefs.saverCategories().size(), "1.6: 4 categories remain active");

        assertTrue(Prefs.toggleSaverCategory("macro"), "1.7: Toggle macro off succeeds");
        assertEquals(3, Prefs.saverCategories().size(), "1.8: 3 categories remain active");

        assertTrue(Prefs.toggleSaverCategory("space"), "1.9: Toggle space off succeeds");
        assertEquals(2, Prefs.saverCategories().size(), "1.10: 2 categories remain active");

        assertTrue(Prefs.toggleSaverCategory("paintings"), "1.11: Toggle paintings off succeeds");
        assertEquals(1, Prefs.saverCategories().size(), "1.12: Exactly 1 category remains active");
        assertTrue(Prefs.saverCategories().contains("art"), "1.13: Last remaining category is 'art'");
        assertEquals("art", Prefs.saverCategory(), "1.14: Legacy saverCategory() returns 'art'");

        // CRITICAL INVARIANT: Attempting to uncheck the ONLY remaining category MUST be rejected!
        boolean toggledLast = Prefs.toggleSaverCategory("art");
        assertFalse(toggledLast, "1.15: CRITICAL: Toggling off the only remaining active category returns false");
        assertEquals(1, Prefs.saverCategories().size(), "1.16: Active categories set still has size 1");
        assertTrue(Prefs.saverCategories().contains("art"), "1.17: 'art' remains active despite deselect attempt");
        assertEquals("art", Prefs.saverCategory(), "1.18: Legacy saverCategory() still returns 'art'");

        // 1.3 Permutation test across all 6 categories
        // Verify for EVERY category that when it is alone, it cannot be deselected
        for (String cat : Prefs.SAVER_CATEGORIES) {
            Prefs.setSaverCategories(new LinkedHashSet<>(Collections.singletonList(cat)));
            assertEquals(1, Prefs.saverCategories().size(), "1.19: Set single category " + cat);
            boolean rejected = Prefs.toggleSaverCategory(cat);
            assertFalse(rejected, "1.20: Deselecting sole category '" + cat + "' rejected (returns false)");
            Set<String> surviving = Prefs.saverCategories();
            assertEquals(1, surviving.size(), "1.21: Size strictly preserved as 1 for " + cat);
            assertTrue(surviving.contains(cat), "1.22: Sole category '" + cat + "' survived rejection");
            assertEquals(cat, Prefs.saverCategory(), "1.23: saverCategory() matches sole surviving category");
        }

        // 1.4 Invalid / Null / Unknown category inputs to toggleSaverCategory
        assertFalse(Prefs.toggleSaverCategory(null), "1.24: toggleSaverCategory(null) returns false");
        assertFalse(Prefs.toggleSaverCategory(""), "1.25: toggleSaverCategory('') returns false");
        assertFalse(Prefs.toggleSaverCategory("   "), "1.26: toggleSaverCategory('   ') returns false");
        assertFalse(Prefs.toggleSaverCategory("alien_category"), "1.27: toggleSaverCategory('alien_category') returns false");
        assertFalse(Prefs.toggleSaverCategory("mixed"), "1.28: toggleSaverCategory('mixed') returns false");
        assertFalse(Prefs.toggleSaverCategory("wildlife"), "1.29: toggleSaverCategory('wildlife') returns false (not in CORE 6)");

        // 1.5 Rapid & Concurrent Toggle Operations (Concurrency Race Resilience)
        System.out.println("  -> Running 32-thread concurrent hammer on toggleSaverCategory()...");
        Prefs.setSaverCategories(null); // start from all 6
        final int THREADS = 32;
        final int OPS_PER_THREAD = 200;
        final ExecutorService exec = Executors.newFixedThreadPool(THREADS);
        final CountDownLatch latch = new CountDownLatch(THREADS);
        final AtomicInteger errors = new AtomicInteger(0);
        final AtomicBoolean emptyObserved = new AtomicBoolean(false);

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try {
                    String[] cats = Prefs.SAVER_CATEGORIES;
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        String targetCat = cats[(threadId + i) % cats.length];
                        Prefs.toggleSaverCategory(targetCat);
                        Set<String> current = Prefs.saverCategories();
                        if (current == null || current.isEmpty()) {
                            emptyObserved.set(true);
                        }
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        exec.shutdownNow();
        assertTrue(finished, "1.30: All 32 hammer threads completed within 10s");
        assertEquals(0, errors.get(), "1.31: Zero exceptions encountered during 32-thread concurrent hammer");
        assertFalse(emptyObserved.get(), "1.32: CRITICAL: saverCategories() was NEVER empty during concurrent race");
        Set<String> finalSet = Prefs.saverCategories();
        assertTrue(finalSet != null && finalSet.size() >= 1 && finalSet.size() <= 6,
                "1.33: Final active categories size is bounded between 1 and 6 (was " + (finalSet != null ? finalSet.size() : 0) + ")");
        for (String c : finalSet) {
            assertTrue(Arrays.asList(Prefs.SAVER_CATEGORIES).contains(c),
                    "1.34: Surviving category '" + c + "' is a valid core category");
        }
        assertTrue(Prefs.saverCategory() != null && !Prefs.saverCategory().isEmpty(),
                "1.35: Final legacy saverCategory() is valid non-empty string");
    }

    // =========================================================================
    // SECTION 2: Preference Persistence & Serialization Robustness
    // =========================================================================
    private static void testSection2_PreferencePersistenceAndSerialization() {
        System.out.println("\n--- [Section 2] Preference Persistence & Serialization Robustness ---");

        // 2.1 Serialization format
        Set<String> subset = new LinkedHashSet<>(Arrays.asList("nature", "space", "art"));
        Prefs.setSaverCategories(subset);
        Set<String> readBack = Prefs.saverCategories();
        assertEquals(3, readBack.size(), "2.1: saverCategories() matches 3 set elements");
        assertTrue(readBack.contains("nature") && readBack.contains("space") && readBack.contains("art"),
                "2.2: saverCategories() preserves exact categories");

        // Verify deterministic iteration order preservation
        List<String> order = new ArrayList<>(readBack);
        assertEquals("nature", order.get(0), "2.3: Preserves insertion order nature at 0");
        assertEquals("space", order.get(1), "2.4: Preserves insertion order space at 1");
        assertEquals("art", order.get(2), "2.5: Preserves insertion order art at 2");

        // 2.2 Recovery and fallback under corrupted SharedPreferences strings
        // Simulate corrupted strings directly via low-level reflection or setSaverCategories invalid payloads

        // Test with empty set passed to setSaverCategories
        Prefs.setSaverCategories(Collections.emptySet());
        assertEquals(6, Prefs.saverCategories().size(), "2.6: setSaverCategories(empty) falls back to all 6 categories");

        // Test with null set passed to setSaverCategories
        Prefs.setSaverCategories(null);
        assertEquals(6, Prefs.saverCategories().size(), "2.7: setSaverCategories(null) falls back to all 6 categories");

        // Test with set containing only null elements
        Prefs.setSaverCategories(Collections.singleton(null));
        assertEquals(6, Prefs.saverCategories().size(), "2.8: setSaverCategories({null}) falls back to all 6 categories");

        // Test with set containing only invalid category names
        Set<String> bogusSet = new HashSet<>(Arrays.asList("bogus1", "fake2", "corrupt3"));
        Prefs.setSaverCategories(bogusSet);
        assertEquals(6, Prefs.saverCategories().size(), "2.9: setSaverCategories(bogus) falls back to all 6 categories");

        // Test with set containing mixed valid and invalid
        Set<String> mixedValidBogus = new LinkedHashSet<>(Arrays.asList("space", "bogus_entry", "paintings", "   ", null));
        Prefs.setSaverCategories(mixedValidBogus);
        Set<String> recovered = Prefs.saverCategories();
        assertEquals(2, recovered.size(), "2.10: Recovers exactly 2 valid categories from mixed bogus set");
        assertTrue(recovered.contains("space"), "2.11: Contains valid category 'space'");
        assertTrue(recovered.contains("paintings"), "2.12: Contains valid category 'paintings'");
        assertFalse(recovered.contains("bogus_entry"), "2.13: Discarded invalid 'bogus_entry'");

        // Test with whitespace and weird casing in setSaverCategories
        Set<String> weirdCasing = new LinkedHashSet<>(Arrays.asList("  SpAcE  ", "   uRbAn\t\n", "pAiNtInGs"));
        Prefs.setSaverCategories(weirdCasing);
        Set<String> normalizedCasing = Prefs.saverCategories();
        assertEquals(3, normalizedCasing.size(), "2.14: Correctly trimmed and lowercased 3 entries");
        assertTrue(normalizedCasing.contains("space"), "2.15: Contains normalized 'space'");
        assertTrue(normalizedCasing.contains("urban"), "2.16: Contains normalized 'urban'");
        assertTrue(normalizedCasing.contains("paintings"), "2.17: Contains normalized 'paintings'");

        // 2.3 Substring collision rejection
        // Ensure "nature1" or "art_fake" is NOT accepted because it starts with "nature" or "art"
        Set<String> substringCollisions = new HashSet<>(Arrays.asList("nature1", "art_fake", "paint", "mac", "urban_2", "spacetrek"));
        Prefs.setSaverCategories(substringCollisions);
        assertEquals(6, Prefs.saverCategories().size(), "2.18: Substring collision tokens cleanly rejected, falling back to all 6");

        // 2.4 Backward compatibility when legacy "saverCategory" is used
        // When saverCategories is set via legacy setSaverCategory()
        Prefs.setSaverCategory("space");
        assertEquals(1, Prefs.saverCategories().size(), "2.19: Legacy setSaverCategory('space') sets active set to size 1");
        assertTrue(Prefs.saverCategories().contains("space"), "2.20: Active set contains 'space'");
        assertEquals("space", Prefs.saverCategory(), "2.21: Legacy saverCategory() returns 'space'");

        Prefs.setSaverCategory("paintings");
        assertEquals(1, Prefs.saverCategories().size(), "2.22: Legacy setSaverCategory('paintings') sets active set to size 1");
        assertTrue(Prefs.saverCategories().contains("paintings"), "2.23: Active set contains 'paintings'");

        // Legacy "mixed" MUST enable all 6 categories!
        Prefs.setSaverCategory("mixed");
        assertEquals(6, Prefs.saverCategories().size(), "2.24: Legacy setSaverCategory('mixed') activates all 6 categories");

        // Case-insensitive legacy "MIXED"
        Prefs.setSaverCategory("MIXED");
        assertEquals(6, Prefs.saverCategories().size(), "2.25: Legacy setSaverCategory('MIXED') uppercase activates all 6 categories");

        // Legacy null or empty
        Prefs.setSaverCategory(null);
        assertEquals(6, Prefs.saverCategories().size(), "2.26: Legacy setSaverCategory(null) activates all 6 categories");

        Prefs.setSaverCategory("");
        assertEquals(6, Prefs.saverCategories().size(), "2.27: Legacy setSaverCategory('') activates all 6 categories");

        // Legacy invalid category
        Prefs.setSaverCategory("macro");
        Prefs.setSaverCategory("invalid_galaxy"); // rejected, leaves prior valid state intact
        assertEquals(1, Prefs.saverCategories().size(), "2.28: Legacy setSaverCategory with invalid string ignored");
        assertTrue(Prefs.saverCategories().contains("macro"), "2.29: Prior valid state 'macro' preserved");

        // 2.5 Context overload compatibility
        Set<String> ctxCategories = Prefs.saverCategories(null);
        assertEquals(Prefs.saverCategories().size(), ctxCategories.size(), "2.30: saverCategories(Context) matches saverCategories()");
    }

    // =========================================================================
    // SECTION 3: Icon Disjointness & Supported Symbols
    // =========================================================================
    private static void testSection3_IconDisjointnessAndSupportedSymbols() {
        System.out.println("\n--- [Section 3] Category Icon Disjointness & Supported Symbols ---");

        // 3.1 All 6 categories in Prefs.SAVER_CATEGORIES
        String[] expectedCats = {"nature", "urban", "macro", "space", "paintings", "art"};
        assertEquals(6, Prefs.SAVER_CATEGORIES.length, "3.1: Prefs.SAVER_CATEGORIES has exactly 6 categories");
        for (String expected : expectedCats) {
            boolean found = false;
            for (String cat : Prefs.SAVER_CATEGORIES) {
                if (expected.equals(cat)) { found = true; break; }
            }
            assertTrue(found, "3.2: Contains canonical category '" + expected + "'");
        }

        // 3.2 100% Unique Disjoint Icons
        Set<String> uniqueIcons = new HashSet<>();
        for (String cat : Prefs.SAVER_CATEGORIES) {
            String icon = SettingsTree.iconForGalleryCategory(cat);
            assertTrue(icon != null && !icon.isEmpty(), "3.3: Icon for '" + cat + "' is non-empty (" + icon + ")");
            assertFalse(uniqueIcons.contains(icon), "3.4: Icon '" + icon + "' for category '" + cat + "' is 100% unique (no duplicates)");
            uniqueIcons.add(icon);
        }
        assertEquals(6, uniqueIcons.size(), "3.5: All 6 categories have completely disjoint icons: " + uniqueIcons);

        // 3.3 Explicit Icon Mapping Checks
        assertEquals("gallery_nature", SettingsTree.iconForGalleryCategory("nature"), "3.6: nature -> gallery_nature");
        assertEquals("gallery_urban", SettingsTree.iconForGalleryCategory("urban"), "3.7: urban -> gallery_urban");
        assertEquals("gallery_macro", SettingsTree.iconForGalleryCategory("macro"), "3.8: macro -> gallery_macro");
        assertEquals("sparkles", SettingsTree.iconForGalleryCategory("space"), "3.9: space -> sparkles");
        assertEquals("palette", SettingsTree.iconForGalleryCategory("paintings"), "3.10: paintings -> palette");
        assertEquals("star", SettingsTree.iconForGalleryCategory("art"), "3.11: art -> star");

        // Fallbacks
        assertEquals("gallery_mixed", SettingsTree.iconForGalleryCategory(null), "3.12: null -> gallery_mixed");
        assertEquals("gallery_mixed", SettingsTree.iconForGalleryCategory("mixed"), "3.13: mixed -> gallery_mixed");
        assertEquals("gallery_mixed", SettingsTree.iconForGalleryCategory("unknown_category"), "3.14: unknown -> gallery_mixed");

        // 3.4 Supported Icons in Symbols
        for (String cat : Prefs.SAVER_CATEGORIES) {
            String icon = SettingsTree.iconForGalleryCategory(cat);
            assertTrue(Symbols.isSupportedIcon(icon), "3.15: Icon '" + icon + "' for '" + cat + "' is verified supported in Symbols");
        }

        // Procedural & Asset Verification
        assertTrue(Symbols.isSupportedIcon("gallery_nature"), "3.16: gallery_nature is supported procedural icon");
        assertTrue(Symbols.isSupportedIcon("gallery_urban"), "3.17: gallery_urban is supported procedural icon");
        assertTrue(Symbols.isSupportedIcon("gallery_macro"), "3.18: gallery_macro is supported procedural icon");
        assertTrue(Symbols.isSupportedIcon("sparkles"), "3.19: sparkles is supported PNG asset icon");
        assertTrue(Symbols.isSupportedIcon("palette"), "3.20: palette is supported procedural icon");
        assertTrue(Symbols.isSupportedIcon("star"), "3.21: star is supported procedural icon");

        // 3.5 Parent ss_category icon behavior in SettingsTree.buildScreensaverNode
        // When snap.saverCategory is "nature" or all 6 active -> returns "gallery_nature"
        SettingsTree.StateSnapshot snapAll = new SettingsTree.StateSnapshot();
        snapAll.saverCategories = new LinkedHashSet<>(Arrays.asList(Prefs.SAVER_CATEGORIES));
        snapAll.saverCategory = "nature";
        SettingsTree.Node ssParent = SettingsTree.buildRoot(snapAll).findById("screensaver");
        SettingsTree.Node catParent = ssParent.findById("ss_category");
        assertTrue(catParent != null, "3.22: ss_category parent node found");
        assertEquals("gallery_nature", catParent.icon, "3.23: Parent ss_category icon is 'gallery_nature' when all 6 active");

        // When single category is active, parent icon matches that category's icon
        for (String cat : Prefs.SAVER_CATEGORIES) {
            SettingsTree.StateSnapshot snapSingle = new SettingsTree.StateSnapshot();
            snapSingle.saverCategories = new LinkedHashSet<>(Collections.singletonList(cat));
            snapSingle.saverCategory = cat;
            SettingsTree.Node parentNode = SettingsTree.buildRoot(snapSingle).findById("screensaver");
            SettingsTree.Node catNode = parentNode.findById("ss_category");
            String expectedIcon = SettingsTree.iconForGalleryCategory(cat);
            assertEquals(expectedIcon, catNode.icon, "3.24: Parent ss_category icon matches single category '" + cat + "' -> " + expectedIcon);
        }
    }

    // =========================================================================
    // SECTION 4: ScreensaverCatalog Category Normalization
    // =========================================================================
    private static void testSection4_ScreensaverCatalogCategoryNormalization() {
        System.out.println("\n--- [Section 4] ScreensaverCatalog Category Normalization ---");

        // 4.1 Null category set -> returns all 1,230 entries
        List<ScreensaverCatalog.PhotoEntry> allNull = ScreensaverCatalog.catalogForCategories(null, null);
        assertEquals(1230, allNull.size(), "4.1: Passing null categories set returns all 1,230 entries");

        // 4.2 Empty category set -> returns all 1,230 entries
        List<ScreensaverCatalog.PhotoEntry> allEmpty = ScreensaverCatalog.catalogForCategories(null, Collections.emptySet());
        assertEquals(1230, allEmpty.size(), "4.2: Passing empty categories set returns all 1,230 entries");

        // 4.3 Whitespace string elements only -> returns all 1,230 entries
        Set<String> whitespaceOnly = new HashSet<>(Arrays.asList("   ", "\t", "\n", ""));
        List<ScreensaverCatalog.PhotoEntry> allWs = ScreensaverCatalog.catalogForCategories(null, whitespaceOnly);
        assertEquals(1230, allWs.size(), "4.3: Passing whitespace-only strings returns all 1,230 entries");

        // 4.4 Set containing "mixed" -> returns all 1,230 entries
        Set<String> mixedSet = new HashSet<>(Arrays.asList("mixed", "space"));
        List<ScreensaverCatalog.PhotoEntry> allMixed = ScreensaverCatalog.catalogForCategories(null, mixedSet);
        assertEquals(1230, allMixed.size(), "4.4: Passing set containing 'mixed' returns all 1,230 entries");

        // 4.5 Mixed-case normalization
        List<ScreensaverCatalog.PhotoEntry> spaceMixed = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("SpAcE"));
        assertEquals(205, spaceMixed.size(), "4.5: Query 'SpAcE' mixed-case returns exactly 205 entries");
        for (ScreensaverCatalog.PhotoEntry pe : spaceMixed) {
            assertEquals("space", pe.category.toLowerCase(Locale.ROOT), "4.6: Every returned entry is 'space'");
        }

        List<ScreensaverCatalog.PhotoEntry> artMixed = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("  ART  "));
        assertEquals(205, artMixed.size(), "4.7: Query '  ART  ' padded with spaces returns exactly 205 entries");
        for (ScreensaverCatalog.PhotoEntry pe : artMixed) {
            assertEquals("art", pe.category.toLowerCase(Locale.ROOT), "4.8: Every returned entry is 'art'");
        }

        List<ScreensaverCatalog.PhotoEntry> paintingsTab = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("\tpaintings\n"));
        assertEquals(205, paintingsTab.size(), "4.9: Query '\\tpaintings\\n' returns exactly 205 entries");
        for (ScreensaverCatalog.PhotoEntry pe : paintingsTab) {
            assertEquals("paintings", pe.category.toLowerCase(Locale.ROOT), "4.10: Every returned entry is 'paintings'");
        }

        // 4.6 Multi-category combination queries
        Set<String> pair = new HashSet<>(Arrays.asList("  NATURE  ", "space"));
        List<ScreensaverCatalog.PhotoEntry> pairPicks = ScreensaverCatalog.catalogForCategories(null, pair);
        assertEquals(410, pairPicks.size(), "4.11: Query 'nature' + 'space' returns 410 entries (205 + 205)");

        Set<String> triplet = new HashSet<>(Arrays.asList("urban", "macro", "art"));
        List<ScreensaverCatalog.PhotoEntry> tripletPicks = ScreensaverCatalog.catalogForCategories(null, triplet);
        assertEquals(615, tripletPicks.size(), "4.12: Query 'urban' + 'macro' + 'art' returns 615 entries (205 * 3)");

        // 4.7 Nonexistent category returns empty list without throwing NPE
        List<ScreensaverCatalog.PhotoEntry> unknownPicks = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("atlantis_galaxy"));
        assertEquals(0, unknownPicks.size(), "4.13: Unknown category returns 0 entries cleanly without NPE");

        // 4.8 sampleEntryForCategory verification across all categories
        for (String cat : Prefs.SAVER_CATEGORIES) {
            ScreensaverCatalog.PhotoEntry sample = ScreensaverCatalog.sampleEntryForCategory(cat);
            assertTrue(sample != null, "4.14: Sample entry for " + cat + " is non-null");
            assertTrue(sample.title != null && !sample.title.isEmpty(), "4.15: Sample title for " + cat + " is non-empty");
            assertTrue(sample.location != null, "4.16: Sample location for " + cat + " is non-null");
            if ("nature".equals(cat) || "urban".equals(cat)) {
                assertTrue(!sample.location.isEmpty(), "4.17: Geographic photo category " + cat + " has non-empty location (" + sample.location + ")");
            } else {
                assertTrue(sample.location.isEmpty(), "4.17: Subject category " + cat + " uses subject title instead of geographic location");
            }
        }
    }

    // =========================================================================
    // SECTION 5: SettingsTree Checkbox Node Representation
    // =========================================================================
    private static void testSection5_SettingsTreeCheckboxNodeRepresentation() {
        System.out.println("\n--- [Section 5] SettingsTree Checkbox Node Representation ---");

        // 5.1 When all 6 categories are active
        SettingsTree.StateSnapshot snapAll = new SettingsTree.StateSnapshot();
        snapAll.saverCategories = new LinkedHashSet<>(Arrays.asList(Prefs.SAVER_CATEGORIES));
        snapAll.saverCategory = "nature";
        SettingsTree.Node ssParent = SettingsTree.buildRoot(snapAll).findById("screensaver");
        SettingsTree.Node catParent = ssParent.findById("ss_category");
        assertTrue(catParent != null, "5.1: ss_category node exists");
        assertEquals("All Categories", catParent.value, "5.2: Parent row value is 'All Categories' when all 6 active");
        assertEquals(6, catParent.children.size(), "5.3: ss_category has exactly 6 child nodes");

        for (String cat : Prefs.SAVER_CATEGORIES) {
            SettingsTree.Node child = catParent.findById("ss_cat:" + cat);
            assertTrue(child != null, "5.4: Child node exists for " + cat);
            assertTrue(child.checked, "5.5: Child checkbox for " + cat + " is checked");
            assertEquals("Active", child.value, "5.6: Child value is 'Active'");
            assertEquals(SettingsTree.iconForGalleryCategory(cat), child.icon, "5.7: Child icon matches category icon");
        }

        // 5.2 When only 1 category is active ('space')
        SettingsTree.StateSnapshot snapSpace = new SettingsTree.StateSnapshot();
        snapSpace.saverCategories = new LinkedHashSet<>(Collections.singletonList("space"));
        snapSpace.saverCategory = "space";
        SettingsTree.Node ssSpace = SettingsTree.buildRoot(snapSpace).findById("screensaver");
        SettingsTree.Node catSpace = ssSpace.findById("ss_category");
        assertEquals("Space", catSpace.value, "5.8: Parent summary shows capitalized 'Space' when 1 active");
        assertEquals("sparkles", catSpace.icon, "5.9: Parent icon is 'sparkles'");

        for (String cat : Prefs.SAVER_CATEGORIES) {
            SettingsTree.Node child = catSpace.findById("ss_cat:" + cat);
            if ("space".equals(cat)) {
                assertTrue(child.checked, "5.10: space child is checked");
                assertEquals("Active", child.value, "5.11: space child value is 'Active'");
            } else {
                assertFalse(child.checked, "5.12: " + cat + " child is unchecked");
                assertEquals("", child.value, "5.13: " + cat + " child value is empty");
            }
        }

        // 5.3 When 3 categories are active ('nature', 'macro', 'art')
        SettingsTree.StateSnapshot snapThree = new SettingsTree.StateSnapshot();
        snapThree.saverCategories = new LinkedHashSet<>(Arrays.asList("nature", "macro", "art"));
        snapThree.saverCategory = "nature";
        SettingsTree.Node ssThree = SettingsTree.buildRoot(snapThree).findById("screensaver");
        SettingsTree.Node catThree = ssThree.findById("ss_category");
        assertEquals("3 Active", catThree.value, "5.14: Parent summary shows '3 Active'");

        int checkedCount = 0;
        for (String cat : Prefs.SAVER_CATEGORIES) {
            SettingsTree.Node child = catThree.findById("ss_cat:" + cat);
            if (child.checked) checkedCount++;
        }
        assertEquals(3, checkedCount, "5.15: Exactly 3 child checkboxes are checked");
    }
}
