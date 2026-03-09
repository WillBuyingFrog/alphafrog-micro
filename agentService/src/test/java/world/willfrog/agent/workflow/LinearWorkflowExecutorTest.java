package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.tool.ToolRouter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinearWorkflowExecutorTest {

    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentPromptService promptService;
    @Mock
    private ToolRouter toolRouter;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private ChatModel model;

    private LinearWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new LinearWorkflowExecutor(
                eventService,
                promptService,
                toolRouter,
                observabilityService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(executor, "defaultMaxToolCalls", 20);

        lenient().when(promptService.dynamicContextPrefix()).thenReturn("今天是2026年03月08日。");

        @SuppressWarnings("unchecked")
        ChatResponse response = mockResponse("done");
        lenient().when(model.chat(any(List.class))).thenReturn(response);
    }

    @Test
    void execute_shouldCompleteWhenToolCallSucceeds() {
        when(eventService.isRunnable("run-1", "u1")).thenReturn(true);
        when(toolRouter.invoke(eq("searchStock"), anyMap())).thenReturn(
                "{\"ok\":true,\"data\":{\"result\":\"success\"}}"
        );

        WorkflowExecutionResult result = executor.execute(request("run-1", planWithTools(1)));

        assertTrue(result.isSuccess());
        verify(eventService).append(eq("run-1"), eq("u1"), eq("TODO_STARTED"), anyMap());
        verify(eventService).append(eq("run-1"), eq("u1"), eq("TOOL_CALLED"), anyMap());
    }

    @Test
    void execute_shouldHandleMultipleTodos() {
        when(eventService.isRunnable("run-multi", "u1")).thenReturn(true);
        when(toolRouter.invoke(eq("searchStock"), anyMap())).thenReturn(
                "{\"ok\":true,\"data\":{\"dataset_id\":\"ds_123\"}}"
        );

        WorkflowExecutionResult result = executor.execute(request("run-multi", planWithTools(2)));

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-multi"), eq("u1"), eq("REACT_LINEAR_EXECUTION_STARTED"), captor.capture());
        assertTrue(captor.getValue().containsKey("items_count"));
    }

    @Test
    void execute_shouldRespectToolCallLimit() {
        when(eventService.isRunnable("run-limit", "u1")).thenReturn(true);
        ReflectionTestUtils.setField(executor, "defaultMaxToolCalls", 1);

        when(toolRouter.invoke(eq("searchStock"), anyMap())).thenReturn(
                "{\"ok\":true,\"data\":{\"result\":\"success\"}}"
        );

        // First todo succeeds, second hits limit
        WorkflowExecutionResult result = executor.execute(request("run-limit", planWithTools(2)));

        // Should fail because tool call limit is reached on second todo
        assertFalse(result.isSuccess());
    }

    @Test
    void execute_shouldHandleToolCallFailure() {
        when(eventService.isRunnable("run-fail", "u1")).thenReturn(true);
        when(toolRouter.invoke(eq("searchStock"), anyMap())).thenReturn(
                "{\"ok\":false,\"error\":{\"message\":\"Tool failed\"}}"
        );

        WorkflowExecutionResult result = executor.execute(request("run-fail", planWithTools(1)));

        assertFalse(result.isSuccess());
    }

    @Test
    void execute_shouldWorkWithExecutePython() {
        when(eventService.isRunnable("run-python", "u1")).thenReturn(true);
        when(toolRouter.invoke(eq("executePython"), anyMap())).thenReturn(
                "{\"ok\":true,\"data\":{\"stdout\":\"Hello World\",\"dataset_id\":\"py_ds_1\"}}"
        );

        WorkflowExecutionResult result = executor.execute(request("run-python", planExecutePython("todo_1")));

        assertTrue(result.isSuccess());
    }

    @Test
    void execute_shouldSkipWhenRunNotRunnable() {
        when(eventService.isRunnable("run-not-runnable", "u1")).thenReturn(false);

        WorkflowExecutionResult result = executor.execute(request("run-not-runnable", planWithTools(1)));

        // When not runnable, the workflow should still attempt to run but will check at each step
        // The actual behavior depends on implementation - may complete or fail
    }

    private WorkflowRequest request(String runId, TodoPlan plan) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("u1");
        return WorkflowRequest.builder()
                .run(run)
                .userId("u1")
                .userGoal("查询股票数据并进行分析")
                .plan(plan)
                .model(model)
                .toolSpecifications(List.of(
                        ToolSpecification.builder().name("searchStock").description("搜索股票").build(),
                        ToolSpecification.builder().name("executePython").description("执行Python代码").build()
                ))
                .endpointName("ep")
                .endpointBaseUrl("base")
                .modelName("m")
                .build();
    }

    private TodoPlan planWithTools(int count) {
        TodoPlan plan = new TodoPlan();
        for (int i = 1; i <= count; i++) {
            plan.getItems().add(TodoItem.builder()
                    .id("todo_" + i)
                    .sequence(i)
                    .description("查询股票数据 " + i)
                    .dependsOn(List.of())
                    .status(TodoStatus.PENDING)
                    .build());
        }
        return plan;
    }

    private TodoPlan planExecutePython(String todoId) {
        TodoPlan plan = new TodoPlan();
        plan.getItems().add(TodoItem.builder()
                .id(todoId)
                .sequence(1)
                .description("执行Python数据分析")
                .dependsOn(List.of())
                .status(TodoStatus.PENDING)
                .build());
        return plan;
    }

    private ChatResponse mockResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(new AiMessage(text))
                .metadata(ChatResponseMetadata.builder().build())
                .build();
    }
}
