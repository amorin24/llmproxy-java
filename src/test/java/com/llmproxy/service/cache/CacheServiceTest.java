package com.llmproxy.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmproxy.model.ModelType;
import com.llmproxy.model.QueryRequest;
import com.llmproxy.model.QueryResponse;
import com.llmproxy.model.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class CacheServiceTest {

    private static final Logger logger = LoggerFactory.getLogger(CacheServiceTest.class);
    
    private final AtomicReference<CacheService> cacheServiceRef = new AtomicReference<>();
    private final AtomicReference<QueryRequest> requestRef = new AtomicReference<>();
    private final AtomicReference<QueryResponse> responseRef = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        cacheServiceRef.set(new CacheService(true, 300, 1000, new ObjectMapper()));
        
        requestRef.set(QueryRequest.builder()
                .query("Test query")
                .model(ModelType.OPENAI)
                .taskType(TaskType.TEXT_GENERATION)
                .build());
        
        responseRef.set(QueryResponse.builder()
                .response("Test response")
                .model(ModelType.OPENAI)
                .build());
    }

    @Test
    void get_cacheDisabled_returnsNull() {
        CacheService disabledCache = new CacheService(false, 300, 1000, new ObjectMapper());
        disabledCache.set(requestRef.get(), responseRef.get());
        
        assertNull(disabledCache.get(requestRef.get()));
    }

    @Test
    void get_cacheEnabled_cacheMiss_returnsNull() {
        assertNull(cacheServiceRef.get().get(requestRef.get()));
    }

    @Test
    void get_cacheEnabled_cacheHit_returnsResponse() {
        CacheService cacheService = cacheServiceRef.get();
        QueryRequest request = requestRef.get();
        QueryResponse response = responseRef.get();
        
        cacheService.set(request, response);
        
        QueryResponse cachedResponse = cacheService.get(request);
        assertNotNull(cachedResponse);
        assertEquals(response.getResponse(), cachedResponse.getResponse());
        assertEquals(response.getModel(), cachedResponse.getModel());
    }

    @Test
    void set_cacheDisabled_doesNothing() {
        CacheService disabledCache = new CacheService(false, 300, 1000, new ObjectMapper());
        disabledCache.set(requestRef.get(), responseRef.get());
        
        assertNull(disabledCache.get(requestRef.get()));
    }

    @Test
    void set_cacheEnabled_storesResponse() {
        CacheService cacheService = cacheServiceRef.get();
        QueryRequest request = requestRef.get();
        QueryResponse response = responseRef.get();
        
        cacheService.set(request, response);
        
        QueryResponse cachedResponse = cacheService.get(request);
        assertNotNull(cachedResponse);
        assertEquals(response.getResponse(), cachedResponse.getResponse());
    }

    @Test
    void generateCacheKey_differentQueries_differentKeys() {
        CacheService cacheService = cacheServiceRef.get();
        QueryResponse response = responseRef.get();
        
        QueryRequest request1 = QueryRequest.builder()
                .query("Query 1")
                .model(ModelType.OPENAI)
                .build();
        
        QueryRequest request2 = QueryRequest.builder()
                .query("Query 2")
                .model(ModelType.OPENAI)
                .build();
        
        cacheService.set(request1, response);
        
        assertNotNull(cacheService.get(request1));
        assertNull(cacheService.get(request2));
    }

    @Test
    void generateCacheKey_differentModels_differentKeys() {
        CacheService cacheService = cacheServiceRef.get();
        QueryResponse response = responseRef.get();
        
        QueryRequest request1 = QueryRequest.builder()
                .query("Test query")
                .model(ModelType.OPENAI)
                .build();
        
        QueryRequest request2 = QueryRequest.builder()
                .query("Test query")
                .model(ModelType.GEMINI)
                .build();
        
        cacheService.set(request1, response);
        
        assertNotNull(cacheService.get(request1));
        assertNull(cacheService.get(request2));
    }

    @Test
    void generateCacheKey_differentTaskTypes_differentKeys() {
        CacheService cacheService = cacheServiceRef.get();
        QueryResponse response = responseRef.get();
        
        QueryRequest request1 = QueryRequest.builder()
                .query("Test query")
                .taskType(TaskType.TEXT_GENERATION)
                .build();
        
        QueryRequest request2 = QueryRequest.builder()
                .query("Test query")
                .taskType(TaskType.SUMMARIZATION)
                .build();
        
        cacheService.set(request1, response);
        
        assertNotNull(cacheService.get(request1));
        assertNull(cacheService.get(request2));
    }
    
    @Test
    @Execution(ExecutionMode.SAME_THREAD)
    void cacheExpiration_shortDuration_entriesExpireQuickly() throws InterruptedException {
        // Set up cache with very short expiration time (1 second)
        int shortExpirationTime = 1; // seconds
        CacheService shortCache = new CacheService(true, shortExpirationTime, 1000, new ObjectMapper());
        QueryRequest request = requestRef.get();
        QueryResponse response = responseRef.get();
        
        logger.info("Testing cache with expiration time: {} seconds", shortExpirationTime);
        
        shortCache.set(request, response);
        
        // Verify item is in the cache
        assertNotNull(shortCache.get(request));
        
        // Wait for the entry to expire
        TimeUnit.SECONDS.sleep(shortExpirationTime + 1);
        
        // Verify item is no longer in the cache
        assertNull(shortCache.get(request), 
                "Cache entry should have expired after " + shortExpirationTime + " seconds");
    }
    
    @Test
    @Execution(ExecutionMode.SAME_THREAD)
    void cacheExpiration_moderateDuration_entriesExpireAfterTimeout() throws InterruptedException {
        // Set up cache with moderate expiration time (2 seconds)
        int moderateExpirationTime = 2; // seconds
        CacheService moderateCache = new CacheService(true, moderateExpirationTime, 1000, new ObjectMapper());
        QueryRequest request = requestRef.get();
        QueryResponse response = responseRef.get();
        
        logger.info("Testing cache with expiration time: {} seconds", moderateExpirationTime);
        
        moderateCache.set(request, response);
        
        // Verify item is in the cache
        assertNotNull(moderateCache.get(request));
        
        // Check that it's still there after half the expiration time
        TimeUnit.MILLISECONDS.sleep(moderateExpirationTime * 500);
        assertNotNull(moderateCache.get(request), 
                "Cache entry should still exist after half the expiration time");
        
        // Wait for the entry to expire
        TimeUnit.MILLISECONDS.sleep(moderateExpirationTime * 1500);
        
        // Verify item is no longer in the cache
        assertNull(moderateCache.get(request), 
                "Cache entry should have expired after " + moderateExpirationTime + " seconds");
    }
    
    @Test
    @Execution(ExecutionMode.SAME_THREAD)
    void cacheExpiration_longDuration_entriesRemainValid() throws InterruptedException {
        // Set up cache with longer expiration time
        int longExpirationTime = 5; // seconds
        CacheService longCache = new CacheService(true, longExpirationTime, 1000, new ObjectMapper());
        QueryRequest request = requestRef.get();
        QueryResponse response = responseRef.get();
        
        logger.info("Testing cache with expiration time: {} seconds", longExpirationTime);
        
        longCache.set(request, response);
        
        // Verify item is in the cache initially
        assertNotNull(longCache.get(request));
        
        // Wait for some time (but less than expiration)
        TimeUnit.SECONDS.sleep(1);
        
        // Verify item is still in the cache
        assertNotNull(longCache.get(request), 
                "Cache entry should still exist before expiration time");
    }

    @Test
    @Tag("performance")
    void performanceBenchmark_lookupSpeed() {
        CacheService cacheService = cacheServiceRef.get();
        
        // Warm-up phase
        int warmupItems = 20;
        List<QueryRequest> warmupRequests = generateUniqueRequests(warmupItems);
        for (int i = 0; i < warmupItems; i++) {
            QueryResponse resp = QueryResponse.builder()
                    .response("Warmup response " + i)
                    .model(ModelType.OPENAI)
                    .build();
            cacheService.set(warmupRequests.get(i), resp);
            cacheService.get(warmupRequests.get(i)); // Warm up get operation
        }
        
        // Prepare a pre-populated cache
        int numItems = 100;
        List<QueryRequest> requests = new ArrayList<>();
        
        // Generate and cache requests
        for (int i = 0; i < numItems; i++) {
            QueryRequest req = QueryRequest.builder()
                    .query("Performance test query " + i)
                    .model(ModelType.OPENAI)
                    .taskType(TaskType.TEXT_GENERATION)
                    .requestId(UUID.randomUUID().toString())
                    .build();
                    
            QueryResponse resp = QueryResponse.builder()
                    .response("Test response " + i)
                    .model(ModelType.OPENAI)
                    .build();
                    
            cacheService.set(req, resp);
            requests.add(req);
        }
        
        // Benchmark lookups with multiple iterations for more accurate results
        int iterations = 5;
        double[] iterationTimes = new double[iterations];
        
        for (int iter = 0; iter < iterations; iter++) {
            long startTime = System.nanoTime();
            for (QueryRequest req : requests) {
                QueryResponse resp = cacheService.get(req);
                assertNotNull(resp);
            }
            long endTime = System.nanoTime();
            
            iterationTimes[iter] = (endTime - startTime) / (double)(numItems * 1_000_000);
        }
        
        // Calculate median time (more stable than average)
        java.util.Arrays.sort(iterationTimes);
        double medianLookupTimeMs = iterationTimes[iterations / 2];
        double avgLookupTimeMs = java.util.Arrays.stream(iterationTimes).average().orElse(0);
        
        logger.info("Cache lookup performance: Median={} ms, Avg={} ms", 
                String.format("%.3f", medianLookupTimeMs),
                String.format("%.3f", avgLookupTimeMs));
    }
    
    @Test
    @Tag("performance")
    void performanceBenchmark_insertionSpeed() {
        CacheService cacheService = cacheServiceRef.get();
        int numItems = 1000;
        List<QueryRequest> requests = generateUniqueRequests(numItems);
        
        // Warm-up phase
        int warmupItems = 50;
        for (int i = 0; i < warmupItems; i++) {
            QueryResponse resp = QueryResponse.builder()
                    .response("Warmup response " + i)
                    .model(ModelType.OPENAI)
                    .build();
            cacheService.set(requests.get(i), resp);
        }
        
        // Benchmark insertions with multiple iterations
        int iterations = 5;
        double[] iterationTimes = new double[iterations];
        
        for (int iter = 0; iter < iterations; iter++) {
            // Create a fresh cache for each iteration to avoid measuring cache size impact
            CacheService freshCache = new CacheService(true, 300, 1000, new ObjectMapper());
            
            long startTime = System.nanoTime();
            for (int i = 0; i < numItems; i++) {
                QueryResponse resp = QueryResponse.builder()
                        .response("Performance test response " + i)
                        .model(ModelType.OPENAI)
                        .build();
                        
                freshCache.set(requests.get(i), resp);
            }
            long endTime = System.nanoTime();
            
            iterationTimes[iter] = (endTime - startTime) / (double)(numItems * 1_000_000);
        }
        
        // Calculate median time
        java.util.Arrays.sort(iterationTimes);
        double medianInsertTimeMs = iterationTimes[iterations / 2];
        double avgInsertTimeMs = java.util.Arrays.stream(iterationTimes).average().orElse(0);
        
        logger.info("Cache insertion performance: Median={} ms, Avg={} ms", 
                String.format("%.3f", medianInsertTimeMs),
                String.format("%.3f", avgInsertTimeMs));
    }
    
    @Test
    @Tag("performance")
    void performanceBenchmark_scalingWithSize() {
        // Test with different cache sizes
        int[] cacheSizes = {100, 1000, 5000};
        
        for (int size : cacheSizes) {
            CacheService sizedCache = new CacheService(true, 300, size, new ObjectMapper());
            List<QueryRequest> requests = generateUniqueRequests(size);
            
            // Warmup phase
            int warmupItems = Math.min(size / 10, 100);
            for (int i = 0; i < warmupItems; i++) {
                QueryResponse resp = QueryResponse.builder()
                        .response("Warmup response " + i)
                        .model(ModelType.OPENAI)
                        .build();
                        
                sizedCache.set(requests.get(i), resp);
                sizedCache.get(requests.get(i)); // Warm up get operation
            }
            
            // Measure insertion speed
            long insertStart = System.nanoTime();
            for (int i = 0; i < size; i++) {
                QueryResponse resp = QueryResponse.builder()
                        .response("Scaling test response " + i)
                        .model(ModelType.OPENAI)
                        .build();
                        
                sizedCache.set(requests.get(i), resp);
            }
            long insertEnd = System.nanoTime();
            
            // Measure lookup speed (random access)
            int lookupCount = Math.min(size, 1000);
            long[] lookupTimes = new long[lookupCount];
            
            for (int i = 0; i < lookupCount; i++) {
                int index = (int)(Math.random() * size);
                long start = System.nanoTime();
                sizedCache.get(requests.get(index));
                lookupTimes[i] = System.nanoTime() - start;
            }
            
            double totalInsertTimeMs = (insertEnd - insertStart) / 1_000_000.0;
            double avgInsertTimeMs = totalInsertTimeMs / size;
            double avgLookupTimeNs = java.util.Arrays.stream(lookupTimes).average().orElse(0);
            double avgLookupTimeMs = avgLookupTimeNs / 1_000_000.0;
            double p95LookupTimeMs = calculatePercentile(lookupTimes, 95) / 1_000_000.0;
            
            logger.info("Cache size {}: Avg insert={} ms, Avg lookup={} ms, P95 lookup={} ms",
                    size, 
                    String.format("%.3f", avgInsertTimeMs),
                    String.format("%.3f", avgLookupTimeMs),
                    String.format("%.3f", p95LookupTimeMs));
        }
    }
    
    private double calculatePercentile(long[] times, double percentile) {
        java.util.Arrays.sort(times);
        int index = (int) Math.ceil(percentile / 100.0 * times.length) - 1;
        return times[index];
    }
    
    // Helper method to generate unique requests for performance testing
    private List<QueryRequest> generateUniqueRequests(int count) {
        List<QueryRequest> requests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            QueryRequest req = QueryRequest.builder()
                    .query("Generated query " + i)
                    .model(i % 2 == 0 ? ModelType.OPENAI : ModelType.GEMINI)
                    .taskType(i % 4 == 0 ? TaskType.TEXT_GENERATION : 
                             i % 4 == 1 ? TaskType.SUMMARIZATION : 
                             i % 4 == 2 ? TaskType.SENTIMENT_ANALYSIS :
                             TaskType.QUESTION_ANSWERING)
                    .requestId(UUID.randomUUID().toString())
                    .build();
            requests.add(req);
        }
        return requests;
    }
}