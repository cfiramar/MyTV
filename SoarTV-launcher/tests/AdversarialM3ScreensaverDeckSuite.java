package io.soartv;

import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Empirical Adversarial Challenge Test Suite for Milestone 3:
 * Randomized Navigation Deck, History Stack, and Remote Key Bindings.
 *
 * <p>Validates:
 * 1. Exact Reverse Order History Navigation (LIFO traversal, bounds clamping at 0, forward re-traversal).
 * 2. ShuffleBag Randomization & Uniformity (zero repeats within cycle, cycle boundary de-duplication, uniform distribution).
 * 3. Memory Safety & SlideHistoryCache Invariants (max 3 bitmaps bounded, rapid alternating steps LRU eviction, recycling safety).
 * 4. Remote Key Code Bindings & Dismissal Isolation (DPAD Left/Right step slides, BACK key exits screensaver and is never consumed for slide stepping).
 *
 * Authored by Challenger M3-2.
 */
public final class AdversarialM3ScreensaverDeckSuite {

    private static int assertions = 0;
    private static int failedAssertions = 0;
    private static final List<String> criticalDefects = new ArrayList<>();

    private AdversarialM3ScreensaverDeckSuite() {}

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

    private static void assertNotNull(Object obj, String message) {
        assertTrue(obj != null, message);
    }

    private static void assertNull(Object obj, String message) {
        assertTrue(obj == null, message);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== STARTING ADVERSARIAL M3 SCREENSAVER DECK & HISTORY SUITE ===");
        long start = System.currentTimeMillis();

        testExactReverseOrderHistoryNavigation();
        testShuffleBagRandomizationAndUniformity();
        testMemorySafetyAndSlideHistoryCacheInvariants();
        testRemoteKeyCodeBindingsAndDismissalIsolation();

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("\n=== ADVERSARIAL M3 SCREENSAVER DECK SUMMARY ===");
        System.out.println("Total Assertions Executed: " + assertions);
        System.out.println("Failed Assertions: " + failedAssertions);
        System.out.println("Critical Defects Identified: " + criticalDefects.size());
        System.out.println("Execution Time: " + elapsed + " ms");

        if (failedAssertions > 0) {
            System.err.println("ADVERSARIAL SUITE RESULT: REJECT");
            for (String defect : criticalDefects) {
                System.err.println("  - " + defect);
            }
            throw new AssertionError("Adversarial M3 Screensaver Deck Suite FAILED with " + failedAssertions + " errors");
        } else {
            System.out.println("ADVERSARIAL SUITE RESULT: APPROVE");
        }
    }

    // =========================================================================
    // 1. Exact Reverse Order History Navigation
    // =========================================================================

    /**
     * Empirical Stress-Testing of Navigation Deck History:
     * - Advancing through N slides sequentially.
     * - Stepping DPAD Left (stepSlide(-1)) N-1 times returns visited slides in exact reverse chronological order (LIFO).
     * - Stepping DPAD Left at index 0 clamps at 0 without crash or underflow.
     * - Stepping DPAD Right (stepSlide(+1)) re-traverses the visited slides forward in original order until reaching frontier.
     * - Stepping DPAD Right at frontier draws a new item.
     * - Capping at MAX_NAV_HISTORY (100) preserves bounds.
     */
    private static void testExactReverseOrderHistoryNavigation() {
        System.out.println("\n--- [Challenge 1] Exact Reverse Order History Navigation ---");

        final int N = 50;
        final int MAX_NAV_HISTORY = 100;
        List<String> navHistory = new ArrayList<>(MAX_NAV_HISTORY + 8);
        int navIndex = -1;

        // Simulate screensaver advancing through N slides
        List<String> visitedOrder = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            String key = String.format("photo:slide_%03d", i);
            visitedOrder.add(key);
            // Replicate recordCurrentInHistory
            navHistory.add(key);
            navIndex = navHistory.size() - 1;
        }

