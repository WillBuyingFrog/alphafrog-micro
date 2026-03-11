package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.tool.ToolRouter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReactTodoExecutorTest {

    @Mock
    private AgentPromptService promptService;
    @Mock
    private ToolRouter toolRouter;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private ChatModel model;

    private ReactTodoExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ReactTodoExecutor(promptService, toolRouter, new ObjectMapper(), observabilityService);
        AgentContext.clear();

        lenient().when(promptService.dagReactSystemPrompt()).thenReturn("system prompt");
        lenient().when(promptService.dynamicContextPrefix()).thenReturn("dynamic prefix");
        lenient().when(observabilityService.recordLlmCall(
                anyString(), anyString(), any(), anyLong(),
                any(), any(), any(), anyMap(), anyString()
        )).thenReturn("trace-1");
    }

    @Test
    void executeWithObservability_shouldClearDecisionContextAfterSuccessfulToolCall() {
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("{\"tool\":\"searchIndex\",\"params\":{\"keyword\":\"沪深300\"}}"))
                .build());
        when(toolRouter.invoke(eq("searchIndex"), anyMap())).thenReturn("{\"ok\":true}");

        ReactTodoExecutor.TodoExecutionRecord record = executor.executeWithObservability(
                "搜索指数",
                context(),
                model,
                "run-1",
                "dag_execution"
        );

        assertTrue(record.isSuccess());
        assertNull(AgentContext.getDecisionTraceId());
        assertNull(AgentContext.getDecisionStage());
        assertNull(AgentContext.getDecisionExcerpt());
    }

    @Test
    void executeWithObservability_shouldClearDecisionContextWhenToolThrowsNonExceptionThrowable() {
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("{\"tool\":\"searchIndex\",\"params\":{\"keyword\":\"沪深300\"}}"))
                .build());
        when(toolRouter.invoke(eq("searchIndex"), anyMap())).thenThrow(new AssertionError("fatal"));

        assertThrows(AssertionError.class, () -> executor.executeWithObservability(
                "搜索指数",
                context(),
                model,
                "run-1",
                "dag_execution"
        ));
        assertNull(AgentContext.getDecisionTraceId());
        assertNull(AgentContext.getDecisionStage());
        assertNull(AgentContext.getDecisionExcerpt());
    }

    private ReactTodoExecutor.TodoExecutionContext context() {
        return ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal("分析指数")
                .availableTools(Set.of("searchIndex"))
                .completedTodos(List.of())
                .datasetRefs(Map.of())
                .build();
    }
}
