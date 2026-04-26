package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.openai.internal.OpenAiUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