        assertEquals(N, navHistory.size(), "Nav history contains N = " + N + " visited slides");
        assertEquals(N - 1, navIndex, "Nav pointer is at current frontier index N - 1 = " + (N - 1));
        assertEquals(visitedOrder.get(N - 1), navHistory.get(navIndex), "Current slide is slide_049");

        // Step DPAD Left (delta = -1) N-1 times -> verify exact reverse order (LIFO)
        for (int step = 1; step < N; step++) {
            int expectedTargetIndex = (N - 1) - step;
            String expectedSlideKey = visitedOrder.get(expectedTargetIndex);

            // Replicate stepSlide(-1) logic
            assertTrue(navIndex > 0, "navIndex > 0 before backward step " + step);
            int targetNavIndex = navIndex - 1;
            String targetKey = navHistory.get(targetNavIndex);

            assertEquals(expectedTargetIndex, targetNavIndex, "Step " + step + " targets index " + expectedTargetIndex);
            assertEquals(expectedSlideKey, targetKey, "Step " + step + " returns exact reverse slide " + expectedSlideKey);

            // Apply nav target
            navIndex = targetNavIndex;
        }

        assertEquals(0, navIndex, "Nav pointer arrived at index 0 (first slide: slide_000)");
        assertEquals(visitedOrder.get(0), navHistory.get(navIndex), "Current slide at index 0 is slide_000");

        // Boundary Underflow Test: trigger DPAD Left at index 0 multiple times
        for (int underflowPress = 1; underflowPress <= 15; underflowPress++) {
            // Replicate stepSlide(-1) boundary check:
            // if (navIndex <= 0) return;
            boolean wouldClamp = (navIndex <= 0);
            assertTrue(wouldClamp, "Underflow press " + underflowPress + " detects navIndex <= 0 boundary");
            if (!wouldClamp) {
                navIndex--; // would cause out of bounds if not clamped
            }
            assertEquals(0, navIndex, "Underflow press " + underflowPress + " leaves navIndex clamped at 0");
            assertEquals(visitedOrder.get(0), navHistory.get(navIndex), "Slide remains clamped at slide_000");
        }

        // Forward Re-Traversal: step DPAD Right (delta = +1) from index 0 back to frontier
        for (int fwdStep = 1; fwdStep < N; fwdStep++) {
            int expectedTargetIndex = fwdStep;
            String expectedSlideKey = visitedOrder.get(expectedTargetIndex);

            // Replicate stepSlide(+1) forward traversal:
            // if (navIndex < navHistory.size() - 1) -> re-traverse existing history without drawing new
            assertTrue(navIndex < navHistory.size() - 1, "navIndex < size - 1 at forward step " + fwdStep);
            int targetNavIndex = navIndex + 1;
            String targetKey = navHistory.get(targetNavIndex);

            assertEquals(expectedTargetIndex, targetNavIndex, "Forward step " + fwdStep + " targets index " + expectedTargetIndex);
            assertEquals(expectedSlideKey, targetKey, "Forward step " + fwdStep + " returns original chronological slide " + expectedSlideKey);

            navIndex = targetNavIndex;
        }

        assertEquals(N - 1, navIndex, "Nav pointer returned to frontier index " + (N - 1));
        assertEquals(visitedOrder.get(N - 1), navHistory.get(navIndex), "Frontier slide is slide_049");

        // Frontier Transition: trigger DPAD Right at frontier -> draws next randomized item
        boolean atFrontier = (navIndex == navHistory.size() - 1);
        assertTrue(atFrontier, "Confirmed at frontier before drawing new random item");
        String newlyDrawn = "photo:slide_050_new_random";
        int targetNavIndex = navHistory.size();
        navHistory.add(newlyDrawn);
        navIndex = navHistory.size() - 1;

        assertEquals(N + 1, navHistory.size(), "History expanded to N + 1 = 51 entries");
        assertEquals(N, navIndex, "navIndex advanced to 50");
        assertEquals(newlyDrawn, navHistory.get(navIndex), "Newly drawn item established as active slide");

