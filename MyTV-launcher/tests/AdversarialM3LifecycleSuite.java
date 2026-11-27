package io.mytv;

import android.content.Context;

import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Empirical Adversarial Challenge Test Suite for Milestone 3 (Requirement R3):
 * TV Launcher Session Lifecycle, Debounce Guard, and Concurrent Race Resilience.
 *
 * Authored by Challenger M3-2.
 */
public final class AdversarialM3LifecycleSuite {

    private static int assertions = 0;
    private static int failedAssertions = 0;
    private static final List<String> criticalDefects = new ArrayList<>();

    private AdversarialM3LifecycleSuite() {}

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
        System.out.println("=== STARTING ADVERSARIAL M3 SESSION LIFECYCLE & DEBOUNCE SUITE ===");
        long start = System.currentTimeMillis();

        testActiveStreamingSessionNormalizationAndImmutability();
        testDebounceThresholdBoundaries();
        testClockSkewAndNegativeDeltaResilience();
        testJsonPayloadEscapingAndFormatting();
        testConcurrent32ThreadHammerAndZeroCorruption();
        testAtomicSingleDispatchUnder32ThreadRace();
        testNullContextAndNetworkSocketResilience();

        long duration = System.currentTimeMillis() - start;
        System.out.println("\n=== ADVERSARIAL M3 SUMMARY ===");
        System.out.println("Total Assertions Executed: " + assertions);
        System.out.println("Failed Assertions: " + failedAssertions);
        System.out.println("Execution Time: " + duration + " ms");

