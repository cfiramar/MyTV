package io.soartv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Empirical Adversarial Challenge Test Suite for Milestone 2 Remediation Recheck:
 * 4K Galleries Catalog & Sourcing Engine (Challenger M2 Recheck 1).
 *
 * <p>Validates:
 * 1. Headless desktop JVM catalog ingestion in {@link ScreensaverCatalog#loadCatalog(android.content.Context)}:
 *    - Must load all 1,230 curated True 4K UHD entries with zero Stub! exceptions.
 *    - All 6 categories (nature, urban, macro, space, paintings, art) have >= 200 items (205 each).
 *    - Immutability and isolation of the returned catalog list.
 * 2. Case-insensitivity & whitespace trimming in {@link ScreensaverCatalog#catalogForCategories(android.content.Context, Set)}:
 *    - Mixed casing ("Space", "sPaCe", "SPACE", "PAINTINGS", "  art  ").
 *    - Trimming of leading/trailing whitespace and control characters ("\tmacro\n").
 *    - Multi-category combinations with mixed casing.
 *    - Boundary sets: null, empty, "mixed" in uppercase/mixed-case, sets containing null/empty elements.
 * 3. Thread-safety and concurrency stress under 25 threads / 2,500 simultaneous operations.
 * 4. LRU disk cache bounds enforcement (200MB limit with 85% headroom eviction).
 * 5. R3 caption engine rules (art/space title only vs photo location only).
 */
public final class AdversarialM2RecheckSuite {

    private static int assertions = 0;
    private static int failures = 0;

    private AdversarialM2RecheckSuite() {}

    private static void assertTrue(boolean condition, String message) {
        assertions++;
        if (!condition) {
            failures++;
            System.err.println("  [FAIL] " + message);
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
            failures++;
            System.err.println("  [FAIL] " + message + " | expected=" + expected + ", actual=" + actual);
        } else {
            System.out.println("  [PASS] " + message);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("======================================================================");
        System.out.println("=== STARTING ADVERSARIAL M2 RECHECK 1 EMPIRICAL STRESS TEST SUITE ===");
        System.out.println("======================================================================");
        long start = System.currentTimeMillis();

        testDesktopJvmCatalogIngestion();
        testCategoryDistributionAndIntegrity();
        testCaseInsensitiveCategoryFiltering();
        testEdgeAndBoundaryCategorySets();
        testCatalogImmutabilityAndDefensiveCopying();
        testConcurrentCategoryQueriesHammer();
        testLruDiskCacheBoundsStress();
        testR3CaptionEngineContracts();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("\n======================================================================");
        System.out.println("=== ADVERSARIAL M2 RECHECK 1 SUMMARY ===");
        System.out.println("Total Assertions Executed: " + assertions);
        System.out.println("Failed Assertions: " + failures);
        System.out.println("Execution Time: " + elapsed + " ms");

        if (failures > 0) {
            System.err.println("VERDICT: REJECT (" + failures + " failures detected)");
            System.exit(1);
        } else {
            System.out.println("VERDICT: APPROVE (100% assertions passed)");
        }
    }

    /**
     * Requirement 1: ScreensaverCatalog.loadCatalog(Context) on headless desktop JVM.
     */
    private static void testDesktopJvmCatalogIngestion() {
        System.out.println("\n--- [Challenge 1] Desktop JVM Catalog Ingestion (loadCatalog) ---");

        List<ScreensaverCatalog.PhotoEntry> catalog = null;
        Throwable error = null;
        try {
            catalog = ScreensaverCatalog.loadCatalog(null);
        } catch (Throwable t) {
            error = t;
        }

        assertTrue(error == null, "1.1: loadCatalog(null) executed with zero exceptions (error=" + error + ")");
        assertTrue(catalog != null, "1.2: loadCatalog(null) returned non-null list");
        assertTrue(catalog != null && catalog.size() >= 1200, "1.3: loadCatalog(null) returned >= 1200 entries (found " + (catalog != null ? catalog.size() : 0) + ")");
        assertEquals(1230, catalog != null ? catalog.size() : 0, "1.4: loadCatalog(null) returned exactly 1,230 curated 4K entries");

        // Second call should return cached instance in < 1ms
        long cacheStart = System.nanoTime();
        List<ScreensaverCatalog.PhotoEntry> cached = ScreensaverCatalog.loadCatalog(null);
        long cacheDurationUs = (System.nanoTime() - cacheStart) / 1000;
        assertEquals(1230, cached != null ? cached.size() : 0, "1.5: Subsequent loadCatalog(null) returned 1,230 entries");
        assertTrue(cacheDurationUs < 5000, "1.6: Cached retrieval took " + cacheDurationUs + " µs (< 5ms)");
    }

    /**
     * Requirement 1 & 2: Validate category distribution, uniqueness, and metadata hygiene.
     */
    private static void testCategoryDistributionAndIntegrity() {
        System.out.println("\n--- [Challenge 2] Category Distribution & Metadata Integrity ---");

        List<ScreensaverCatalog.PhotoEntry> catalog = ScreensaverCatalog.loadCatalog(null);
        Map<String, Integer> categoryCounts = new HashMap<>();
        Set<String> uniqueIds = new HashSet<>();

        String[] expectedCategories = {"nature", "urban", "macro", "space", "paintings", "art"};
        for (String cat : expectedCategories) {
            categoryCounts.put(cat, 0);
        }

        for (ScreensaverCatalog.PhotoEntry pe : catalog) {
            assertTrue(pe.id != null && !pe.id.trim().isEmpty(), "2.1: Entry id is non-empty (" + pe.id + ")");
            uniqueIds.add(pe.id);

            assertTrue(pe.category != null && !pe.category.trim().isEmpty(), "2.2: Entry category is non-empty (id=" + pe.id + ")");
            String lowerCat = pe.category.toLowerCase(Locale.ROOT);
            assertTrue(categoryCounts.containsKey(lowerCat), "2.3: Category '" + lowerCat + "' is one of 6 canonical categories");
            categoryCounts.put(lowerCat, categoryCounts.get(lowerCat) + 1);

            assertTrue(pe.title != null && !pe.title.trim().isEmpty(), "2.4: Title is non-empty for id=" + pe.id);
            assertTrue(pe.imageUrl != null && pe.imageUrl.startsWith("https://"), "2.5: Image URL starts with https:// for id=" + pe.id);
            assertFalse(pe.imageUrl.contains("http://"), "2.6: Image URL contains no insecure http:// for id=" + pe.id);
            assertFalse(pe.title.contains("<") || pe.title.contains(">"), "2.7: Title contains no raw HTML tags for id=" + pe.id);
        }

        assertEquals(1230, uniqueIds.size(), "2.8: All 1,230 items have globally unique IDs");

        for (String cat : expectedCategories) {
            int count = categoryCounts.get(cat);
            assertTrue(count >= 200, "2.9: Category '" + cat + "' has >= 200 entries (found " + count + ")");
            assertEquals(205, count, "2.10: Category '" + cat + "' has exactly 205 entries");
        }
    }

    /**
     * Requirement 2: Case-insensitivity and whitespace trimming in catalogForCategories.
     */
    private static void testCaseInsensitiveCategoryFiltering() {
        System.out.println("\n--- [Challenge 3] Case-Insensitivity & Whitespace Trimming in catalogForCategories ---");

        // 3A: Single categories with standard lowercase
        List<ScreensaverCatalog.PhotoEntry> spaceLow = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("space"));
        assertEquals(205, spaceLow.size(), "3.1: Query 'space' returns exactly 205 items");

        List<ScreensaverCatalog.PhotoEntry> paintingsLow = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("paintings"));
        assertEquals(205, paintingsLow.size(), "3.2: Query 'paintings' returns exactly 205 items");

        List<ScreensaverCatalog.PhotoEntry> artLow = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("art"));
        assertEquals(205, artLow.size(), "3.3: Query 'art' returns exactly 205 items");

        // 3B: Mixed casing tests
        List<ScreensaverCatalog.PhotoEntry> spaceMixed1 = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("Space"));
        assertEquals(205, spaceMixed1.size(), "3.4: Query 'Space' returns exactly 205 items");

        List<ScreensaverCatalog.PhotoEntry> spaceMixed2 = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("sPaCe"));
        assertEquals(205, spaceMixed2.size(), "3.5: Query 'sPaCe' returns exactly 205 items");

        List<ScreensaverCatalog.PhotoEntry> spaceUpper = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("SPACE"));
        assertEquals(205, spaceUpper.size(), "3.6: Query 'SPACE' returns exactly 205 items");

        List<ScreensaverCatalog.PhotoEntry> paintingsUpper = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("PAINTINGS"));
        assertEquals(205, paintingsUpper.size(), "3.7: Query 'PAINTINGS' returns exactly 205 items");

        List<ScreensaverCatalog.PhotoEntry> paintingsTitleCase = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("Paintings"));
        assertEquals(205, paintingsTitleCase.size(), "3.8: Query 'Paintings' returns exactly 205 items");

        // 3C: Whitespace and control character trimming
        List<ScreensaverCatalog.PhotoEntry> artSpaced = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("  art  "));
        assertEquals(205, artSpaced.size(), "3.9: Query '  art  ' returns exactly 205 items");

        List<ScreensaverCatalog.PhotoEntry> macroTabbed = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("\tmacro\n"));
        assertEquals(205, macroTabbed.size(), "3.10: Query '\\tmacro\\n' returns exactly 205 items");

        List<ScreensaverCatalog.PhotoEntry> urbanSpacedUpper = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("   URBAN   "));
        assertEquals(205, urbanSpacedUpper.size(), "3.11: Query '   URBAN   ' returns exactly 205 items");

        List<ScreensaverCatalog.PhotoEntry> natureSpacedLower = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("  nature "));
        assertEquals(205, natureSpacedLower.size(), "3.12: Query '  nature ' returns exactly 205 items");

        // 3D: Combinatorial multi-category queries with mixed casing
        Set<String> spaceAndArt = new HashSet<>(Arrays.asList("SpAcE", "  ART  "));
        List<ScreensaverCatalog.PhotoEntry> pairFiltered = ScreensaverCatalog.catalogForCategories(null, spaceAndArt);
        assertEquals(410, pairFiltered.size(), "3.13: Query {'SpAcE', '  ART  '} returns 410 items (205 + 205)");

        Set<String> trio = new HashSet<>(Arrays.asList("  NATURE  ", "\tMacro", "URBAN\n"));
        List<ScreensaverCatalog.PhotoEntry> trioFiltered = ScreensaverCatalog.catalogForCategories(null, trio);
        assertEquals(615, trioFiltered.size(), "3.14: Query {'  NATURE  ', '\\tMacro', 'URBAN\\n'} returns 615 items (205 * 3)");

        Set<String> allSixMixed = new HashSet<>(Arrays.asList("Nature", "URBAN", "macro", "  sPaCe  ", "Paintings", "ART"));
        List<ScreensaverCatalog.PhotoEntry> allSixFiltered = ScreensaverCatalog.catalogForCategories(null, allSixMixed);
        assertEquals(1230, allSixFiltered.size(), "3.15: Query all 6 categories with mixed casing/whitespace returns all 1,230 items");
    }

    /**
     * Requirement 2: Boundary sets: null, empty, "mixed" variations, null/blank set items.
     */
    private static void testEdgeAndBoundaryCategorySets() {
        System.out.println("\n--- [Challenge 4] Boundary Sets & Corrupted Inputs ---");

        // Null set -> full catalog
        List<ScreensaverCatalog.PhotoEntry> nullSet = ScreensaverCatalog.catalogForCategories(null, null);
        assertEquals(1230, nullSet.size(), "4.1: Null category set returns full catalog (1230 items)");

        // Empty set -> full catalog
        List<ScreensaverCatalog.PhotoEntry> emptySet = ScreensaverCatalog.catalogForCategories(null, Collections.emptySet());
        assertEquals(1230, emptySet.size(), "4.2: Empty category set returns full catalog (1230 items)");

        // Set containing "mixed" in various casing
        List<ScreensaverCatalog.PhotoEntry> mixedLower = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("mixed"));
        assertEquals(1230, mixedLower.size(), "4.3: Set {'mixed'} returns full catalog (1230 items)");

        List<ScreensaverCatalog.PhotoEntry> mixedTitle = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("Mixed"));
        assertEquals(1230, mixedTitle.size(), "4.4: Set {'Mixed'} returns full catalog (1230 items)");

        List<ScreensaverCatalog.PhotoEntry> mixedUpper = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("MIXED"));
        assertEquals(1230, mixedUpper.size(), "4.5: Set {'MIXED'} returns full catalog (1230 items)");

        List<ScreensaverCatalog.PhotoEntry> mixedSpaced = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("  mixed  "));
        assertEquals(1230, mixedSpaced.size(), "4.6: Set {'  mixed  '} returns full catalog (1230 items)");

        // Set containing null and whitespace elements alongside valid categories
        Set<String> setWithNullAndBlanks = new HashSet<>(Arrays.asList(null, "", "   ", "\t\n", "space"));
        List<ScreensaverCatalog.PhotoEntry> robustFiltered = ScreensaverCatalog.catalogForCategories(null, setWithNullAndBlanks);
        assertEquals(205, robustFiltered.size(), "4.7: Set with null, empty, and 'space' cleanly returns 205 space items without crashing");

        // Set containing ONLY null and empty strings defaults to full catalog
        Set<String> onlyBlanks = new HashSet<>(Arrays.asList(null, "", "   "));
        List<ScreensaverCatalog.PhotoEntry> onlyBlanksFiltered = ScreensaverCatalog.catalogForCategories(null, onlyBlanks);
        assertEquals(1230, onlyBlanksFiltered.size(), "4.8: Set with only null/blank elements defaults cleanly to full catalog");

        // Unknown category returns 0 entries
        List<ScreensaverCatalog.PhotoEntry> unknownCat = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("intergalactic_void"));
        assertEquals(0, unknownCat.size(), "4.9: Unknown category returns empty list");

        // Unknown category mixed with valid category returns only valid category entries
        Set<String> validAndUnknown = new HashSet<>(Arrays.asList("intergalactic_void", "paintings"));
        List<ScreensaverCatalog.PhotoEntry> validUnknownFiltered = ScreensaverCatalog.catalogForCategories(null, validAndUnknown);
        assertEquals(205, validUnknownFiltered.size(), "4.10: Set {'intergalactic_void', 'paintings'} returns 205 paintings");
    }

    /**
     * Test catalog immutability and defensive copying.
     */
    private static void testCatalogImmutabilityAndDefensiveCopying() {
        System.out.println("\n--- [Challenge 5] Catalog Immutability & Defensive Copying ---");

        List<ScreensaverCatalog.PhotoEntry> loaded = ScreensaverCatalog.loadCatalog(null);

        // Attempting to modify the list returned by loadCatalog should throw UnsupportedOperationException
        boolean threwUnsupported = false;
        try {
            loaded.clear();
        } catch (UnsupportedOperationException e) {
            threwUnsupported = true;
        }
        assertTrue(threwUnsupported, "5.1: loadCatalog(null) returned an unmodifiable list");

        // Mutating the list returned by catalogForCategories should not affect the catalog
        List<ScreensaverCatalog.PhotoEntry> filtered = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("space"));
        assertEquals(205, filtered.size(), "5.2: Filtered space list has 205 items before mutation");
        filtered.clear();
        assertEquals(0, filtered.size(), "5.3: Filtered local copy cleared to 0");

        List<ScreensaverCatalog.PhotoEntry> refiltered = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("space"));
        assertEquals(205, refiltered.size(), "5.4: Subsequent space query still returns 205 items (defensive copy intact)");
    }

    /**
     * Multi-threaded concurrent stress test.
     */
    private static void testConcurrentCategoryQueriesHammer() throws Exception {
        System.out.println("\n--- [Challenge 6] Concurrent Multi-Threaded Hammer (25 threads, 2500 ops) ---");

        int threadCount = 25;
        int opsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        String[][] queryVariations = {
            {"space"}, {"Space"}, {"SPACE"}, {"  sPaCe  "},
            {"paintings"}, {"Paintings"}, {"PAINTINGS"},
            {"art"}, {"  art  "}, {"ART"},
            {"nature", "urban"}, {"  NATURE  ", "URBAN"},
            {"macro", "SPACE", "Paintings"},
            {"mixed"}, {"Mixed"}, {"MIXED"}
        };

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String[] q = queryVariations[(threadId + i) % queryVariations.length];
                        Set<String> querySet = new HashSet<>(Arrays.asList(q));
                        List<ScreensaverCatalog.PhotoEntry> res = ScreensaverCatalog.catalogForCategories(null, querySet);
                        if (res != null && res.size() > 0) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Throwable ex) {
                    errorCount.incrementAndGet();
                    ex.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished, "6.1: All 25 concurrent threads finished within 10 seconds");
        assertEquals(0, errorCount.get(), "6.2: Zero errors under 2,500 concurrent operations");
        assertEquals(threadCount * opsPerThread, successCount.get(), "6.3: All 2,500 operations succeeded");
    }

    /**
     * Test LRU disk cache bounds enforcement.
     */
    private static void testLruDiskCacheBoundsStress() throws Exception {
        System.out.println("\n--- [Challenge 7] LRU Disk Cache Bounds Enforcement (>200MB) ---");

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "soartv_recheck_lru_" + System.currentTimeMillis());
        assertTrue(tempDir.mkdirs(), "7.1: Created temp test cache dir: " + tempDir.getAbsolutePath());

        try {
            // Create 25 files, each 10MB = 250MB total (> 200MB limit)
            int fileCount = 25;
            long fileSizeBytes = 10L * 1024 * 1024;
            File[] files = new File[fileCount];
            long baseTime = System.currentTimeMillis() - 200_000L;

            for (int i = 0; i < fileCount; i++) {
                files[i] = new File(tempDir, String.format("slide_%02d.jpg", i));
                try (RandomAccessFile raf = new RandomAccessFile(files[i], "rw")) {
                    raf.setLength(fileSizeBytes);
                }
                files[i].setLastModified(baseTime + (i * 2000L));
            }

            // Create non-.jpg file (e.g. meta.json)
            File nonJpg = new File(tempDir, "cache_meta.json");
            try (FileOutputStream fos = new FileOutputStream(nonJpg)) {
                fos.write("{\"cache\": true}".getBytes("UTF-8"));
            }
            nonJpg.setLastModified(baseTime - 10_000L); // Oldest

            // Enforce bounds
            ScreensaverCatalog.enforceDiskCacheBounds(tempDir);

            // Compute total remaining jpg bytes
            long remainingJpgBytes = 0;
            int remainingJpgs = 0;
            File[] remainingFiles = tempDir.listFiles();
            if (remainingFiles != null) {
                for (File f : remainingFiles) {
                    if (f.getName().endsWith(".jpg")) {
                        remainingJpgBytes += f.length();
                        remainingJpgs++;
                    }
                }
            }

            long targetMax = (long) (ScreensaverCatalog.MAX_DISK_CACHE_BYTES * 0.85); // 170 MB
            assertTrue(remainingJpgBytes <= targetMax, "7.2: Cache size evicted down to <= 85% headroom (actual: " + (remainingJpgBytes / (1024 * 1024)) + " MB, max: " + (targetMax / (1024 * 1024)) + " MB)");
            assertEquals(17, remainingJpgs, "7.3: Exactly 17 of 25 files survive (17 * 10MB = 170MB)");

            // Verify oldest files (0..7) were deleted, newest (8..24) survived
            for (int i = 0; i < 8; i++) {
                assertFalse(files[i].exists(), "7.4: Oldest file slide_" + String.format("%02d", i) + ".jpg was evicted");
            }
            for (int i = 8; i < 25; i++) {
                assertTrue(files[i].exists(), "7.5: Newer file slide_" + String.format("%02d", i) + ".jpg survived");
            }

            assertTrue(nonJpg.exists(), "7.6: Non-.jpg file cache_meta.json was preserved");
        } finally {
            // Clean up tempDir
            File[] remainingFiles = tempDir.listFiles();
            if (remainingFiles != null) {
                for (File f : remainingFiles) f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Test R3 Caption Engine contracts.
     */
    private static void testR3CaptionEngineContracts() {
        System.out.println("\n--- [Challenge 8] R3 Caption Formatter Specification Rules ---");

        // Subject classification
        assertTrue(ScreensaverCatalog.isSubjectCategory("art"), "8.1: 'art' is subject category");
        assertTrue(ScreensaverCatalog.isSubjectCategory("paintings"), "8.2: 'paintings' is subject category");
        assertTrue(ScreensaverCatalog.isSubjectCategory("macro"), "8.3: 'macro' is subject category");
        assertTrue(ScreensaverCatalog.isSubjectCategory("space"), "8.4: 'space' is subject category");
        assertTrue(ScreensaverCatalog.isSubjectCategory("SPACE"), "8.5: 'SPACE' is subject category");
        assertFalse(ScreensaverCatalog.isSubjectCategory("nature"), "8.6: 'nature' is NOT subject category");
        assertFalse(ScreensaverCatalog.isSubjectCategory("urban"), "8.7: 'urban' is NOT subject category");
        assertFalse(ScreensaverCatalog.isSubjectCategory(null), "8.8: null is NOT subject category");

        // Subject displays title, suppresses location
        assertEquals("James Webb Deep Field", ScreensaverCatalog.buildCaptionTitle("space", "James Webb Deep Field", true), "8.9: Space displays title");
        assertEquals("Mona Lisa", ScreensaverCatalog.buildCaptionTitle("paintings", "Mona Lisa", true), "8.10: Paintings displays title");
        assertEquals("", ScreensaverCatalog.buildCaptionTitle("nature", "Banff Lake", true), "8.11: Nature suppresses title per R3");
        assertEquals("", ScreensaverCatalog.buildCaptionTitle("urban", "Tokyo Skytree", true), "8.12: Urban suppresses title per R3");

        // Metadata display
        String urbanMeta = ScreensaverCatalog.buildCaptionMeta("urban", "Photographer", "Tokyo, Japan", "Tokyo Skytree", true, true);
        assertEquals("Photo by Photographer \u00B7 Tokyo Skytree, Tokyo, Japan", urbanMeta, "8.13: Urban prepends building to location");

        String spaceMeta = ScreensaverCatalog.buildCaptionMeta("space", "NASA / ESA", "Low Earth Orbit", "", true, true);
        assertEquals("Photo by NASA / ESA", spaceMeta, "8.14: Space suppresses location");

        // Missing fields graceful skipping
        assertEquals("", ScreensaverCatalog.buildCaptionMeta("nature", null, null, null, true, true), "8.15: Missing all fields yields empty string");
        assertEquals("Iceland", ScreensaverCatalog.buildCaptionMeta("nature", null, "Iceland", null, true, true), "8.16: Missing artist yields clean location");
        assertEquals("Photo by Ansel Adams", ScreensaverCatalog.buildCaptionMeta("nature", "Ansel Adams", null, null, true, true), "8.17: Missing location yields clean artist");
    }
}