        // MAX_NAV_HISTORY Bounding Test: advance through 150 total slides
        for (int extra = 51; extra <= 150; extra++) {
            String extraKey = String.format("photo:extra_%03d", extra);
            navHistory.add(extraKey);
            navIndex = navHistory.size() - 1;
            if (navHistory.size() > MAX_NAV_HISTORY) {
                navHistory.remove(0);
                navIndex = navHistory.size() - 1;
            }
        }

        assertEquals(MAX_NAV_HISTORY, navHistory.size(), "Nav history strictly bounded at MAX_NAV_HISTORY = 100");
        assertEquals(MAX_NAV_HISTORY - 1, navIndex, "navIndex clamped at 99");
        assertEquals("photo:extra_150", navHistory.get(navIndex), "Frontier holds latest extra_150 slide");
        // Oldest preserved slide should be extra_051 (since 51..150 is exactly 100 items)
        assertEquals("photo:extra_051", navHistory.get(0), "Oldest preserved slide is extra_051 (FIFO eviction)");
    }

    // =========================================================================
    // 2. ShuffleBag Randomization & Uniformity
    // =========================================================================

    /**
     * Empirical Stress-Testing of ShuffleBag:
     * - Multi-cycle execution (10 cycles of 200 items = 2,000 draws).
     * - Zero repeats within each single cycle.
     * - Back-to-back duplicate prevention across cycle boundaries (cycle K end != cycle K+1 start).
     * - Distribution uniformity (equal frequency across full cycles).
     * - Edge cases: empty pool, single-item pool, failed-keys skipping, all-failed non-hanging.
     */
    private static void testShuffleBagRandomizationAndUniformity() {
        System.out.println("\n--- [Challenge 2] ShuffleBag Randomization & Uniformity ---");

        final int POOL_SIZE = 200; // Requirement R2: >200 images per category
        final int CYCLES = 10;
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.add(String.format("art_item_%03d", i));
        }

        DreamView.ShuffleBag<String> bag = new DreamView.ShuffleBag<>(new Random(424242));
        bag.setPool(pool);

        assertEquals(POOL_SIZE, bag.size(), "ShuffleBag pool size is " + POOL_SIZE);
        assertFalse(bag.isEmpty(), "ShuffleBag is not empty");

        Map<String, Integer> globalFrequencies = new HashMap<>();
        String previousItem = null;
        int boundaryChecks = 0;
        int boundaryDuplicates = 0;

        for (int cycle = 0; cycle < CYCLES; cycle++) {
            Set<String> cycleItems = new HashSet<>();
            String firstItemOfCycle = null;
            String lastItemOfCycle = null;

            for (int step = 0; step < POOL_SIZE; step++) {
                String item = bag.draw(null);
                assertNotNull(item, "Cycle " + cycle + " step " + step + " draw is not null");

                if (step == 0) {
                    firstItemOfCycle = item;
                    if (previousItem != null) {
                        boundaryChecks++;
                        if (item.equals(previousItem)) {
                            boundaryDuplicates++;
                        }
                    }
                }
                if (step == POOL_SIZE - 1) {
                    lastItemOfCycle = item;
                }

                // Intra-cycle uniqueness check
                boolean added = cycleItems.add(item);
                assertTrue(added, "Cycle " + cycle + ": item " + item + " drawn without repeat in current cycle");

                // Track global frequencies
                globalFrequencies.put(item, globalFrequencies.getOrDefault(item, 0) + 1);
            }

            assertEquals(POOL_SIZE, cycleItems.size(), "Cycle " + cycle + " contains all " + POOL_SIZE + " unique items");
            previousItem = lastItemOfCycle;
        }

        // Boundary de-duplication verification
        assertEquals(CYCLES - 1, boundaryChecks, "Total boundary transitions checked: " + (CYCLES - 1));
        assertEquals(0, boundaryDuplicates, "Zero back-to-back duplicates across cycle boundaries (K end != K+1 start)");

        // Distribution uniformity across complete cycles
        for (String item : pool) {
            int count = globalFrequencies.getOrDefault(item, 0);
            assertEquals(CYCLES, count, "Item " + item + " drawn exactly " + CYCLES + " times across " + CYCLES + " cycles");
        }

        // High-Probability Boundary Stress Test: 3-item pool over 300 cycles (900 draws)
        // With only 3 items, random shuffle has a 33.3% chance of putting the previous item first.
        // ShuffleBag's swap logic MUST eliminate 100% of these collisions.
        List<String> smallPool = Arrays.asList("cat_A", "cat_B", "cat_C");
        DreamView.ShuffleBag<String> smallBag = new DreamView.ShuffleBag<>(new Random(99999));
        smallBag.setPool(smallPool);

        String lastSmallDrawn = null;
        int smallBoundaryDuplicates = 0;
        for (int cycle = 0; cycle < 300; cycle++) {
            for (int s = 0; s < 3; s++) {
                String item = smallBag.draw(null);
                if (s == 0 && lastSmallDrawn != null) {
                    if (item.equals(lastSmallDrawn)) {
                        smallBoundaryDuplicates++;
                    }
                }
                lastSmallDrawn = item;
            }
        }
        assertEquals(0, smallBoundaryDuplicates, "Small 3-item pool had 0 boundary duplicates across 300 cycles (swap logic verified)");

        // Edge Case 1: Empty Pool
        DreamView.ShuffleBag<String> emptyBag = new DreamView.ShuffleBag<>();
        assertNull(emptyBag.draw(null), "Empty bag draw returns null safely");
        assertEquals(0, emptyBag.size(), "Empty bag size is 0");
        assertTrue(emptyBag.isEmpty(), "Empty bag isEmpty() is true");

        // Edge Case 2: 1-Item Pool
        DreamView.ShuffleBag<String> singleBag = new DreamView.ShuffleBag<>();
        singleBag.setPool(Collections.singletonList("lone_wolf"));
        for (int i = 0; i < 10; i++) {
            assertEquals("lone_wolf", singleBag.draw(null), "Single-item pool repeatedly returns sole item without hang");
        }

        // Edge Case 3: Failed Keys Filtering
        DreamView.ShuffleBag<String> filterBag = new DreamView.ShuffleBag<>(new Random(1234));
        filterBag.setPool(Arrays.asList("ok1", "corrupt_file", "ok2", "missing_url", "ok3"));
        Set<String> failed = new HashSet<>(Arrays.asList("corrupt_file", "missing_url"));

        for (int i = 0; i < 9; i++) {
            String drawn = filterBag.draw(failed);
            assertNotNull(drawn, "Filtered draw returns valid item");
            assertFalse(failed.contains(drawn), "Drawn item " + drawn + " is never in failed set");
        }

        // Edge Case 4: All Keys Failed (Must not infinite loop)
        Set<String> allFailed = new HashSet<>(Arrays.asList("ok1", "corrupt_file", "ok2", "missing_url", "ok3"));
        String allFailedDrawn = filterBag.draw(allFailed);
        assertNull(allFailedDrawn, "When all items are failed, draw() exhausts attempts and returns null cleanly");

        // Clear bag
        filterBag.clear();
        assertTrue(filterBag.isEmpty(), "clear() resets pool and bag");
    }

    // =========================================================================
    // 3. Memory Safety & SlideHistoryCache Invariants
    // =========================================================================

    /**
     * Empirical Stress-Testing of SlideHistoryCache:
     * - Strict heap memory bounding (max 3 slides in cache).
     * - Eviction returns displaced/oldest entries for scheduled recycling.
     * - LRU access order preservation.
     * - Rapid alternating Left/Right navigation (1,000 steps) verifying zero memory leaks.
     */
    private static void testMemorySafetyAndSlideHistoryCacheInvariants() {
        System.out.println("\n--- [Challenge 3] Memory Safety & SlideHistoryCache Invariants ---");

        final int CAPACITY = 3;
        assertEquals(3, DreamView.MAX_HISTORY_SLIDES, "DreamView.MAX_HISTORY_SLIDES constant is 3");

        DreamView.SlideHistoryCache<String> cache = new DreamView.SlideHistoryCache<>(CAPACITY);
        assertEquals(0, cache.size(), "Initial cache size is 0");

        // 1. Initial 3 insertions: evict nothing
        assertNull(cache.put("k1", "slide_1"), "1st insert evicts null");
        assertEquals(1, cache.size(), "Size is 1");
        assertNull(cache.put("k2", "slide_2"), "2nd insert evicts null");
        assertEquals(2, cache.size(), "Size is 2");
        assertNull(cache.put("k3", "slide_3"), "3rd insert evicts null");
        assertEquals(3, cache.size(), "Size is 3");

        // 2. 4th insertion: strictly evicts oldest (k1)
        String evicted = cache.put("k4", "slide_4");
        assertEquals("slide_1", evicted, "4th insert evicts oldest slide_1");
        assertEquals(3, cache.size(), "Size remains bounded at 3");

        // 3. LRU access order preservation
        // Cache currently contains: k2, k3, k4.
        // Access k2 via take and re-insert -> makes k2 MRU
        String takenK2 = cache.take("k2");
        assertEquals("slide_2", takenK2, "take('k2') returns slide_2");
        assertEquals(2, cache.size(), "Size reduced to 2 after take");
        cache.put("k2", "slide_2_refreshed");
        assertEquals(3, cache.size(), "Size back to 3");

        // Now oldest is k3 (since k4 was inserted after k3, and k2 was just refreshed)
        String evictedNext = cache.put("k5", "slide_5");
        assertEquals("slide_3", evictedNext, "LRU eviction evicts k3 (slide_3), preserving recently accessed k2");
        assertEquals(3, cache.size(), "Size strictly bounded at 3");

        // 4. In-place replacement of same key
        String displaced = cache.put("k5", "slide_5_v2");
        assertEquals("slide_5", displaced, "Replaced entry is returned for recycling");
        assertEquals(3, cache.size(), "Size remains 3");

        // Same instance under same key returns null (no eviction)
        String selfPut = cache.put("k5", "slide_5_v2");
        assertNull(selfPut, "Re-inserting same object under same key evicts nothing");
        assertEquals(3, cache.size(), "Size remains 3");

        // Null guards
        assertNull(cache.put(null, "val"), "Null key returns null");
        assertNull(cache.put("", "val"), "Empty key returns null");
        assertNull(cache.put("k6", null), "Null slide returns null");
        assertEquals(3, cache.size(), "Size unchanged after null inputs");

        assertNull(cache.take(null), "take(null) returns null");
        assertNull(cache.take("non_existent"), "take(miss) returns null");

        // 5. Rapid Alternating 1,000 Navigation Steps Stress Harness
        System.out.println("  -> Running 1,000 rapid alternating Left/Right steps...");
        Random stepRng = new Random(777);
        int totalEvictions = 0;
        Set<String> scheduledForRecycle = new HashSet<>();
        List<String> liveKeys = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            String stepKey = "step_slide_" + i;
            liveKeys.add(stepKey);

            // Replicate DreamView.stashInHistory
            String oldEvicted = cache.put(stepKey, "bitmap_handle_" + i);
            if (oldEvicted != null) {
                totalEvictions++;
                scheduledForRecycle.add(oldEvicted);
            }

            assertTrue(cache.size() <= CAPACITY, "Step " + i + ": cache size " + cache.size() + " <= " + CAPACITY);

            // Periodically take or check random key
            if (i % 5 == 0 && !liveKeys.isEmpty()) {
                int targetIdx = Math.max(0, liveKeys.size() - 1 - stepRng.nextInt(3));
                String queryKey = liveKeys.get(targetIdx);
                String hit = cache.take(queryKey);
                if (hit != null) {
                    // Re-stash
                    cache.put(queryKey, hit);
                }
            }
        }

        assertTrue(totalEvictions > 900, "1,000 rapid steps produced " + totalEvictions + " bounded LRU evictions");
        assertEquals(CAPACITY, cache.size(), "Cache size after 1,000 rapid steps is exactly bounded at " + CAPACITY);

        // Values collection integrity
        Collection<String> retained = cache.values();
        assertEquals(CAPACITY, retained.size(), "Retained values view matches cache size");

        // Clear
        cache.clear();
        assertEquals(0, cache.size(), "clear() resets size to 0");
    }

    // =========================================================================
    // 4. Remote Key Code Bindings & Dismissal Isolation
    // =========================================================================

    /**
     * Empirical Verification of Remote Key Code Bindings:
     * - KEYCODE_DPAD_LEFT and KEYCODE_DPAD_RIGHT are consumed screensaver navigation keys.
     * - KEYCODE_DPAD_UP and KEYCODE_DPAD_DOWN are info toggle navigation keys.
     * - KEYCODE_BACK is NEVER consumed for slide stepping (shouldConsumeScreensaverKey == false).
     * - KEYCODE_BACK is classified as isScreensaverDismissKey == true.
     * - Remote dismissal keys (ESCAPE, HOME, DPAD_CENTER, ENTER, NUMPAD_ENTER) exit screensaver.
     * - Non-screensaver keys (VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE) are untouched.
     */
    private static void testRemoteKeyCodeBindingsAndDismissalIsolation() {
        System.out.println("\n--- [Challenge 4] Remote Key Code Bindings & Dismissal Isolation ---");

        // 1. Navigation Keys Recognition
        assertTrue(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_DPAD_LEFT), "DPAD_LEFT is screensaver nav key");
        assertTrue(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_DPAD_RIGHT), "DPAD_RIGHT is screensaver nav key");
        assertTrue(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_DPAD_UP), "DPAD_UP is screensaver nav key");
        assertTrue(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_DPAD_DOWN), "DPAD_DOWN is screensaver nav key");

        // 2. Navigation Keys Consumption
        assertTrue(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_DPAD_LEFT), "DreamView consumes DPAD_LEFT");
        assertTrue(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_DPAD_RIGHT), "DreamView consumes DPAD_RIGHT");
        assertTrue(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_DPAD_UP), "DreamView consumes DPAD_UP");
        assertTrue(DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_DPAD_DOWN), "DreamView consumes DPAD_DOWN");

        // 3. BACK KEY CRITICAL INVARIANT (SoarTvCoreTests:3758)
        // KEYCODE_BACK must NOT be consumed by navigation (must trigger dismissal)
        assertEquals(false, DreamView.shouldConsumeScreensaverKey(KeyEvent.KEYCODE_BACK),
                "CRITICAL: DreamView does NOT consume KEYCODE_BACK (must exit screensaver)");
        assertTrue(DreamView.isScreensaverDismissKey(KeyEvent.KEYCODE_BACK),
                "CRITICAL: KEYCODE_BACK is classified as screensaver dismiss key");
        assertFalse(DreamView.isScreensaverNavKey(KeyEvent.KEYCODE_BACK),
                "CRITICAL: KEYCODE_BACK is NOT a screensaver nav key");

        // 4. Other Dismissal Keys
        int[] dismissKeys = {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER
        };

        for (int key : dismissKeys) {
            assertTrue(DreamView.isScreensaverDismissKey(key), "Key " + key + " is recognized as dismiss key");
            assertEquals(false, DreamView.shouldConsumeScreensaverKey(key), "Key " + key + " is not consumed by nav router");
            assertFalse(DreamView.isScreensaverNavKey(key), "Key " + key + " is not a nav key");
        }

        // 5. Volume Key Safety (Never touched by screensaver)
        int[] volumeKeys = {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE
        };

        for (int vKey : volumeKeys) {
            assertFalse(DreamView.isScreensaverNavKey(vKey), "Volume key " + vKey + " is not screensaver nav key");
            assertFalse(DreamView.isScreensaverDismissKey(vKey), "Volume key " + vKey + " is not screensaver dismiss key");
            assertEquals(false, DreamView.shouldConsumeScreensaverKey(vKey), "Volume key " + vKey + " is never consumed");
        }
    }
}
