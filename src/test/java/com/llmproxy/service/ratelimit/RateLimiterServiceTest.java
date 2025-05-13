package com.llmproxy.service.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterServiceTest {

    private ExecutorService executor;

    @BeforeEach
    void setupExecutor() {
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void teardownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void allow_withinLimit_returnsTrue() {
        RateLimiterService limiter = new RateLimiterService(60, 10);

        assertTrue(limiter.allow());
    }

    @Test
    void allow_exceedsLimit_returnsFalse() {
        RateLimiterService limiter = new RateLimiterService(60, 3);

        assertTrue(limiter.allow());
        assertTrue(limiter.allow());
        assertTrue(limiter.allow());

        assertFalse(limiter.allow());
    }

    @Test
    void allowClient_withinLimit_returnsTrue() {
        RateLimiterService limiter = new RateLimiterService(60, 10);

        assertTrue(limiter.allowClient("client1"));
    }

    @Test
    void allowClient_differentClients_separateLimits() {
        RateLimiterService limiter = new RateLimiterService(60, 2);

        assertTrue(limiter.allowClient("client1"));
        assertTrue(limiter.allowClient("client1"));
        assertFalse(limiter.allowClient("client1"));

        assertTrue(limiter.allowClient("client2"));
        assertTrue(limiter.allowClient("client2"));
        assertFalse(limiter.allowClient("client2"));
    }

    @Test
    void allowClient_withCustomFunction_usesFunction() {
        RateLimiterService limiter = new RateLimiterService(60, 10);

        AtomicInteger callCount = new AtomicInteger(0);
        Function<String, Boolean> customFunc = clientId -> {
            callCount.incrementAndGet();
            return "allowed".equals(clientId);
        };

        limiter.setAllowClientFunc(customFunc);

        assertTrue(limiter.allowClient("allowed"));
        assertFalse(limiter.allowClient("blocked"));
        assertEquals(2, callCount.get());
    }

    @Test
    void allow_zeroRefillRate_tokensDoNotRefill() {
        RateLimiterService limiter = new RateLimiterService(0, 3); // 0 requests per minute, burst 3
        
        assertTrue(limiter.allow()); // Consumes 1st token
        assertTrue(limiter.allow()); // Consumes 2nd token
        assertTrue(limiter.allow()); // Consumes 3rd token
        
        // All tokens consumed, and refill rate is 0
        assertFalse(limiter.allow()); // Should be false as no tokens can be refilled
        assertFalse(limiter.allow()); // Should remain false
    }

    @Test
    void allowClient_zeroRefillRate_clientTokensDoNotRefill() {
        // Parent limiter has 0 refill rate, so client-specific limiters will also have 0 refill rate.
        RateLimiterService parentLimiter = new RateLimiterService(0, 2); 

        // Test for client1
        assertTrue(parentLimiter.allowClient("client1")); // Uses 1st token for client1
        assertTrue(parentLimiter.allowClient("client1")); // Uses 2nd token for client1
        assertFalse(parentLimiter.allowClient("client1"));// No more tokens for client1, should not refill
        assertFalse(parentLimiter.allowClient("client1"));// Still no tokens for client1

        // Test for client2 (should be independent but also not refill)
        assertTrue(parentLimiter.allowClient("client2")); // Uses 1st token for client2
        assertTrue(parentLimiter.allowClient("client2")); // Uses 2nd token for client2
        assertFalse(parentLimiter.allowClient("client2"));// No more tokens for client2, should not refill
    }

    @Test
    void allow_zeroBurst_alwaysReturnsFalse() {
        RateLimiterService limiter = new RateLimiterService(60, 0); // 60 requests per minute, burst 0
        
        // With zero burst capacity, no tokens are ever available.
        assertFalse(limiter.allow());
        assertFalse(limiter.allow()); // Should remain false even if time passes, as maxTokens is 0.
    }

    @Test
    void allowClient_zeroBurst_alwaysReturnsFalseForClients() {
        // Parent limiter has 0 burst, so client-specific limiters will also have 0 burst.
        RateLimiterService parentLimiter = new RateLimiterService(60, 0); 

        // Test for client1
        assertFalse(parentLimiter.allowClient("client1")); // Client limiter inherits zero burst
        assertFalse(parentLimiter.allowClient("client1"));

        // Test for client2
        assertFalse(parentLimiter.allowClient("client2")); // Client limiter inherits zero burst
    }

     // --- PERFORMANCE BENCHMARK TESTS ---

    @Test
    @DisplayName("Throughput benchmark: single-threaded, default/global limiter")
    void throughput_singleThread_globalLimiter() {
        RateLimiterService limiter = new RateLimiterService(600, 100); // 10 QPS global for 10 seconds window
        int attempts = 100;
        long start = System.nanoTime();
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.allow()) allowed++;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Within burst, all should be allowed
        assertEquals(attempts, allowed);
        System.out.println("[throughput_singleThread_globalLimiter] Time: " + elapsedMs + " ms for " + allowed + " ops");
    }

    @Test
    @DisplayName("Throughput benchmark: multi-threaded, global limiter, burst exhaustion")
    void throughput_multiThread_globalLimiter_burstLimit() throws InterruptedException {
        final int burst = 20;
        final int threads = 8;
        final RateLimiterService limiter = new RateLimiterService(600, burst); // 10 QPS, burst of 20
        AtomicInteger accepted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        int attemptsPerThread = 10;

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                for (int i = 0; i < attemptsPerThread; i++) {
                    if (limiter.allow()) accepted.incrementAndGet();
                    else rejected.incrementAndGet();
                }
                return null;
            });
        }
        long start = System.nanoTime();
        executor.invokeAll(tasks);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(burst, accepted.get());
        assertEquals(threads * attemptsPerThread - burst, rejected.get());
        System.out.println("[throughput_multiThread_globalLimiter_burstLimit] Time: " + elapsedMs + " ms, Accepted: " + accepted.get() + ", Rejected: " + rejected.get());
    }

    @Test
    @DisplayName("Latency benchmark: multi-threaded, global limiter")
    void latency_multiThreaded_globalLimiter() throws InterruptedException {
        final int burst = 40;
        final int threads = 20;
        final RateLimiterService limiter = new RateLimiterService(1000, burst);
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Long> latencies = new CopyOnWriteArrayList<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                barrier.await();
                long t0 = System.nanoTime();
                limiter.allow();
                latencies.add(System.nanoTime() - t0);
                return null;
            });
        }
        executor.invokeAll(tasks);

        double avgLatencyUs = latencies.stream().mapToLong(x -> x).average().orElse(0) / 1000.0;
        long maxLatencyUs = latencies.stream().mapToLong(x -> x).max().orElse(0) / 1000;
        System.out.println("[latency_multiThreaded_globalLimiter] Average: " + avgLatencyUs + " μs, Max: " + maxLatencyUs + " μs");
        assertTrue(avgLatencyUs < 5000); // Should be low for single op in-memory
    }

    @Test
    @DisplayName("Throughput & isolation: multi-threaded, client-specific, distinct limits")
    void throughput_multiClient_isolatedLimits() throws InterruptedException {
        final int clientCount = 10;
        final int burst = 5;
        final RateLimiterService limiter = new RateLimiterService(100, burst); // generous refill for test
        List<String> clientIds = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) clientIds.add("client" + i);

        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (String client : clientIds) {
            tasks.add(() -> {
                // Let's try 10 attempts per client
                for (int i = 0; i < 10; i++) {
                    if (limiter.allowClient(client)) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                }
                return null;
            });
        }

        long start = System.nanoTime();
        executor.invokeAll(tasks);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Each client can burst up to 5, so allowed = 5 * clientCount, rejected = rest
        assertEquals(burst * clientCount, allowed.get());
        assertEquals((10 - burst) * clientCount, rejected.get());
        System.out.println("[throughput_multiClient_isolatedLimits] Time: " + elapsedMs + " ms, Allowed: " + allowed.get() + ", Rejected: " + rejected.get());
    }

    @Test
    @DisplayName("Client-specific: variable client counts & request rates")
    void clientSpecific_variablePatterns_benchmark() throws InterruptedException {
        final int[] clientCounts = {1, 5, 20, 50};
        final int burst = 20;
        for (int c = 0; c < clientCounts.length; c++) {
            int numClients = clientCounts[c];
            RateLimiterService limiter = new RateLimiterService(200, burst);
            AtomicInteger allowed = new AtomicInteger(0);
            AtomicInteger rejected = new AtomicInteger(0);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < numClients; i++) {
                String client = "client-" + i;
                tasks.add(() -> {
                    for (int j = 0; j < burst + 10; j++) {
                        if (limiter.allowClient(client)) allowed.incrementAndGet();
                        else rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            long start = System.nanoTime();
            executor.invokeAll(tasks);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            int expectedAllowed = burst * numClients;
            int expectedRejected = 10 * numClients;
            assertEquals(expectedAllowed, allowed.get());
            assertEquals(expectedRejected, rejected.get());
            System.out.printf("[clientSpecific_variablePatterns_benchmark] Clients: %d, Time: %d ms, Allowed: %d, Rejected: %d%n", numClients, elapsedMs, allowed.get(), rejected.get());
        }
    }

    @Test
    @DisplayName("Resource usage: stress test with many clients and threads")
    void resourceUsage_stress_highConcurrency_manyClients() throws InterruptedException {
        final int clientCount = 100;
        final int threads = 50;
        final int burst = 10;
        RateLimiterService limiter = new RateLimiterService(1000, burst);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        List<String> clientIds = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) clientIds.add("client-" + i);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                for (String clientId : clientIds) {
                    // Each thread does 5 attempts per client (should be over burst for most)
                    for (int att = 0; att < 5; att++) {
                        if (limiter.allowClient(clientId)) allowed.incrementAndGet();
                        else rejected.incrementAndGet();
                    }
                }
                return null;
            });
        }
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long start = System.nanoTime();
        executor.invokeAll(tasks);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long memDeltaKb = (memAfter - memBefore) / 1024;
        System.out.printf("[resourceUsage_stress_highConcurrency_manyClients] Time: %d ms, Memory used: %d KB, Allowed: %d, Rejected: %d%n",
                elapsedMs, memDeltaKb, allowed.get(), rejected.get());
        // We cannot assert exactly due to concurrent race but numbers should be reasonable
        assertTrue(allowed.get() > 0);
        assertTrue(rejected.get() > 0);
        assertTrue(memDeltaKb >= 0);
    }

    @Test
    @DisplayName("Latency under exhaustion: repeated over-limit calls")
    void latency_underExhaustion() {
        RateLimiterService limiter = new RateLimiterService(60, 3);

        // Exhaust burst limit
        for (int i = 0; i < 3; i++) assertTrue(limiter.allow());
        assertFalse(limiter.allow());

        // Now, measure latency when over limit
        long t0 = System.nanoTime();
        boolean allowed = limiter.allow();
        long elapsedUs = (System.nanoTime() - t0) / 1_000;
        assertFalse(allowed);
        System.out.println("[latency_underExhaustion] Over-limit check latency: " + elapsedUs + " μs (should be very low)");
    }
}
