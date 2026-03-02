package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentObservabilityServiceCacheTest {

    private AgentObservabilityService service;
    private ObjectMapper objectMapper;

    @Mock
    private AgentRunStateStore stateStore;

    @Mock
    private AgentObservabilityDebugFileWriter debugFileWriter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AgentObservabilityService(stateStore, objectMapper, debugFileWriter);
        ReflectionTestUtils.setField(service, "llmTraceEnabled", true);
        ReflectionTestUtils.setField(service, "llmTraceMaxCalls", 100);
        ReflectionTestUtils.setField(service, "llmTraceMaxTextChars", 20000);
        ReflectionTestUtils.setField(service, "captureCachedTokens", true);
        ReflectionTestUtils.setField(service, "llmTraceReasoningMaxChars", 20000);
    }

    @Test
    void recordLlmCallWithRawHttp_shouldTrackCachedTokens() {
        String runId = "test-run-1";
        when(stateStore.loadObservability(eq(runId))).thenReturn(Optional.empty());

        TokenUsage tokenUsage = new TokenUsage(1000, 200, 1200);
        Integer cachedTokens = 800;

        String traceId = service.recordLlmCallWithRawHttp(
                runId, "planning", tokenUsage, cachedTokens,
                500L, 100L, 600L,
                "openrouter", "openai/gpt-5.2", null,
                null, null, null
        );

        assertNotNull(traceId);
        assertFalse(traceId.isBlank());
    }

    @Test
    void recordLlmCallWithRawHttp_shouldHandleNullCachedTokens() {
        String runId = "test-run-2";
        when(stateStore.loadObservability(eq(runId))).thenReturn(Optional.empty());

        TokenUsage tokenUsage = new TokenUsage(500, 100, 600);

        String traceId = service.recordLlmCallWithRawHttp(
                runId, "planning", tokenUsage, null,
                300L, 100L, 400L,
                "moonshot", "kimi-k2.5", null,
                null, null, null
        );

        assertNotNull(traceId);
    }

    @Test
    void recordLlmCallWithRawHttp_cachedTokensShouldAccumulateInSummary() {
        String runId = "test-run-3";
        AtomicReference<String> savedJson = new AtomicReference<>();

        when(stateStore.loadObservability(eq(runId))).thenAnswer(inv -> {
            String json = savedJson.get();
            return json == null ? Optional.empty() : Optional.of(json);
        });
        doAnswer(inv -> {
            savedJson.set(inv.getArgument(1));
            return null;
        }).when(stateStore).saveObservability(eq(runId), anyString());

        // First call with 800 cached tokens
        service.recordLlmCallWithRawHttp(
                runId, "planning",
                new TokenUsage(1000, 200, 1200), 800,
                500L, 0L, 0L,
                "openrouter", "openai/gpt-5.2", null,
                null, null, null
        );

        // Second call with 500 cached tokens
        service.recordLlmCallWithRawHttp(
                runId, "planning",
                new TokenUsage(600, 100, 700), 500,
                300L, 0L, 0L,
                "openrouter", "openai/gpt-5.2", null,
                null, null, null
        );

        // Load the stored state and verify accumulated cachedTokens
        String stored = savedJson.get();
        assertNotNull(stored);

        try {
            AgentObservabilityService.ObservabilityState state =
                    objectMapper.readValue(stored, AgentObservabilityService.ObservabilityState.class);
            assertEquals(1300, state.getSummary().getCachedTokens());
            assertEquals(1300, state.getPhases().get("planning").getCachedTokens());
        } catch (Exception e) {
            fail("Failed to parse stored state: " + e.getMessage());
        }
    }

    @Test
    void enrichLlmCallSpending_shouldUpdateMatchingTrace() {
        String runId = "test-run-4";
        AtomicReference<String> savedJson = new AtomicReference<>();

        when(stateStore.loadObservability(eq(runId))).thenAnswer(inv -> {
            String json = savedJson.get();
            return json == null ? Optional.empty() : Optional.of(json);
        });
        doAnswer(inv -> {
            savedJson.set(inv.getArgument(1));
            return null;
        }).when(stateStore).saveObservability(eq(runId), anyString());

        // Record an LLM call first
        String traceId = service.recordLlmCallWithRawHttp(
                runId, "planning",
                new TokenUsage(1000, 200, 1200), 800,
                500L, 0L, 0L,
                "openrouter", "openai/gpt-5.2", null,
                null, null, null
        );

        // Enrich with spending info
        service.enrichLlmCallSpending(runId, traceId, 0.0015, 0.0012, 0.0002, false);

        // Verify the spending was applied
        String stored = savedJson.get();
        assertNotNull(stored);

        try {
            AgentObservabilityService.ObservabilityState state =
                    objectMapper.readValue(stored, AgentObservabilityService.ObservabilityState.class);
            AgentObservabilityService.LlmTrace trace = state.getDiagnostics().getLlmTraces().stream()
                    .filter(t -> traceId.equals(t.getTraceId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(0.0015, trace.getActualCost());
            assertEquals(0.0012, trace.getUpstreamCost());
            assertEquals(0.0002, trace.getCacheDiscount());
            assertFalse(trace.getIsByok());
        } catch (Exception e) {
            fail("Failed to parse stored state: " + e.getMessage());
        }
    }

    @Test
    void enrichLlmCallSpending_shouldHandleNullRunIdOrTraceId() {
        // Should not throw
        service.enrichLlmCallSpending(null, "trace-1", 0.001, 0.0008, 0.0001, false);
        service.enrichLlmCallSpending("run-1", null, 0.001, 0.0008, 0.0001, false);
        service.enrichLlmCallSpending("", "trace-1", 0.001, 0.0008, 0.0001, false);
        service.enrichLlmCallSpending("run-1", "", 0.001, 0.0008, 0.0001, false);
    }

    @Test
    void backwardCompatibility_existingRecordLlmCallWithRawHttp_shouldStillWork() {
        String runId = "test-run-compat";
        when(stateStore.loadObservability(eq(runId))).thenReturn(Optional.empty());

        // Old signature without cachedTokens should still work
        String traceId = service.recordLlmCallWithRawHttp(
                runId, "planning",
                new TokenUsage(1000, 200, 1200),
                500L, 100L, 600L,
                "openrouter", "openai/gpt-5.2", null,
                null, null, null
        );

        assertNotNull(traceId);
    }
}
