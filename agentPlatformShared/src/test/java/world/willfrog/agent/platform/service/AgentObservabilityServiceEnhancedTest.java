package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 可观测性增强测试：验证 5.2~5.5 的核心改动。
 */
@ExtendWith(MockitoExtension.class)
class AgentObservabilityServiceEnhancedTest {

    private AgentObservabilityService service;
    private ObjectMapper objectMapper;

    @Mock
    private AgentRunStateStore stateStore;

    @Mock
    private AgentObservabilityDebugFileWriter debugFileWriter;

    private AtomicReference<String> savedJson;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AgentObservabilityService(stateStore, objectMapper, debugFileWriter);
        ReflectionTestUtils.setField(service, "llmTraceEnabled", true);
        ReflectionTestUtils.setField(service, "llmTraceMaxCalls", 100);
        ReflectionTestUtils.setField(service, "llmTraceMaxTextChars", 20000);
        ReflectionTestUtils.setField(service, "captureCachedTokens", true);
        ReflectionTestUtils.setField(service, "llmTraceReasoningMaxChars", 20000);
        ReflectionTestUtils.setField(service, "toolTraceMaxOutputChars", 100000);

        savedJson = new AtomicReference<>();
        AgentContext.clear();
    }

    private void setupStateStore(String runId) {
        when(stateStore.loadObservability(eq(runId))).thenAnswer(inv -> {
            String json = savedJson.get();
            return json == null ? Optional.empty() : Optional.of(json);
        });
        doAnswer(inv -> {
            savedJson.set(inv.getArgument(1));
            return null;
        }).when(stateStore).saveObservability(eq(runId), anyString());
    }

    // ==================== 5.2 LLM inputMessages / outputText ====================

    @Test
    void recordLlmCall_shouldPopulateInputMessagesAndOutputText() throws Exception {
        String runId = "test-input-msg-1";
        setupStateStore(runId);

        Map<String, Object> requestSnapshot = Map.of(
                "messages", List.of(
                        Map.of("role", "system", "content", "你是一个助手"),
                        Map.of("role", "user", "content", "查询沪深300")
                )
        );
        String responseText = "计划分为3个并行任务";

        service.recordLlmCall(runId, "planning", new TokenUsage(500, 100, 600),
                300L, 100L, 400L, null, null, null,
                requestSnapshot, responseText);

        AgentObservabilityService.ObservabilityState state =
                objectMapper.readValue(savedJson.get(), AgentObservabilityService.ObservabilityState.class);
        AgentObservabilityService.LlmTrace trace = state.getDiagnostics().getLlmTraces().get(0);

        assertNotNull(trace.getInputMessages(), "inputMessages should be populated");
        assertEquals(responseText, trace.getOutputText(), "outputText should match response text");
        // backward compat
        assertNotNull(trace.getRequest(), "deprecated request field should still be set");
        assertEquals(responseText, trace.getResponsePreview(), "deprecated responsePreview should still be set");
    }

    @Test
    void recordLlmCallWithRawHttp_shouldReturnProviderTraceIdWithoutMutatingState() {
        String runId = "test-provider-dedup-raw";
        AgentContext.setProviderLlmTraceId("provider-trace-1");

        String traceId = service.recordLlmCallWithRawHttp(
                runId,
                "execution",
                new TokenUsage(1, 2, 3),
                null,
                100L,
                10L,
                110L,
                "openrouter",
                "model-a",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of()
        );

        assertEquals("provider-trace-1", traceId);
        assertNull(savedJson.get(), "provider 已记录 trace 时不应重复写 observability");
    }

    @Test
    void loadObservabilityJson_shouldPreferRedisCache() {
        String runId = "test-load-from-redis";
        when(stateStore.loadObservability(eq(runId))).thenReturn(Optional.of("{\"summary\":{\"status\":\"EXECUTING\"}}"));

        String json = service.loadObservabilityJson(runId, "{\"observability\":{\"summary\":{\"status\":\"FAILED\"}}}");

        assertEquals("{\"summary\":{\"status\":\"EXECUTING\"}}", json);
    }

    @Test
    void loadObservabilityJson_shouldFallbackToSnapshotWhenRedisMissing() {
        String runId = "test-load-from-snapshot";
        when(stateStore.loadObservability(eq(runId))).thenReturn(Optional.empty());

        String json = service.loadObservabilityJson(runId, "{\"observability\":{\"summary\":{\"status\":\"WAITING\"}}}");

        assertTrue(json.contains("\"WAITING\""));
    }

    @Test
    void loadObservabilityJson_shouldReturnEmptyWhenBothSourcesMissing() {
        String runId = "test-load-empty";
        when(stateStore.loadObservability(eq(runId))).thenReturn(Optional.empty());

        String json = service.loadObservabilityJson(runId, "{\"answer\":\"no_observability\"}");

        assertEquals("", json);
    }

    @Test
    void recordLlmCallWithRawHttp_shouldParseInputMessagesFromHttpBody() throws Exception {
        String runId = "test-raw-http-input";
        setupStateStore(runId);

        String requestBody = "{\"model\":\"test-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        String responseBody = "{\"choices\":[{\"message\":{\"content\":\"world\"}}]}";

        RawHttpLogger.HttpRequestRecord httpRequest = RawHttpLogger.HttpRequestRecord.builder()
                .url("https://api.example.com/chat")
                .method("POST")
                .body(requestBody)
                .headers(Map.of("Content-Type", "application/json"))
                .build();

        RawHttpLogger.HttpResponseRecord httpResponse = RawHttpLogger.HttpResponseRecord.builder()
                .statusCode(200)
                .body(responseBody)
                .build();

        service.recordLlmCallWithRawHttp(runId, "planning",
                new TokenUsage(100, 50, 150), null,
                200L, 100L, 300L,
                "test-endpoint", "test-model", null,
                httpRequest, httpResponse, "curl ...");

        AgentObservabilityService.ObservabilityState state =
                objectMapper.readValue(savedJson.get(), AgentObservabilityService.ObservabilityState.class);
        AgentObservabilityService.LlmTrace trace = state.getDiagnostics().getLlmTraces().get(0);

        assertNotNull(trace.getInputMessages(), "inputMessages should be parsed from HTTP body");
        assertTrue(trace.getInputMessages().containsKey("messages"), "inputMessages should contain messages key");
        assertNotNull(trace.getOutputText(), "outputText should be set from HTTP response");
    }

    // ==================== 5.3 DAG 节点 ID 写入 LlmTrace ====================

    @Test
    void recordLlmCall_shouldCaptureTodoIdFromAgentContext() throws Exception {
        String runId = "test-todo-id-1";
        setupStateStore(runId);

        AgentContext.setTodoContext("todo_search_index", 1);

        service.recordLlmCall(runId, "tool_execution", new TokenUsage(200, 80, 280),
                150L, 0L, 0L, null, null, null,
                null, "执行搜索操作");

        AgentObservabilityService.ObservabilityState state =
                objectMapper.readValue(savedJson.get(), AgentObservabilityService.ObservabilityState.class);
        AgentObservabilityService.LlmTrace trace = state.getDiagnostics().getLlmTraces().get(0);

        assertEquals("todo_search_index", trace.getTodoId(), "todoId should come from AgentContext");
        assertEquals(1, trace.getTodoSequence(), "todoSequence should come from AgentContext");

        AgentContext.clearTodoContext();
    }

    @Test
    void recordLlmCallWithRawHttp_shouldCaptureTodoIdFromAgentContext() throws Exception {
        String runId = "test-raw-todo-id";
        setupStateStore(runId);

        AgentContext.setTodoContext("node_parallel_1", 3);

        service.recordLlmCallWithRawHttp(runId, "tool_execution",
                new TokenUsage(100, 50, 150), null,
                200L, 0L, 0L,
                "test-endpoint", "test-model", null,
                null, null, null);

        AgentObservabilityService.ObservabilityState state =
                objectMapper.readValue(savedJson.get(), AgentObservabilityService.ObservabilityState.class);
        AgentObservabilityService.LlmTrace trace = state.getDiagnostics().getLlmTraces().get(0);

        assertEquals("node_parallel_1", trace.getTodoId());
        assertEquals(3, trace.getTodoSequence());

        AgentContext.clearTodoContext();
    }

    // ==================== 5.4 工具输出使用独立配置限制 ====================

    @Test
    void recordToolCall_shouldUseToolTraceOutputLimit() throws Exception {
        String runId = "test-tool-output-1";
        setupStateStore(runId);

        // Set tool trace limit to 50 chars (much smaller than llm trace limit)
        ReflectionTestUtils.setField(service, "toolTraceMaxOutputChars", 50);

        String longOutput = "A".repeat(100);

        service.recordToolCall(runId, "tool_execution", "searchIndex",
                Map.of("keyword", "test"), longOutput,
                100L, true, false, false, null, null, 0, 0, null);

        AgentObservabilityService.ObservabilityState state =
                objectMapper.readValue(savedJson.get(), AgentObservabilityService.ObservabilityState.class);
        AgentObservabilityService.ToolTrace trace = state.getDiagnostics().getToolTraces().get(0);

        // Should be truncated at 50 chars + "[truncated]" suffix
        assertNotNull(trace.getOutput(), "output field should be set");
        assertTrue(trace.getOutput().length() < longOutput.length(),
                "output should be truncated by tool-trace-specific limit");
        assertTrue(trace.getOutput().contains("[truncated]"), "should contain truncation marker");
    }

    @Test
    void recordToolCall_shouldNotTruncateShortOutput() throws Exception {
        String runId = "test-tool-output-2";
        setupStateStore(runId);

        String shortOutput = "{\"ok\":true,\"data\":[]}";

        service.recordToolCall(runId, "tool_execution", "searchIndex",
                Map.of("keyword", "test"), shortOutput,
                100L, true, false, false, null, null, 0, 0, null);

        AgentObservabilityService.ObservabilityState state =
                objectMapper.readValue(savedJson.get(), AgentObservabilityService.ObservabilityState.class);
        AgentObservabilityService.ToolTrace trace = state.getDiagnostics().getToolTraces().get(0);

        assertEquals(shortOutput, trace.getOutput(), "short output should not be truncated");
    }

    // ==================== ToolTrace backward compat ====================

    @Test
    void toolTrace_setOutputPreview_shouldSetOutput() {
        AgentObservabilityService.ToolTrace trace = new AgentObservabilityService.ToolTrace();
        trace.setOutputPreview("test value");
        assertEquals("test value", trace.getOutput());
        assertEquals("test value", trace.getOutputPreview());
    }

    // ==================== LlmTrace without TodoContext ====================

    @Test
    void recordLlmCall_withoutTodoContext_shouldHaveEmptyTodoId() throws Exception {
        String runId = "test-no-todo";
        setupStateStore(runId);

        AgentContext.clear(); // ensure no context

        service.recordLlmCall(runId, "planning", new TokenUsage(100, 50, 150),
                100L, 0L, 0L, null, null, null,
                null, "some response");

        AgentObservabilityService.ObservabilityState state =
                objectMapper.readValue(savedJson.get(), AgentObservabilityService.ObservabilityState.class);
        AgentObservabilityService.LlmTrace trace = state.getDiagnostics().getLlmTraces().get(0);

        assertEquals("", trace.getTodoId(), "todoId should be empty when not set");
        assertNull(trace.getTodoSequence(), "todoSequence should be null when not set");
    }
}
