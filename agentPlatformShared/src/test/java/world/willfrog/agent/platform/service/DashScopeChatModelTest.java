package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashScopeChatModelTest {

    @Test
    void supportsThinking_shouldOnlyMatchQwen35AndQwen36Prefixes() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);

        assertTrue(invokeSupportsThinking(model, "qwen3.6-max-preview"));
        assertTrue(invokeSupportsThinking(model, "qwen3.6-plus"));
        assertTrue(invokeSupportsThinking(model, "qwen3.5-plus"));
        assertTrue(invokeSupportsThinking(model, "qwen3.5-flash"));
        assertTrue(invokeSupportsThinking(model, "QWEN3.6-MAX-PREVIEW"));

        // 旧版 qwen3-max / qwq 已不再被识别为 thinking 模型
        assertFalse(invokeSupportsThinking(model, "qwen3-max"));
        assertFalse(invokeSupportsThinking(model, "qwq-plus"));
        assertFalse(invokeSupportsThinking(model, "qwen-plus"));
        assertFalse(invokeSupportsThinking(model, ""));
        assertFalse(invokeSupportsThinking(model, null));
    }

    @Test
    void applyThinkingConfig_shouldEnableWhenFeatureOnAndModelSupports() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);
        Map<String, Object> request = new LinkedHashMap<>();

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, java.util.List.of());

        assertEquals(Boolean.TRUE, request.get("enable_thinking"));
        assertEquals(38912, request.get("thinking_budget"));
    }

    @Test
    void applyThinkingConfig_shouldSkipWhenFeatureDisabled() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", false);
        Map<String, Object> request = new LinkedHashMap<>();

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, java.util.List.of());

        assertTrue(request.isEmpty());
    }

    @Test
    void applyThinkingConfig_shouldSkipForUnsupportedModels() {
        // 即使 enableThinking=true，但 qwen-plus 不在支持列表内
        DashScopeChatModel model = newModel("qwen-plus", true);
        Map<String, Object> request = new LinkedHashMap<>();

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, java.util.List.of());

        assertTrue(request.isEmpty());
    }

    @Test
    void extractThinkingContent_shouldSplitThinkTags() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);

        Object thinkingContent = ReflectionTestUtils.invokeMethod(
                model,
                "extractThinkingContent",
                "<think>先推理A</think>结论A<think>再推理B</think>结论B"
        );

        assertEquals("结论A结论B", ReflectionTestUtils.invokeMethod(thinkingContent, "content"));
        assertEquals("先推理A\n再推理B", ReflectionTestUtils.invokeMethod(thinkingContent, "thinking"));
    }

    @Test
    void extractThinkingContent_shouldHandleNullOrBlank() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);

        Object empty = ReflectionTestUtils.invokeMethod(model, "extractThinkingContent", (Object) null);
        assertEquals("", ReflectionTestUtils.invokeMethod(empty, "thinking"));
    }

    @Test
    void reportLlmCall_shouldExposeProviderTraceIdForOuterDedup() {
        AgentContext.clear();
        AgentContext.setRunId("run-dashscope-trace");
        AgentObservabilityService observabilityService = mock(AgentObservabilityService.class);
        when(observabilityService.recordLlmCallWithRawHttp(
                anyString(),
                anyString(),
                any(),
                any(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyString(),
                anyString(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn("trace-dashscope-1");
        DashScopeChatModel model = new DashScopeChatModel(
                new ObjectMapper(),
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "dashscope-key",
                "qwen-plus",
                0.6D,
                1024,
                mock(RawHttpLogger.class),
                observabilityService,
                "dashscope",
                true,
                mock(AgentLlmLocalConfigLoader.class)
        );

        String traceId = ReflectionTestUtils.invokeMethod(
                model,
                "reportLlmCall",
                null,
                null,
                null,
                100L,
                50L,
                null,
                null,
                null
        );

        assertEquals("trace-dashscope-1", traceId);
        assertEquals("trace-dashscope-1", AgentContext.consumeProviderLlmTraceId());
        AgentContext.clear();
    }

    @Test
    void doChat_shouldCheckBudgetBeforeNetworkAttempt() {
        DashScopeChatModel model = newModel("qwen-plus", true);
        AgentRunBudgetService budgetService = mock(AgentRunBudgetService.class);
        doThrow(new IllegalStateException("budget exceeded"))
                .when(budgetService).checkBeforeLlmCall();
        model.setBudgetService(budgetService);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(new UserMessage("hello")))
                .build();

        assertThrows(IllegalStateException.class, () -> model.doChat(request));
        verify(budgetService).checkBeforeLlmCall();
        verify(budgetService, never()).checkHttpAttempt(anyInt());
    }

    private static boolean invokeSupportsThinking(DashScopeChatModel model, String name) {
        Boolean result = ReflectionTestUtils.invokeMethod(model, "supportsThinking", name);
        return Boolean.TRUE.equals(result);
    }

    private DashScopeChatModel newModel(String modelName, boolean enableThinking) {
        return new DashScopeChatModel(
                new ObjectMapper(),
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "dashscope-key",
                modelName,
                0.6D,
                1024,
                mock(RawHttpLogger.class),
                mock(AgentObservabilityService.class),
                "dashscope",
                enableThinking,
                mock(AgentLlmLocalConfigLoader.class)
        );
    }
}
