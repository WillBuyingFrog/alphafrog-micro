package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.openai.internal.OpenAiUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRouterProviderRoutedChatModelTest {

    @Test
    void normalizeOpenRouterTokenLimit_shouldUseMaxTokensForProviderRouting() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "moonshotai/kimi-k2.5");
        payload.put("max_completion_tokens", 512);

        OpenRouterProviderRoutedChatModel.normalizeOpenRouterTokenLimit(payload);

        assertFalse(payload.containsKey("max_completion_tokens"));
        assertEquals(512, payload.get("max_tokens"));
    }

    @Test
    void normalizeOpenRouterTokenLimit_shouldKeepExistingMaxTokens() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("max_tokens", 256);
        payload.put("max_completion_tokens", 512);

        OpenRouterProviderRoutedChatModel.normalizeOpenRouterTokenLimit(payload);

        assertFalse(payload.containsKey("max_completion_tokens"));
        assertEquals(256, payload.get("max_tokens"));
    }

    @Test
    void applyStreamingOptions_shouldUseFireworksPerfMetricsWithoutStreamOptions() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));

        OpenRouterProviderRoutedChatModel.applyStreamingOptions(
                payload,
                "https://api.fireworks.ai/inference/v1",
                null
        );

        assertFalse(payload.containsKey("stream_options"));
        assertEquals(true, payload.get("perf_metrics_in_response"));
    }

    @Test
    void applyStreamingOptions_shouldUseOpenAiCompatibleStreamUsageForNonFireworks() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stream", true);
        payload.put("perf_metrics_in_response", true);

        OpenRouterProviderRoutedChatModel.applyStreamingOptions(
                payload,
                "https://openrouter.ai/api/v1",
                "execution"
        );

        assertFalse(payload.containsKey("perf_metrics_in_response"));
        assertEquals(Map.of("include_usage", true), payload.get("stream_options"));
    }

    @Test
    void applyStreamingOptions_shouldSkipStreamOptionsForPlanningPhaseOnOpenRouter() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));

        OpenRouterProviderRoutedChatModel.applyStreamingOptions(
                payload,
                "https://openrouter.ai/api/v1",
                AgentObservabilityService.PHASE_PLANNING
        );

        assertFalse(payload.containsKey("stream_options"));
        assertFalse(payload.containsKey("perf_metrics_in_response"));
        assertEquals(true, payload.get("stream"));
    }

    @Test
    void applyFireworksReasoningEffort_shouldSetTopLevelReasoningEffort() {
        Map<String, Object> payload = new LinkedHashMap<>();

        OpenRouterProviderRoutedChatModel.applyFireworksReasoningEffort(payload, "high");

        assertEquals("high", payload.get("reasoning_effort"));
    }

    @Test
    void applyFireworksReasoningEffort_shouldIgnoreBlankValue() {
        Map<String, Object> payload = new LinkedHashMap<>();

        OpenRouterProviderRoutedChatModel.applyFireworksReasoningEffort(payload, " ");

        assertTrue(payload.isEmpty());
    }

    @Test
    void applyEndpointSamplingDefaults_shouldOmitTemperatureForFireworks() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("temperature", 0.7D);

        OpenRouterProviderRoutedChatModel.applyEndpointSamplingDefaults(
                payload,
                "https://api.fireworks.ai/inference/v1"
        );

        assertFalse(payload.containsKey("temperature"));
    }

    @Test
    void applyEndpointSamplingDefaults_shouldKeepTemperatureForNonFireworks() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("temperature", 0.7D);

        OpenRouterProviderRoutedChatModel.applyEndpointSamplingDefaults(
                payload,
                "https://openrouter.ai/api/v1"
        );

        assertEquals(0.7D, payload.get("temperature"));
    }

    @Test
    void resolveRequestTimeout_shouldUseStageAndPhaseBuckets() {
        assertEquals(Duration.ofSeconds(90),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("planning", "execution"));
        assertEquals(Duration.ofSeconds(90),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("final_answer", "summarizing"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("python_refine_plan", "execution"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("sub_agent_step_execute", "execution"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("semantic_judge", "execution"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("tool_use_decision", "execution"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("search_evidence_judge", "execution"));
        assertEquals(Duration.ofSeconds(60),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("summarizing", "execution"));
    }

    @Test
    void aggregateSseStream_shouldPreserveToolCallDeltas() {
        String sse = """
                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"searchWeb:0","type":"function","function":{"name":"searchWeb","arguments":"{\\"query\\":"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"function":{"arguments":"\\"今天A股\\",\\"maxResults\\":"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"function":{"arguments":"5}"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":"tool_calls","native_finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}

                data: [DONE]
                """;

        OpenAiCompatibleChatModelSupport.SseAggregateResult result =
                OpenAiCompatibleChatModelSupport.aggregateSseStream(
                        new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                        new ObjectMapper(),
                        org.slf4j.LoggerFactory.getLogger(OpenRouterProviderRoutedChatModelTest.class),
                        null
                );

        AiMessage message = OpenAiUtils.aiMessageFrom(result.completionResponse());

        assertNotNull(message.toolExecutionRequests());
        assertEquals(1, message.toolExecutionRequests().size());
        assertEquals("searchWeb", message.toolExecutionRequests().get(0).name());
        assertEquals("{\"query\":\"今天A股\",\"maxResults\":5}", message.toolExecutionRequests().get(0).arguments());
    }

    @Test
    void streamingProgressTracker_shouldCountToolCallArgumentCharsAndReportFinalSnapshot() {
        String sse = """
                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"searchWeb:0","type":"function","function":{"name":"searchWeb","arguments":"{\\"query\\":"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"function":{"arguments":"\\"今天A股\\"}"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;
        AtomicReference<StreamingProgressTracker.StreamingProgressSnapshot> reported = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        StreamingProgressTracker tracker = new StreamingProgressTracker(
                org.slf4j.LoggerFactory.getLogger(OpenRouterProviderRoutedChatModelTest.class),
                "moonshotai/kimi-k2.5",
                "openrouter",
                false,
                true,
                1000,
                (snapshot, done) -> {
                    reported.set(snapshot);
                    completed.set(done);
                }
        );

        OpenAiCompatibleChatModelSupport.aggregateSseStream(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                new ObjectMapper(),
                org.slf4j.LoggerFactory.getLogger(OpenRouterProviderRoutedChatModelTest.class),
                tracker
        );
        StreamingProgressTracker.StreamingProgressSnapshot finalSnapshot = tracker.onStreamComplete(1000);

        assertEquals("{\"query\":\"今天A股\"}".length(), finalSnapshot.toolCallCharCount());
        assertEquals(finalSnapshot.toolCallCharCount(), finalSnapshot.totalCharCount());
        assertEquals(finalSnapshot, reported.get());
        assertEquals(true, completed.get());
    }

}