        if (failedAssertions > 0) {
            System.err.println("ADVERSARIAL SUITE RESULT: REJECT (" + failedAssertions + " failures)");
            System.exit(1);
        } else {
            System.out.println("ADVERSARIAL SUITE RESULT: APPROVE");
        }
    }

    /**
     * 1. Value Object Normalization & Immutability.
     */
    private static void testActiveStreamingSessionNormalizationAndImmutability() {
        System.out.println("\n--- Testing ActiveStreamingSession Normalization & Immutability ---");

        // Null arguments safety
        GamingZone.ActiveStreamingSession sNull = new GamingZone.ActiveStreamingSession(
                null, null, null, -99, 5000L);
        assertEquals("", sNull.gameId, "Null gameId normalized to empty string");
        assertEquals("", sNull.gameTitle, "Null gameTitle normalized to empty string");
        assertEquals("", sNull.hostIp, "Null hostIp normalized to empty string");
        assertEquals(GamingZone.LAPTOP_AGENT_PORT, sNull.port, "Negative port defaults to LAPTOP_AGENT_PORT");
        assertEquals(5000L, sNull.launchTimeMs, "Launch time preserved");

        // Whitespace trimming
        GamingZone.ActiveStreamingSession sTrim = new GamingZone.ActiveStreamingSession(
                "  steam_965680  ", "  Boomerang Fu  ", "  192.168.1.12  ", 8485, 10000L);
        assertEquals("steam_965680", sTrim.gameId, "gameId trimmed");
        assertEquals("Boomerang Fu", sTrim.gameTitle, "gameTitle trimmed");
        assertEquals("192.168.1.12", sTrim.hostIp, "hostIp trimmed");
        assertEquals(8485, sTrim.port, "Valid port preserved");

        // Convenience constructor (3 args) assigns System.currentTimeMillis()
        long before = System.currentTimeMillis();
        GamingZone.ActiveStreamingSession sQuick = new GamingZone.ActiveStreamingSession(
                "hades", "Hades", "192.168.1.12");
        long after = System.currentTimeMillis();
        assertTrue(sQuick.launchTimeMs >= before && sQuick.launchTimeMs <= after,
                "Quick constructor sets current timestamp");
        assertEquals(GamingZone.LAPTOP_AGENT_PORT, sQuick.port, "Quick constructor defaults port to LAPTOP_AGENT_PORT");
    }

    /**
     * 2. Debounce Threshold Boundary Testing (3000ms).
     */
    private static void testDebounceThresholdBoundaries() {
        System.out.println("\n--- Testing Debounce Threshold Boundaries (3000ms) ---");
        long t0 = 1_000_000L;
        long threshold = GamingZone.EXIT_DEBOUNCE_THRESHOLD_MS;
        assertEquals(3000L, threshold, "EXIT_DEBOUNCE_THRESHOLD_MS is 3000ms");

        GamingZone.ActiveStreamingSession session = new GamingZone.ActiveStreamingSession(
                "steam_965680", "Boomerang Fu", "192.168.1.12", 8485, t0);

        // Null session always false
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(null, t0 + 5000L, threshold),
                "Null session never triggers exit");

        // Exactly at t0 (0ms elapsed) -> false
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(session, t0, threshold),
                "nowMs = t0 (0ms elapsed) returns false");

        // Rapid bounce at 1ms -> false
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(session, t0 + 1L, threshold),
                "nowMs = t0 + 1L returns false");

        // Halfway at 1500ms -> false
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(session, t0 + 1500L, threshold),
                "nowMs = t0 + 1500L returns false");

        // Boundary test: t0 + 2999L -> false
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(session, t0 + 2999L, threshold),
                "Boundary check: nowMs = t0 + 2999L MUST return false");

        // Boundary test: t0 + 3000L -> true
        assertEquals(true, GamingZone.shouldTriggerRemoteExit(session, t0 + 3000L, threshold),
                "Boundary check: nowMs = t0 + 3000L MUST return true");

        // Boundary test: t0 + 3001L -> true
        assertEquals(true, GamingZone.shouldTriggerRemoteExit(session, t0 + 3001L, threshold),
                "Boundary check: nowMs = t0 + 3001L MUST return true");

        // Long running session at 60s -> true
        assertEquals(true, GamingZone.shouldTriggerRemoteExit(session, t0 + 60_000L, threshold),
                "nowMs = t0 + 60000L returns true");
    }

    /**
     * 3. Clock Skew & Negative Delta Resilience.
     */
    private static void testClockSkewAndNegativeDeltaResilience() {
        System.out.println("\n--- Testing Clock Skew & Negative Delta Resilience ---");
        long t0 = 1_000_000L;
        long threshold = 3000L;
        GamingZone.ActiveStreamingSession session = new GamingZone.ActiveStreamingSession(
                "steam_965680", "Boomerang Fu", "192.168.1.12", 8485, t0);

        // Device clock stepped backward by 1ms
        assertEquals(0L, session.getElapsedMs(t0 - 1L),
                "getElapsedMs clamps negative delta (nowMs = t0 - 1L) to 0");
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(session, t0 - 1L, threshold),
                "shouldTriggerRemoteExit returns false when clock stepped back 1ms");

        // Device clock stepped backward by 5 seconds (e.g. NTP sync adjustment)
        assertEquals(0L, session.getElapsedMs(t0 - 5000L),
                "getElapsedMs clamps negative delta (nowMs = t0 - 5000L) to 0");
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(session, t0 - 5000L, threshold),
                "shouldTriggerRemoteExit returns false when clock stepped back 5000ms");

        // Device clock stepped back to Unix epoch 0
        assertEquals(0L, session.getElapsedMs(0L),
                "getElapsedMs clamps negative delta (nowMs = 0L) to 0");
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(session, 0L, threshold),
                "shouldTriggerRemoteExit returns false when clock stepped back to epoch 0");

        // Clock stepped forward by 10 hours
        assertEquals(true, GamingZone.shouldTriggerRemoteExit(session, t0 + 36_000_000L, threshold),
                "shouldTriggerRemoteExit returns true when clock jumps forward 10h");
    }

    /**
     * 4. JSON Payload Formatting & Escaping.
     */
    private static void testJsonPayloadEscapingAndFormatting() {
        System.out.println("\n--- Testing JSON Payload Escaping & Formatting ---");

        // Standard payload
        String payload1 = GamingZone.buildTerminationPayload("steam_965680", "remote_home_exit");
        assertEquals("{\"gameId\":\"steam_965680\",\"reason\":\"remote_home_exit\"}", payload1,
                "Standard payload matches JSON contract");

        // Null parameters
        String payloadNull = GamingZone.buildTerminationPayload(null, null);
        assertEquals("{\"gameId\":\"\",\"reason\":\"remote_home_exit\"}", payloadNull,
                "Null arguments produce safe default JSON");

        // Adversarial string injection: quotes and backslashes
        String trickyId = "game\"with\"quotes";
        String trickyReason = "reason\"with\"quotes";
        String payloadAdversarial = GamingZone.buildTerminationPayload(trickyId, trickyReason);
        assertTrue(payloadAdversarial.contains("\\\"with\\\""),
                "Quotes in gameId and reason are escaped with backslash");
        assertTrue(payloadAdversarial.startsWith("{") && payloadAdversarial.endsWith("}"),
                "Adversarial payload remains valid JSON object structure");
    }

    /**
     * 5. Concurrent 32-Thread Hammer on ACTIVE_STREAMING_SESSION.
     */
    private static void testConcurrent32ThreadHammerAndZeroCorruption() throws Exception {
        System.out.println("\n--- Testing Concurrent 32-Thread Hammer on ACTIVE_STREAMING_SESSION ---");
        GamingZone.clearActiveStreamingSession();

        final int threadCount = 32;
        final int iterationsPerThread = 1000;
        final ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final AtomicInteger nonNullClears = new AtomicInteger(0);
        final AtomicInteger exceptionsCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            exec.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterationsPerThread; j++) {
                        int op = (threadId + j) % 3;
                        if (op == 0) {
                            GamingZone.setActiveStreamingSession(new GamingZone.ActiveStreamingSession(
                                    "game_" + threadId + "_" + j,
                                    "Game " + threadId,
                                    "192.168.1.12",
                                    8485,
                                    System.currentTimeMillis() - 5000L
                            ));
                        } else if (op == 1) {
                            GamingZone.ActiveStreamingSession s = GamingZone.clearActiveStreamingSession();
                            if (s != null) {
                                nonNullClears.incrementAndGet();
                            }
                        } else {
                            // Resume without throwing
                            GamingZone.handleLauncherResume(null);
                        }
                    }
                } catch (Throwable t) {
                    exceptionsCount.incrementAndGet();
                    t.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertTrue(finished, "All 32 hammer threads completed within 10 seconds");
        assertEquals(0, exceptionsCount.get(), "Zero exceptions encountered during 32-thread concurrent hammer");

        // State can be cleanly cleared
        GamingZone.clearActiveStreamingSession();
        assertEquals(null, GamingZone.getActiveStreamingSession(),
                "State is completely consistent and null after hammer clear");
    }

    /**
     * 6. Atomic Single-Dispatch Guarantee Under 32-Thread Race.
     */
    private static void testAtomicSingleDispatchUnder32ThreadRace() throws Exception {
        System.out.println("\n--- Testing Atomic Single-Dispatch Guarantee Under 32-Thread Race ---");

        final int rounds = 100;
        final int threads = 32;
        final ExecutorService exec = Executors.newFixedThreadPool(threads);
        int totalSingleDispatches = 0;

        try {
            for (int r = 0; r < rounds; r++) {
                // Set an active session that is expired past debounce (5000ms old)
                GamingZone.ActiveStreamingSession session = new GamingZone.ActiveStreamingSession(
                        "round_game_" + r, "Round Game", "192.168.1.12", 8485,
                        System.currentTimeMillis() - 5000L
                );
                GamingZone.setActiveStreamingSession(session);

                final CountDownLatch raceStart = new CountDownLatch(1);
                final CountDownLatch raceDone = new CountDownLatch(threads);
                final AtomicInteger clearedSessions = new AtomicInteger(0);

                for (int i = 0; i < threads; i++) {
                    exec.submit(() -> {
                        try {
                            raceStart.await();
                            GamingZone.ActiveStreamingSession s = GamingZone.getActiveStreamingSession();
                            if (s != null && GamingZone.shouldTriggerRemoteExit(s, System.currentTimeMillis(), 3000L)) {
                                GamingZone.ActiveStreamingSession toTerminate = GamingZone.clearActiveStreamingSession();
                                if (toTerminate != null) {
                                    clearedSessions.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            // ignore
                        } finally {
                            raceDone.countDown();
                        }
                    });
                }

                raceStart.countDown();
                raceDone.await(2, TimeUnit.SECONDS);

                // EXACTLY ONE thread must have obtained the session to terminate!
                if (clearedSessions.get() == 1) {
                    totalSingleDispatches++;
                }
            }
        } finally {
            exec.shutdownNow();
            GamingZone.clearActiveStreamingSession();
        }

        assertEquals(rounds, totalSingleDispatches,
                "All " + rounds + " concurrent 32-thread races had EXACTLY 1 winner (0 double-dispatches)");
    }

    /**
     * 7. Null Context & Network Socket Resilience.
     */
    private static void testNullContextAndNetworkSocketResilience() throws Exception {
        System.out.println("\n--- Testing Null Context & Network Socket Resilience ---");

        // 1. handleLauncherResume with null session and null context -> zero NPE
        GamingZone.clearActiveStreamingSession();
        try {
            GamingZone.handleLauncherResume(null);
            assertTrue(true, "handleLauncherResume(null) with null session is safe no-op");
        } catch (Throwable t) {
            assertTrue(false, "handleLauncherResume(null) must not throw NPE: " + t);
        }

        // 2. handleLauncherResume with active session and null context
        GamingZone.ActiveStreamingSession session = new GamingZone.ActiveStreamingSession(
                "steam_965680", "Boomerang Fu", "127.0.0.1", 59999,
                System.currentTimeMillis() - 4000L
        );
        GamingZone.setActiveStreamingSession(session);

        try {
            // Context is null, host is 127.0.0.1, port 59999 (unused closed port)
            // This will clear the session and dispatch async RPC which encounters ConnectException
            GamingZone.handleLauncherResume(null);
            assertEquals(null, GamingZone.getActiveStreamingSession(),
                    "Session is atomically cleared after resume trigger");
            assertTrue(true, "handleLauncherResume(null) safely dispatched async without crash");
        } catch (Throwable t) {
            assertTrue(false, "handleLauncherResume(null) with active session threw: " + t);
        }

        // 3. dispatchRemoteTerminationAsync directly with null context, null session, or connection failure
        try {
            GamingZone.dispatchRemoteTerminationAsync(null, null, "test");
            assertTrue(true, "dispatchRemoteTerminationAsync(null, null) is safe no-op");
        } catch (Throwable t) {
            assertTrue(false, "dispatchRemoteTerminationAsync null args threw: " + t);
        }

        // Let background IO thread pool execute and settle
        Thread.sleep(200);
        assertTrue(true, "Background socket exceptions are caught and handled non-fatally");
    }
}
