package io.mytv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Empirical Stress Test Harness for Milestone 2:
 * 4K Galleries Catalog & Sourcing Engine.
 *
 * Authored by Challenger M2-1.
 */
public final class Milestone2StressTests {

    private static int assertionsPassed = 0;

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("[FAIL] " + message);
        }
        assertionsPassed++;
        System.out.println("  [PASS] " + message);
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            throw new AssertionError("[FAIL] " + message + " | expected=" + expected + ", actual=" + actual);
        }
        assertionsPassed++;
        System.out.println("  [PASS] " + message);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== STARTING MILESTONE 2 EMPIRICAL STRESS TEST HARNESS ===");
        long start = System.currentTimeMillis();

        testLruDiskCacheBoundsEnforcement();
        testLruEdgeCases();
        testConcurrencySafety();
        testCaptionEngineR3Rules();
        testCatalogCategoryFiltering();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("\n=== MILESTONE 2 STRESS TESTS SUMMARY ===");
        System.out.println("Total Assertions Executed: " + assertionsPassed);
        System.out.println("Failed Assertions: 0");
        System.out.println("Execution Time: " + elapsed + " ms");
        System.out.println("RESULT: COMPLETE");
    }

    /**
     * Stress Test 1: LRU cache bounds enforcement (enforceDiskCacheBounds).
     * Simulates a cache directory containing > 200MB (e.g. 250MB),
     * verifies eviction down to 85% headroom (<= 170MB),
     * and verifies oldest files by lastModified are evicted first.
     */
    private static void testLruDiskCacheBoundsEnforcement() throws Exception {
        System.out.println("\n--- [Test 1] LRU Disk Cache Bounds Enforcement (>200MB) ---");

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "mytv_cache_lru_test_" + System.currentTimeMillis());
        assertTrue(tempDir.mkdirs(), "Created temporary test cache directory: " + tempDir.getAbsolutePath());

        try {
            // Create 25 files, each exactly 10MB (10 * 1024 * 1024 bytes) = 250MB total (> 200MB limit)
            int fileCount = 25;
            long fileSizeBytes = 10L * 1024 * 1024; // 10 MB each
            File[] files = new File[fileCount];
            long baseTime = System.currentTimeMillis() - 100_000L;

            for (int i = 0; i < fileCount; i++) {
                files[i] = new File(tempDir, String.format("slide_%02d.jpg", i));
                try (RandomAccessFile raf = new RandomAccessFile(files[i], "rw")) {
                    raf.setLength(fileSizeBytes);
                }
                // Assign deterministic timestamps: file 0 oldest, file 24 newest
                files[i].setLastModified(baseTime + (i * 1000L));
            }

            // Also create a non-.jpg file and verify it is not deleted
            File nonJpgFile = new File(tempDir, "meta.json");
            try (FileOutputStream fos = new FileOutputStream(nonJpgFile)) {
                fos.write("{\"version\": 1}".getBytes("UTF-8"));
            }
            nonJpgFile.setLastModified(baseTime); // Very old

            long initialTotalBytes = 0L;
            for (File f : tempDir.listFiles((d, name) -> name.endsWith(".jpg"))) {
                initialTotalBytes += f.length();
            }
            assertEquals(250L * 1024 * 1024, initialTotalBytes, "Initial cache size is exactly 250MB");
            assertTrue(initialTotalBytes > ScreensaverCatalog.MAX_DISK_CACHE_BYTES, "Initial cache exceeds 200MB limit");

            // Execute LRU eviction
            ScreensaverCatalog.enforceDiskCacheBounds(tempDir);

            // Verify bounds after eviction
            File[] remainingJpg = tempDir.listFiles((d, name) -> name.endsWith(".jpg"));
            long remainingTotalBytes = 0L;
            for (File f : remainingJpg) {
                remainingTotalBytes += f.length();
            }

            long targetHeadroomBytes = (long) (ScreensaverCatalog.MAX_DISK_CACHE_BYTES * 0.85); // 170MB
            assertTrue(remainingTotalBytes <= targetHeadroomBytes,
                    "Remaining cache bytes (" + (remainingTotalBytes / (1024 * 1024)) + "MB) is <= 85% headroom (" + (targetHeadroomBytes / (1024 * 1024)) + "MB)");

            // Verify oldest files (0, 1, 2, ...) were evicted and newest files (24, 23, ...) survived
            // 250MB -> <=170MB requires deleting at least 8 files (80MB)
            int expectedSurvivingCount = (int) (targetHeadroomBytes / fileSizeBytes);
            assertTrue(remainingJpg.length <= expectedSurvivingCount + 1,
                    "Surviving file count (" + remainingJpg.length + ") matches target capacity");

            // Check that newest files survived
            assertTrue(files[fileCount - 1].exists(), "Newest file (slide_24) must survive eviction");
            assertTrue(files[fileCount - 2].exists(), "Second newest file (slide_23) must survive eviction");

            // Check that oldest files were deleted
            assertFalse(files[0].exists(), "Oldest file (slide_00) must be evicted first");
            assertFalse(files[1].exists(), "Second oldest file (slide_01) must be evicted");

            // Check that non-.jpg file was untouched
            assertTrue(nonJpgFile.exists(), "Non-.jpg file (meta.json) was not touched by eviction");

        } finally {
            // Clean up
            File[] remaining = tempDir.listFiles();
            if (remaining != null) {
                for (File f : remaining) f.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Stress Test 2: LRU cache edge cases (null, non-existent, empty, <=200MB).
     */
    private static void testLruEdgeCases() throws Exception {
        System.out.println("\n--- [Test 2] LRU Cache Boundary & Edge Cases ---");

        // Null directory
        try {
            ScreensaverCatalog.enforceDiskCacheBounds(null);
            assertTrue(true, "enforceDiskCacheBounds(null) does not throw exception");
        } catch (Throwable t) {
            assertTrue(false, "enforceDiskCacheBounds(null) threw: " + t);
        }

        // Non-existent directory
        File ghostDir = new File(System.getProperty("java.io.tmpdir"), "mytv_non_existent_dir_" + System.currentTimeMillis());
        try {
            ScreensaverCatalog.enforceDiskCacheBounds(ghostDir);
            assertTrue(true, "enforceDiskCacheBounds(nonExistent) does not throw exception");
        } catch (Throwable t) {
            assertTrue(false, "enforceDiskCacheBounds(nonExistent) threw: " + t);
        }

        // Empty directory
        File emptyDir = new File(System.getProperty("java.io.tmpdir"), "mytv_empty_dir_" + System.currentTimeMillis());
        emptyDir.mkdirs();
        try {
            ScreensaverCatalog.enforceDiskCacheBounds(emptyDir);
            assertTrue(true, "enforceDiskCacheBounds(emptyDir) does not throw exception");
        } finally {
            emptyDir.delete();
        }

        // Directory with total size <= 200MB (no eviction should occur)
        File smallDir = new File(System.getProperty("java.io.tmpdir"), "mytv_small_dir_" + System.currentTimeMillis());
        smallDir.mkdirs();
        try {
            File f1 = new File(smallDir, "small1.jpg");
            File f2 = new File(smallDir, "small2.jpg");
            try (RandomAccessFile raf = new RandomAccessFile(f1, "rw")) { raf.setLength(1024 * 1024); }
            try (RandomAccessFile raf = new RandomAccessFile(f2, "rw")) { raf.setLength(1024 * 1024); }

            ScreensaverCatalog.enforceDiskCacheBounds(smallDir);
            assertTrue(f1.exists() && f2.exists(), "No eviction when total size (2MB) is well under 200MB cap");
        } finally {
            File[] rem = smallDir.listFiles();
            if (rem != null) for (File f : rem) f.delete();
            smallDir.delete();
        }
    }

    /**
     * Stress Test 3: Concurrency & Thread-Safety of catalog loading and category queries.
     */
    private static void testConcurrencySafety() throws Exception {
        System.out.println("\n--- [Test 3] Multi-Threaded Concurrency Hammer ---");

        int threadCount = 20;
        int iterationsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        List<Set<String>> testCategorySets = Arrays.asList(
                null,
                Collections.emptySet(),
                Collections.singleton("nature"),
                Collections.singleton("space"),
                Collections.singleton("urban"),
                new HashSet<>(Arrays.asList("art", "paintings")),
                new HashSet<>(Arrays.asList("nature", "macro", "wildlife")),
                Collections.singleton("mixed")
        );

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.execute(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        Set<String> catSet = testCategorySets.get((threadId + i) % testCategorySets.size());
                        List<ScreensaverCatalog.PhotoEntry> entries = ScreensaverCatalog.catalogForCategories(null, catSet);
                        if (entries == null || entries.isEmpty()) {
                            errorCount.incrementAndGet();
                        } else {
                            // Verify isolation: mutating the returned list does not affect other threads or internal catalog
                            int originalSize = entries.size();
                            try {
                                entries.clear(); // Mutate caller's copy if mutable
                            } catch (UnsupportedOperationException expected) {
                                // Immutable collection returned, perfectly safe
                            }
                            List<ScreensaverCatalog.PhotoEntry> fresh = ScreensaverCatalog.catalogForCategories(null, catSet);
                            if (fresh.size() != originalSize) {
                                errorCount.incrementAndGet(); // Leakage across calls!
                            } else {
                                successCount.incrementAndGet();
                            }
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

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished, "All 20 concurrent threads completed within 5 seconds");
        assertEquals(0, errorCount.get(), "Zero concurrency errors under 1,000 multi-threaded operations");
        assertEquals(threadCount * iterationsPerThread, successCount.get(), "All 1,000 concurrent queries returned valid isolated lists");
    }

    /**
     * Stress Test 4: R3 Caption Formatter Specification Rules under edge/boundary inputs.
     */
    private static void testCaptionEngineR3Rules() {
        System.out.println("\n--- [Test 4] R3 Caption Formatter Contract Stress Testing ---");

        // Subject categories classification
        assertTrue(ScreensaverCatalog.isSubjectCategory("art"), "art is subject category");
        assertTrue(ScreensaverCatalog.isSubjectCategory("paintings"), "paintings is subject category");
        assertTrue(ScreensaverCatalog.isSubjectCategory("painting"), "painting is subject category");
        assertTrue(ScreensaverCatalog.isSubjectCategory("macro"), "macro is subject category");
        assertTrue(ScreensaverCatalog.isSubjectCategory("space"), "space is subject category");
        assertTrue(ScreensaverCatalog.isSubjectCategory("SPACE"), "case-insensitive SPACE is subject category");
        assertFalse(ScreensaverCatalog.isSubjectCategory("nature"), "nature is NOT subject category");
        assertFalse(ScreensaverCatalog.isSubjectCategory("urban"), "urban is NOT subject category");
        assertFalse(ScreensaverCatalog.isSubjectCategory("wildlife"), "wildlife is NOT subject category");
        assertFalse(ScreensaverCatalog.isSubjectCategory(null), "null is NOT subject category");

        // Title display rule: subject categories display title; photo categories suppress title
        assertEquals("The Starry Night", ScreensaverCatalog.buildCaptionTitle("paintings", "The Starry Night", true),
                "Subject category 'paintings' displays artwork title");
        assertEquals("Carina Nebula", ScreensaverCatalog.buildCaptionTitle("space", "Carina Nebula", true),
                "Subject category 'space' displays subject title");
        assertEquals("", ScreensaverCatalog.buildCaptionTitle("nature", "Yosemite Valley", true),
                "Photo category 'nature' suppresses title per R3");
        assertEquals("", ScreensaverCatalog.buildCaptionTitle("urban", "Marina Bay", true),
                "Photo category 'urban' suppresses title per R3");
        assertEquals("", ScreensaverCatalog.buildCaptionTitle("paintings", "The Starry Night", false),
                "Subject category suppresses title when showTitle is false");

        // Meta display rule: urban includes building alongside location; subject categories suppress location
        String urbanMeta = ScreensaverCatalog.buildCaptionMeta("urban", "Basile Morin", "Singapore", "Marina Bay Sands", true, true);
        assertEquals("Photo by Basile Morin \u00B7 Marina Bay Sands, Singapore", urbanMeta,
                "Urban category includes building prepended to location");

        String urbanNoBuildingMeta = ScreensaverCatalog.buildCaptionMeta("urban", "Pierre Blaché", "Paris, France", "", true, true);
        assertEquals("Photo by Pierre Blaché \u00B7 Paris, France", urbanNoBuildingMeta,
                "Urban category without building shows location only");

        String subjectMeta = ScreensaverCatalog.buildCaptionMeta("paintings", "Vincent van Gogh", "Saint-Rémy-de-Provence", "", true, true);
        assertEquals("Photo by Vincent van Gogh", subjectMeta,
                "Subject category 'paintings' suppresses location per R3");

        // Missing fields graceful skipping
        String missingAll = ScreensaverCatalog.buildCaptionMeta("nature", null, null, null, true, true);
        assertEquals("", missingAll, "Missing all fields produces clean empty string");

        String missingArtist = ScreensaverCatalog.buildCaptionMeta("nature", null, "Yosemite", null, true, true);
        assertEquals("Yosemite", missingArtist, "Missing artist produces location without separator");

        String missingLocation = ScreensaverCatalog.buildCaptionMeta("nature", "John Doe", null, null, true, true);
        assertEquals("Photo by John Doe", missingLocation, "Missing location produces artist without trailing separator");
    }

    /**
     * Stress Test 5: Category Filtering.
     */
    private static void testCatalogCategoryFiltering() {
        System.out.println("\n--- [Test 5] Category Filtering Invariants ---");

        List<ScreensaverCatalog.PhotoEntry> builtins = ScreensaverCatalog.builtinEntries();
        assertTrue(builtins != null && builtins.size() == 24, "Builtin catalog has exactly 24 verified True 4K items");

        // Filtering by specific category returns only entries matching that category
        List<ScreensaverCatalog.PhotoEntry> natureOnly = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("nature"));
        assertTrue(natureOnly != null && !natureOnly.isEmpty(), "nature filter returned items");
        for (ScreensaverCatalog.PhotoEntry pe : natureOnly) {
            assertEquals("nature", pe.category.toLowerCase(), "Filtered item has category 'nature'");
        }

        // Unknown category returns empty list
        List<ScreensaverCatalog.PhotoEntry> unknown = ScreensaverCatalog.catalogForCategories(null, Collections.singleton("non_existent_category_123"));
        assertTrue(unknown != null && unknown.isEmpty(), "Unknown category returns empty list");
    }
}
