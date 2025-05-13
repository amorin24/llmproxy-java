package com.llmproxy.controller;

import com.llmproxy.exception.ModelError;
import com.llmproxy.model.ModelType;
import com.llmproxy.model.QueryRequest;
import com.llmproxy.model.QueryResponse;
import com.llmproxy.model.StatusResponse;
import com.llmproxy.service.cache.CacheService;
import com.llmproxy.service.llm.LlmClient;
import com.llmproxy.service.llm.LlmClientFactory;
import com.llmproxy.service.llm.QueryResult;
import com.llmproxy.service.ratelimit.RateLimiterService;
import com.llmproxy.service.router.RouterService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmProxyControllerTest {

    @Mock
    private RouterService routerService;

    @Mock
    private LlmClientFactory clientFactory;

    @Mock
    private CacheService cacheService;

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private LlmClient llmClient;

    private LlmProxyController controller;
    private MockHttpServletRequest mockRequest;

    @BeforeEach
    void setUp() {
        controller = new LlmProxyController(routerService, clientFactory, cacheService, rateLimiterService);
        mockRequest = new MockHttpServletRequest();
        mockRequest.setRemoteAddr("127.0.0.1");
        lenient().when(rateLimiterService.allowClient(anyString())).thenReturn(true);
        lenient().when(clientFactory.getClient(any(ModelType.class))).thenReturn(llmClient);
    }

    @Test
    void query_validRequest_returnsResponse() {
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();

        QueryResult queryResult = QueryResult.builder()
                .response("Test response")
                .statusCode(HttpStatus.OK.value())
                .inputTokens(10)
                .outputTokens(20)
                .totalTokens(30)
                .numTokens(30)
                .responseTimeMs(100)
                .build();

        lenient().when(cacheService.get(any(QueryRequest.class))).thenReturn(null);
        lenient().when(routerService.routeRequest(any(QueryRequest.class))).thenReturn(ModelType.OPENAI);
        lenient().when(llmClient.query(any(), any())).thenReturn(queryResult);

        ResponseEntity<QueryResponse> response = controller.query(request, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Test response", response.getBody().getResponse());
        assertEquals(ModelType.OPENAI, response.getBody().getModel());
        assertEquals(10, response.getBody().getInputTokens());
        assertEquals(20, response.getBody().getOutputTokens());
        assertEquals(30, response.getBody().getTotalTokens());
    }

    @Test
    void query_emptyQuery_returnsBadRequest() {
        QueryRequest request = QueryRequest.builder()
                .query("")
                .build();

        ResponseEntity<QueryResponse> response = controller.query(request, mockRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Query cannot be empty", response.getBody().getError());
        assertEquals("validation_error", response.getBody().getErrorType());
    }

    @Test
    void query_rateLimited_returnsTooManyRequests() {
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();

        lenient().when(rateLimiterService.allowClient(anyString())).thenReturn(false);

        ResponseEntity<QueryResponse> response = controller.query(request, mockRequest);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Rate limit exceeded. Please try again later.", response.getBody().getError());
        assertEquals("rate_limit", response.getBody().getErrorType());
    }

    @Test
    void query_cachedResponse_returnsCachedResponse() {
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();

        QueryResponse cachedResponse = QueryResponse.builder()
                .response("Cached response")
                .model(ModelType.OPENAI)
                .cached(true)
                .timestamp(Instant.now())
                .build();

        lenient().when(cacheService.get(any(QueryRequest.class))).thenReturn(cachedResponse);

        ResponseEntity<QueryResponse> response = controller.query(request, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Cached response", response.getBody().getResponse());
        assertEquals(ModelType.OPENAI, response.getBody().getModel());
        assertTrue(response.getBody().isCached());
    }

    @Test
    void query_modelError_returnsErrorResponse() {
        QueryRequest request = QueryRequest.builder()
                .query("Test query")
                .build();

        ModelError apiKeyError = ModelError.apiKeyMissingError(ModelType.OPENAI.toString());

        lenient().when(cacheService.get(any(QueryRequest.class))).thenReturn(null);
        lenient().when(routerService.routeRequest(any(QueryRequest.class))).thenReturn(ModelType.OPENAI);
        lenient().when(llmClient.query(any(), any())).thenThrow(apiKeyError);

        ResponseEntity<QueryResponse> response = controller.query(request, mockRequest);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("API key not configured", response.getBody().getError());
        assertEquals("ModelError", response.getBody().getErrorType());
    }

    @Test
    void status_returnsAvailability() {
        StatusResponse statusResponse = StatusResponse.builder()
                .openai(true)
                .gemini(false)
                .mistral(true)
                .claude(false)
                .build();

        lenient().when(routerService.getAvailability()).thenReturn(statusResponse);

        ResponseEntity<StatusResponse> response = controller.status(mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isOpenai());
        assertFalse(response.getBody().isGemini());
        assertTrue(response.getBody().isMistral());
        assertFalse(response.getBody().isClaude());
    }

    @Test
    void status_onlyGeminiAvailable_returnsCorrectAvailability() {
        StatusResponse statusResponse = StatusResponse.builder()
                .openai(false)
                .gemini(true)
                .mistral(false)
                .claude(false)
                .build();

        when(routerService.getAvailability()).thenReturn(statusResponse);

        ResponseEntity<StatusResponse> response = controller.status(mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isOpenai());
        assertTrue(response.getBody().isGemini());
        assertFalse(response.getBody().isMistral());
        assertFalse(response.getBody().isClaude());
    }

    @Test
    void status_mistralAndClaudeAvailable_returnsCorrectAvailability() {
        StatusResponse statusResponse = StatusResponse.builder()
                .openai(false)
                .gemini(false)
                .mistral(true)
                .claude(true)
                .build();

        when(routerService.getAvailability()).thenReturn(statusResponse);

        ResponseEntity<StatusResponse> response = controller.status(mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isOpenai());
        assertFalse(response.getBody().isGemini());
        assertTrue(response.getBody().isMistral());
        assertTrue(response.getBody().isClaude());
    }

    @Test
    void status_allModelsAvailable_returnsCorrectAvailability() {
        StatusResponse statusResponse = StatusResponse.builder()
                .openai(true)
                .gemini(true)
                .mistral(true)
                .claude(true)
                .build();

        when(routerService.getAvailability()).thenReturn(statusResponse);

        ResponseEntity<StatusResponse> response = controller.status(mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isOpenai());
        assertTrue(response.getBody().isGemini());
        assertTrue(response.getBody().isMistral());
        assertTrue(response.getBody().isClaude());
    }

    @Test
    void status_noModelsAvailable_returnsCorrectAvailability() {
        StatusResponse statusResponse = StatusResponse.builder()
                .openai(false)
                .gemini(false)
                .mistral(false)
                .claude(false)
                .build();

        when(routerService.getAvailability()).thenReturn(statusResponse);

        ResponseEntity<StatusResponse> response = controller.status(mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isOpenai());
        assertFalse(response.getBody().isGemini());
        assertFalse(response.getBody().isMistral());
        assertFalse(response.getBody().isClaude());
    }

    @Test
    void health_returnsOk() {
        ResponseEntity<Map<String, Object>> response = controller.health(mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ok", response.getBody().get("status"));
    }

    @Test
    void download_validRequest_returnsFile() {
        Map<String, String> request = Map.of(
            "response", "Test response",
            "format", "txt"
        );

        ResponseEntity<byte[]> response = controller.download(request, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.TEXT_PLAIN_VALUE, response.getHeaders().getContentType().toString());
        assertEquals("attachment; filename=llm_response.txt", response.getHeaders().getFirst("Content-Disposition"));
        assertEquals("Test response", new String(response.getBody()));
    }
}