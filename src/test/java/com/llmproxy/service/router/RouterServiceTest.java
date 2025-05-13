package com.llmproxy.service.router;

import com.llmproxy.exception.ModelError;
import com.llmproxy.model.ModelType;
import com.llmproxy.model.QueryRequest;
import com.llmproxy.model.StatusResponse;
import com.llmproxy.model.TaskType;
import com.llmproxy.service.llm.LlmClient;
import com.llmproxy.service.llm.LlmClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouterServiceTest {

    @Mock
    private LlmClientFactory clientFactory;
    
    @Mock
    private LlmClient openAiClient;
    
    @Mock
    private LlmClient geminiClient;
    
    @Mock
    private LlmClient mistralClient;
    
    @Mock
    private LlmClient claudeClient;
    
    private RouterService routerService;

    @BeforeEach
    void setUp() {
        routerService = new RouterService(clientFactory);
        routerService.setTestMode(true); // Avoid actual availability checks
        
        lenient().when(openAiClient.getModelType()).thenReturn(ModelType.OPENAI);
        lenient().when(geminiClient.getModelType()).thenReturn(ModelType.GEMINI);
        lenient().when(mistralClient.getModelType()).thenReturn(ModelType.MISTRAL);
        lenient().when(claudeClient.getModelType()).thenReturn(ModelType.CLAUDE);
        
        lenient().when(clientFactory.getClient(ModelType.OPENAI)).thenReturn(openAiClient);
        lenient().when(clientFactory.getClient(ModelType.GEMINI)).thenReturn(geminiClient);
        lenient().when(clientFactory.getClient(ModelType.MISTRAL)).thenReturn(mistralClient);
        lenient().when(clientFactory.getClient(ModelType.CLAUDE)).thenReturn(claudeClient);
    }

    @Test
    void getAvailability_returnsCorrectStatus() {
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, false);
        routerService.setModelAvailability(ModelType.MISTRAL, true);
        routerService.setModelAvailability(ModelType.CLAUDE, false);
        
        StatusResponse status = routerService.getAvailability();
        
        assertTrue(status.isOpenai());
        assertFalse(status.isGemini());
        assertTrue(status.isMistral());
        assertFalse(status.isClaude());
    }

    @Test
    void routeRequest_withSpecifiedAvailableModel_usesSpecifiedModel() {
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, true);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .model(ModelType.GEMINI)
                .build();
        
        ModelType result = routerService.routeRequest(request);
        
        assertEquals(ModelType.GEMINI, result);
    }

    @Test
    void routeRequest_withSpecifiedUnavailableModel_usesAlternative() {
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, false);
        routerService.setModelAvailability(ModelType.MISTRAL, false);
        routerService.setModelAvailability(ModelType.CLAUDE, false);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .model(ModelType.GEMINI)
                .build();
        
        ModelType result = routerService.routeRequest(request);
        
        assertEquals(ModelType.OPENAI, result);
    }

    @Test
    void routeRequest_withTaskType_usesAppropriateModel() {
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, true);
        routerService.setModelAvailability(ModelType.MISTRAL, true);
        routerService.setModelAvailability(ModelType.CLAUDE, true);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .taskType(TaskType.SUMMARIZATION)
                .build();
        
        ModelType result = routerService.routeRequest(request);
        
        assertEquals(ModelType.CLAUDE, result);
    }

    @Test
    void routeRequest_withTaskTypeButUnavailableModel_usesAlternative() {
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, false);
        routerService.setModelAvailability(ModelType.MISTRAL, false);
        routerService.setModelAvailability(ModelType.CLAUDE, false);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .taskType(TaskType.SENTIMENT_ANALYSIS)
                .build();
        
        ModelType result = routerService.routeRequest(request);
        
        assertEquals(ModelType.OPENAI, result);
    }

    @Test
    void routeRequest_noModelOrTaskType_usesRandomAvailableModel() {
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, false);
        routerService.setModelAvailability(ModelType.MISTRAL, false);
        routerService.setModelAvailability(ModelType.CLAUDE, false);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();
        
        ModelType result = routerService.routeRequest(request);
        
        assertEquals(ModelType.OPENAI, result);
    }

    @Test
    void routeRequest_noAvailableModels_throwsException() {
        routerService.setModelAvailability(ModelType.OPENAI, false);
        routerService.setModelAvailability(ModelType.GEMINI, false);
        routerService.setModelAvailability(ModelType.MISTRAL, false);
        routerService.setModelAvailability(ModelType.CLAUDE, false);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();
        
        assertThrows(ModelError.class, () -> routerService.routeRequest(request));
    }

    @Test
    void fallbackOnError_withRetryableError_returnsAlternativeModel() {
        routerService.setModelAvailability(ModelType.OPENAI, false);
        routerService.setModelAvailability(ModelType.GEMINI, true);
        routerService.setModelAvailability(ModelType.MISTRAL, false);
        routerService.setModelAvailability(ModelType.CLAUDE, false);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();
        
        ModelError error = ModelError.rateLimitError(ModelType.OPENAI.toString());
        
        ModelType result = routerService.fallbackOnError(ModelType.OPENAI, request, error);
        
        assertEquals(ModelType.GEMINI, result);
    }

    @Test
    void fallbackOnError_withNonRetryableError_throwsException() {
        routerService.setModelAvailability(ModelType.OPENAI, false);
        routerService.setModelAvailability(ModelType.GEMINI, true);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();
        
        ModelError error = ModelError.apiKeyMissingError(ModelType.OPENAI.toString());
        
        assertThrows(ModelError.class, () -> routerService.fallbackOnError(ModelType.OPENAI, request, error));
    }

    @Test
    void fallbackOnError_noAvailableAlternatives_throwsException() {
        routerService.setModelAvailability(ModelType.OPENAI, false);
        routerService.setModelAvailability(ModelType.GEMINI, false);
        routerService.setModelAvailability(ModelType.MISTRAL, false);
        routerService.setModelAvailability(ModelType.CLAUDE, false);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();
        
        ModelError error = ModelError.rateLimitError(ModelType.OPENAI.toString());
        
        assertThrows(ModelError.class, () -> routerService.fallbackOnError(ModelType.OPENAI, request, error));
    }

    @Test
    void fallbackOnError_withUserSpecifiedModel_usesThatModel() {
        routerService.setModelAvailability(ModelType.OPENAI, false);
        routerService.setModelAvailability(ModelType.GEMINI, true);
        routerService.setModelAvailability(ModelType.MISTRAL, true);
        
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .model(ModelType.MISTRAL)
                .build();
        
        ModelError error = ModelError.rateLimitError(ModelType.OPENAI.toString());
        
        ModelType result = routerService.fallbackOnError(ModelType.OPENAI, request, error);
        
        assertEquals(ModelType.MISTRAL, result);
    }

    // ---- Enhanced Robustness tests for fallbackOnError start here ----

    @Test
    void fallbackOnError_withUserSpecifiedFallbackButUnavailable_selectsOtherAvailable() {
        routerService.setModelAvailability(ModelType.OPENAI, false);
        routerService.setModelAvailability(ModelType.GEMINI, false); // user fallback is unavailable
        routerService.setModelAvailability(ModelType.MISTRAL, true); // Mistral is available
        routerService.setModelAvailability(ModelType.CLAUDE, false);
        
        // User wanted GEMINI, which is unavailable
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .model(ModelType.GEMINI)
                .build();
        
        ModelError error = ModelError.rateLimitError(ModelType.OPENAI.toString());

        // Should select the next available model, which is MISTRAL
        ModelType result = routerService.fallbackOnError(ModelType.OPENAI, request, error);
        assertEquals(ModelType.MISTRAL, result);
    }

    @Test
    void fallbackOnError_withAllButOneModelAvailable_returnsThatModel() {
        // Only Claude is up
        routerService.setModelAvailability(ModelType.OPENAI, false);
        routerService.setModelAvailability(ModelType.GEMINI, false);
        routerService.setModelAvailability(ModelType.MISTRAL, false);
        routerService.setModelAvailability(ModelType.CLAUDE, true);

        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();
        ModelError error = ModelError.rateLimitError(ModelType.OPENAI.toString());
        ModelType result = routerService.fallbackOnError(ModelType.OPENAI, request, error);
        assertEquals(ModelType.CLAUDE, result);
    }

    @Test
    void fallbackOnError_initialModelIsOnlyAvailableOptionButFails_noAlternatives_throw() {
        // Only OpenAI is up, but it fails and fallback should not find any others
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, false);
        routerService.setModelAvailability(ModelType.MISTRAL, false);
        routerService.setModelAvailability(ModelType.CLAUDE, false);
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();
        ModelError error = ModelError.rateLimitError(ModelType.OPENAI.toString());
        // Mark OpenAI as unavailable now (simulate it failed and shouldn't be retried)
        routerService.setModelAvailability(ModelType.OPENAI, false);
        assertThrows(ModelError.class, () -> routerService.fallbackOnError(ModelType.OPENAI, request, error));
    }

    @Test
    void fallbackOnError_withTaskTypeAndPreferredModelUnavailable_selectsTaskTypeAlternative() {
        // Use sentiment_analysis mapping: prefer OpenAI, if not, next available
        routerService.setModelAvailability(ModelType.OPENAI, false);
        routerService.setModelAvailability(ModelType.GEMINI, false);
        routerService.setModelAvailability(ModelType.MISTRAL, true);
        routerService.setModelAvailability(ModelType.CLAUDE, false);

        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .taskType(TaskType.SENTIMENT_ANALYSIS)
                .build();
        ModelError error = ModelError.rateLimitError(ModelType.OPENAI.toString());
        ModelType result = routerService.fallbackOnError(ModelType.OPENAI, request, error);
        assertEquals(ModelType.MISTRAL, result);
    }

    @Test
    void fallbackOnError_specifiedModelEqualsFailedModel_returnsAlternative() {
        // User asked for MISTRAL, but MISTRAL failed, so must route elsewhere
        routerService.setModelAvailability(ModelType.OPENAI, false);
        routerService.setModelAvailability(ModelType.GEMINI, true);
        routerService.setModelAvailability(ModelType.MISTRAL, true); // was up, but failed
        routerService.setModelAvailability(ModelType.CLAUDE, true);

        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .model(ModelType.MISTRAL)
                .build();
        // Mark MISTRAL as down now ("fails")
        routerService.setModelAvailability(ModelType.MISTRAL, false);

        ModelError error = ModelError.rateLimitError(ModelType.MISTRAL.toString());
        // Should skip MISTRAL and pick next available: CLAUDE or GEMINI (impl can pick one of them; assert one of them)
        ModelType result = routerService.fallbackOnError(ModelType.MISTRAL, request, error);
        assertTrue(result == ModelType.GEMINI || result == ModelType.CLAUDE);
    }
    @Test
    @Tag("performance")
    void benchmark_routeRequest_singleThread() {
        // Setup - all models available
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, true);
        routerService.setModelAvailability(ModelType.MISTRAL, true);
        routerService.setModelAvailability(ModelType.CLAUDE, true);
        
        QueryRequest request = QueryRequest.builder()
                .query("Performance test query")
                .build();
        
        // Warm up
        for (int i = 0; i < 100; i++) {
            routerService.routeRequest(request);
        }
        
        // Benchmark
        int numRequests = 10000;
        long startTime = System.nanoTime();
        
        for (int i = 0; i < numRequests; i++) {
            routerService.routeRequest(request);
        }
        
        long endTime = System.nanoTime();
        double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
        double requestsPerSecond = numRequests / (elapsedTimeMs / 1000.0);
        
        System.out.println("Single thread performance:");
        System.out.println("Requests processed: " + numRequests);
        System.out.println("Total time (ms): " + elapsedTimeMs);
        System.out.println("Requests per second: " + requestsPerSecond);
        
        // Simple assertion to verify test ran without error
        assertTrue(requestsPerSecond > 0);
    }
    
    @Test
    @Tag("performance")
    void benchmark_routeRequest_multiThread() throws InterruptedException {
        // Setup - all models available
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, true);
        routerService.setModelAvailability(ModelType.MISTRAL, true);
        routerService.setModelAvailability(ModelType.CLAUDE, true);
        
        int numThreads = 8;
        int requestsPerThread = 1000;
        int totalRequests = numThreads * requestsPerThread;
        
        QueryRequest request = QueryRequest.builder()
                .query("Performance test query")
                .build();
        
        // Warm up
        for (int i = 0; i < 100; i++) {
            routerService.routeRequest(request);
        }
        
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger errorCounter = new AtomicInteger(0);
        
        for (int t = 0; t < numThreads; t++) {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    for (int i = 0; i < requestsPerThread; i++) {
                        try {
                            routerService.routeRequest(request);
                            successCounter.incrementAndGet();
                        } catch (Exception e) {
                            errorCounter.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        long startTime = System.nanoTime();
        startLatch.countDown(); // Start all threads
        
        // Wait for all threads to finish
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        executorService.shutdown();
        
        double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
        double requestsPerSecond = totalRequests / (elapsedTimeMs / 1000.0);
        
        System.out.println("Multi-thread performance (" + numThreads + " threads):");
        System.out.println("Requests processed: " + totalRequests);
        System.out.println("Successful requests: " + successCounter.get());
        System.out.println("Failed requests: " + errorCounter.get());
        System.out.println("Total time (ms): " + elapsedTimeMs);
        System.out.println("Requests per second: " + requestsPerSecond);
        
        assertTrue(completed, "Benchmark timed out");
        assertEquals(totalRequests, successCounter.get(), "Some requests failed");
        assertTrue(requestsPerSecond > 0);
    }
    
    @Test
    @Tag("performance")
    void benchmark_differentAvailabilityScenarios() {
        List<AvailabilityScenario> scenarios = new ArrayList<>();
        
        // All models available
        scenarios.add(new AvailabilityScenario(
            "All models available",
            true, true, true, true
        ));
        
        // Only one model available
        scenarios.add(new AvailabilityScenario(
            "Only OpenAI available",
            true, false, false, false
        ));
        
        scenarios.add(new AvailabilityScenario(
            "Only Gemini available",
            false, true, false, false
        ));
        
        // Two models available
        scenarios.add(new AvailabilityScenario(
            "OpenAI and Claude available",
            true, false, false, true
        ));
        
        // Benchmark each scenario
        int requestsPerScenario = 5000;
        QueryRequest request = QueryRequest.builder()
                .query("Performance test query")
                .build();
                
        for (AvailabilityScenario scenario : scenarios) {
            routerService.setModelAvailability(ModelType.OPENAI, scenario.openaiAvailable);
            routerService.setModelAvailability(ModelType.GEMINI, scenario.geminiAvailable);
            routerService.setModelAvailability(ModelType.MISTRAL, scenario.mistralAvailable);
            routerService.setModelAvailability(ModelType.CLAUDE, scenario.claudeAvailable);
            
            // Warm up
            for (int i = 0; i < 100; i++) {
                try {
                    routerService.routeRequest(request);
                } catch (Exception e) {
                    // Ignore - may happen if no models available
                }
            }
            
            long startTime = System.nanoTime();
            int successCount = 0;
            
            for (int i = 0; i < requestsPerScenario; i++) {
                try {
                    routerService.routeRequest(request);
                    successCount++;
                } catch (Exception e) {
                    // Expected if no models available
                }
            }
            
            long endTime = System.nanoTime();
            double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
            double requestsPerSecond = successCount / (elapsedTimeMs / 1000.0);
            
            System.out.println("Scenario: " + scenario.name);
            System.out.println("  Successful requests: " + successCount + "/" + requestsPerScenario);
            System.out.println("  Total time (ms): " + elapsedTimeMs);
            System.out.println("  Requests per second: " + requestsPerSecond);
        }
        
        // No assertions needed - this is a benchmark
        assertTrue(true);
    }
    
    @Test
    @Tag("performance")
    void benchmark_fallbackOnError() {
        // Setup - multiple models available for fallback
        routerService.setModelAvailability(ModelType.OPENAI, true);
        routerService.setModelAvailability(ModelType.GEMINI, true);
        routerService.setModelAvailability(ModelType.MISTRAL, true);
        routerService.setModelAvailability(ModelType.CLAUDE, true);
        
        QueryRequest request = QueryRequest.builder()
                .query("Performance test query")
                .build();
        
        ModelError retryableError = ModelError.rateLimitError(ModelType.OPENAI.toString());
        
        // Warm up
        for (int i = 0; i < 100; i++) {
            routerService.fallbackOnError(ModelType.OPENAI, request, retryableError);
        }
        
        // Benchmark
        int numRequests = 5000;
        long startTime = System.nanoTime();
        
        for (int i = 0; i < numRequests; i++) {
            routerService.fallbackOnError(ModelType.OPENAI, request, retryableError);
        }
        
        long endTime = System.nanoTime();
        double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
        double fallbacksPerSecond = numRequests / (elapsedTimeMs / 1000.0);
        
        System.out.println("Fallback performance:");
        System.out.println("Fallbacks processed: " + numRequests);
        System.out.println("Total time (ms): " + elapsedTimeMs);
        System.out.println("Fallbacks per second: " + fallbacksPerSecond);
        
        assertTrue(fallbacksPerSecond > 0);
    }
    
    // Helper class for availability scenarios
    private static class AvailabilityScenario {
        final String name;
        final boolean openaiAvailable;
        final boolean geminiAvailable;
        final boolean mistralAvailable;
        final boolean claudeAvailable;
        
        AvailabilityScenario(String name, boolean openai, boolean gemini, 
                            boolean mistral, boolean claude) {
            this.name = name;
            this.openaiAvailable = openai;
            this.geminiAvailable = gemini;
            this.mistralAvailable = mistral;
            this.claudeAvailable = claude;
        }
    }
}
