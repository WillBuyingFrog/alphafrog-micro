package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.tool.ToolRouter;
import world.willfrog.agent.workflow.WorkflowRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DagWorkflowExecutorTest {

    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentPromptService promptService;
    @Mock
    private ToolRouter toolRouter;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private ToolCallCounter toolCallCounter;
    @Mock
    private ChatModel model;

    private DagWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        ReactTodoExecutor reactTodoExecutor = mock(ReactTodoExecutor.class);
        executor = new DagWorkflowExecutor(
                eventService,
                reactTodoExecutor
        );

        lenient().when(eventService.isRunnable(any(), any())).thenReturn(true);

        ChatResponse response = mockResponse("完成");
        lenient().when(model.chat(any(List.class))).thenReturn(response);
    }

    @Test
    void execute_emptyPlanReturnsSuccess() {
        TodoPlan plan = TodoPlan.builder().items(new ArrayList<>()).build();

        WorkflowExecutionResult result = executor.execute(request("run-empty", plan));

        assertTrue(result.isSuccess());
    }

    @Test
    void execute_singleNodeSucceeds() {
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(true)
                        .output("{\"ok\":true}")
                        .build()
        );

        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("")
                .toolName("searchStock").params(Map.of("keyword", "沪深300"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-single", plan));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getCompletedItems().size());
        verify(stateStore, atLeastOnce()).saveWorkflowState(eq("run-single"), any());
        verify(stateStore).clearWorkflowState("run-single");
        verify(toolCallCounter).increment("run-single", 1);
    }

    @Test
    void execute_parallelNodesCompleteSuccessfully() {
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(true)
                        .output("{\"ok\":true}")
                        .build()
        );

        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("")
                .toolName("searchStock").params(Map.of("keyword", "沪深300"))
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("")
                .toolName("searchStock").params(Map.of("keyword", "中证500"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-parallel", plan));

        assertTrue(result.isSuccess());
        assertEquals(2, result.getCompletedItems().size());
    }

    @Test
    void execute_dagWithDependenciesExecutesInOrder() {
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(true)
                        .output("{\"ok\":true}")
                        .build()
        );

        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("")
                .toolName("searchStock").params(Map.of("keyword", "沪深300"))
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("")
                .toolName("searchStock").params(Map.of("keyword", "结果"))
                .dependsOn(List.of("todo_1"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-dag", plan));

        assertTrue(result.isSuccess());
        assertEquals(2, result.getCompletedItems().size());
    }

    @Test
    void execute_failedNodeSkipsDependents() {
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(false)
                        .output("error")
                        .build()
        );

        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("")
                .toolName("searchStock").params(Map.of("keyword", "沪深300"))
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("")
                .toolName("searchStock").params(Map.of("keyword", "依赖"))
                .dependsOn(List.of("todo_1"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-fail-skip", plan));

        assertFalse(result.isSuccess());
    }

    @Test
    void execute_circularDependencyFailsGracefully() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("")
                .toolName("searchStock").dependsOn(List.of("todo_2"))
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("")
                .toolName("searchStock").dependsOn(List.of("todo_1"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-cycle", plan));

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureReason().contains("dag_circular_dependency"));
    }

    @Test
    void execute_thoughtNodesSucceedImmediately() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("")
                .reasoning("思考步骤")
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-thought", plan));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getCompletedItems().size());
        verify(toolCallCounter, never()).increment(eq("run-thought"), anyInt());
    }

    @Test
    void execute_emitsDagEvents() {
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(true)
                        .output("{\"ok\":true}")
                        .build()
        );

        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("")
                .toolName("searchStock").params(Map.of("keyword", "沪深300"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        executor.execute(request("run-events", plan));

        verify(eventService).append(eq("run-events"), eq("u1"), eq("DAG_EXECUTION_STARTED"), anyMap());
        verify(eventService).append(eq("run-events"), eq("u1"), eq("DAG_EXECUTION_COMPLETED"), anyMap());
    }

    @Test
    void execute_failedNodeStillPersistsDagState() {
        when(toolRouter.invokeWithMeta(eq("searchStock"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .success(false)
                        .output("error")
                        .build()
        );

        TodoPlan plan = TodoPlan.builder().items(List.of(
                TodoItem.builder()
                        .id("todo_1").sequence(1).description("")
                        .toolName("searchStock").params(Map.of("keyword", "沪深300"))
                        .build()
        )).build();

        WorkflowExecutionResult result = executor.execute(request("run-state-fail", plan));

        assertFalse(result.isSuccess());
        verify(stateStore, atLeastOnce()).saveWorkflowState(eq("run-state-fail"), any());
        verify(stateStore).clearWorkflowState("run-state-fail");
        verify(toolCallCounter).increment("run-state-fail", 1);
    }

    private WorkflowRequest request(String runId, TodoPlan plan) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("u1");
        return WorkflowRequest.builder()
                .run(run)
                .userId("u1")
                .userGoal("test goal")
                .plan(plan)
                .model(model)
                .endpointName("ep")
                .endpointBaseUrl("base")
                .modelName("model")
                .build();
    }

    @SuppressWarnings("unchecked")
    private ChatResponse mockResponse(String text) {
        ChatResponse response = mock(ChatResponse.class);
        AiMessage aiMessage = AiMessage.from(text);
        lenient().when(response.aiMessage()).thenReturn(aiMessage);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        lenient().when(response.metadata()).thenReturn(metadata);
        return response;
    }
}
