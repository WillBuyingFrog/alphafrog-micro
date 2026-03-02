package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DashScopeChatModelTest {

    @Test
    void supportsThinking_shouldMatchQwen3AndQwqPrefixes() {
        DashScopeChatModel qwen3Model = newModel("qwen3-max");
        DashScopeChatModel qwqModel = newModel("qwq-plus");
        DashScopeChatModel regularModel = newModel("qwen-plus");

        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(qwen3Model, "supportsThinking", "qwen3-max")));
        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(qwqModel, "supportsThinking", "qwq-plus")));
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(regularModel, "supportsThinking", "qwen-plus")));
    }

    @Test
    void applyThinkingConfig_shouldOnlyUseLatestUserMessage() {
        DashScopeChatModel model = newModel("qwen3-max");
        Map<String, Object> request = new LinkedHashMap<>();
        List<ChatMessage> messages = List.of(
                UserMessage.from("/no_think"),
                AiMessage.from("/think")
        );

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, messages);

        assertEquals(Boolean.FALSE, request.get("enable_thinking"));
        assertFalse(request.containsKey("thinking_budget"));
    }

    @Test
    void applyThinkingConfig_shouldEnableThinkingAndBudgetByDefault() {
        DashScopeChatModel model = newModel("qwen3-max");
        Map<String, Object> request = new LinkedHashMap<>();
        List<ChatMessage> messages = List.of(
                SystemMessage.from("/no_think in system prompt"),
                UserMessage.from("请继续分析")
        );

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, messages);

        assertEquals(Boolean.TRUE, request.get("enable_thinking"));
        assertEquals(38912, request.get("thinking_budget"));
    }

    @Test
    void applyThinkingConfig_shouldRespectLastDirectiveInLatestUserMessage() {
        DashScopeChatModel model = newModel("qwen3-max");
        Map<String, Object> request = new LinkedHashMap<>();
        List<ChatMessage> messages = List.of(UserMessage.from("先 /no_think 再 /think"));

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, messages);

        assertEquals(Boolean.TRUE, request.get("enable_thinking"));
        assertEquals(38912, request.get("thinking_budget"));
    }

    @Test
    void applyThinkingConfig_shouldSkipNonThinkingModels() {
        DashScopeChatModel model = newModel("qwen-plus");
        Map<String, Object> request = new LinkedHashMap<>();
        List<ChatMessage> messages = List.of(UserMessage.from("/no_think"));

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, messages);

        assertTrue(request.isEmpty());
    }

    @Test
    void extractThinkingContent_shouldSplitThinkTags() {
        DashScopeChatModel model = newModel("qwen3-max");

        Object thinkingContent = ReflectionTestUtils.invokeMethod(
                model,
                "extractThinkingContent",
                "<think>先推理A</think>结论A<think>再推理B</think>结论B"
        );

        assertEquals("结论A结论B", ReflectionTestUtils.invokeMethod(thinkingContent, "content"));
        assertEquals("先推理A\n再推理B", ReflectionTestUtils.invokeMethod(thinkingContent, "thinking"));
    }

    private DashScopeChatModel newModel(String modelName) {
        return new DashScopeChatModel(
                new ObjectMapper(),
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "dashscope-key",
                modelName,
                0.6D,
                1024,
                mock(RawHttpLogger.class),
                mock(AgentObservabilityService.class),
                "dashscope"
        );
    }
}
