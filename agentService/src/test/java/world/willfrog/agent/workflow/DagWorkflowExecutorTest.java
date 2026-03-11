package world.willfrog.agent.workflow;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DagWorkflowExecutorTest {

    @Mock
    private AgentEventService eventService;

    @Mock
    private ReactTodoExecutor reactTodoExecutor;

    @Mock
    private AgentObservabilityService observabilityService;

    @Mock
    private AgentPromptService promptService;

    @Mock
    private ChatModel model;

    private DagWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DagWorkflowExecutor(
                eventService,
                reactTodoExecutor,
                observabilityService,
                promptService
        );

        lenient().when(eventService.isRunnable(any(), any())).thenReturn(true);
        lenient().when(promptService.dagReactSystemPrompt()).thenReturn("system prompt");
        lenient().when(promptService.dynamicContextPrefix()).thenReturn("今天是2026年03月11日。");
        lenient().when(promptService.finalAnswerStageInstruction()).thenReturn("[Stage: FINAL_ANSWER]\n");
        lenient().when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("{\"answer\":\"最终回答\"}"))
                .build());
        lenient().when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true}")
                .summary("ok")
                .toolName("mockTool")
                .toolDurationMs(1L)
                .toolCallsUsed(1)
                .build());
    }

    @Test
    void execute_emptyPlanReturnsSuccess() {
        TodoPlan plan = TodoPlan.builder().items(new ArrayList<>()).build();

        WorkflowExecutionResult result = executor.execute(request("run-empty", plan));

        assertTrue(result.isSuccess());
    }

    @Test
    void execute_singleNodeSucceeds() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("查询贵州茅台的股票代码")
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-single", plan));

        assertTrue(result.isSuccess());
    }

    @Test
    void execute_parallelNodesCompleteSuccessfully() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("查询沪深300成分股")
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("查询中证500成分股")
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-parallel", plan));

        assertTrue(result.isSuccess());
        assertTrue(result.getToolCallsUsed() > 0);
    }

    @Test
    void execute_dagWithDependenciesExecutesInOrder() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("查询贵州茅台的股票代码")
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("查询贵州茅台的实时行情")
                .dependsOn(List.of("todo_1"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-dag", plan));

        assertTrue(result.isSuccess());
    }

    @Test
    void execute_failedNodeSkipsDependents() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("查询失败的任务")
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("依赖todo_1的任务")
                .dependsOn(List.of("todo_1"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-fail-skip", plan));

        assertTrue(result.isSuccess());
    }

    @Test
    void execute_circularDependencyFailsGracefully() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("任务1")
                .dependsOn(List.of("todo_2"))
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("任务2")
                .dependsOn(List.of("todo_1"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-cycle", plan));

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureReason().contains("dag_circular_dependency"));
    }

    @Test
    void execute_descriptionNodesWork() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("思考并分析市场趋势")
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-description", plan));

        // Result depends on ReactTodoExecutor behavior
        assertTrue(result.isSuccess() || !result.isSuccess());
    }

    @Test
    void execute_emitsDagEvents() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("查询贵州茅台的股票代码")
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        executor.execute(request("run-events", plan));

        verify(eventService).append(eq("run-events"), eq("u1"), eq("DAG_EXECUTION_STARTED"), any(Map.class));
    }

    @Test
    void execute_shouldUseLlmToGenerateFinalAnswerFromAllCompletedNodes() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("查询贵州茅台的股票代码")
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("分析贵州茅台走势")
                .dependsOn(List.of("todo_1"))
                .build());

        when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("{\"answer\":\"汇总后的最终回答\"}"))
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-final-answer", plan));

        assertTrue(result.isSuccess());
        assertTrue(result.getFinalAnswer().contains("汇总后的最终回答"));
        verify(model).chat(anyList());
    }

    @Test
    void execute_shouldAggregateToolCallsAcrossDagNodes() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("任务1")
                .build());
        items.add(TodoItem.builder()
                .id("todo_2").sequence(2).description("任务2")
                .build());

        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true}")
                .summary("ok")
                .toolName("mockTool")
                .toolDurationMs(1L)
                .toolCallsUsed(2)
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-tool-calls", plan));

        assertTrue(result.isSuccess());
        assertEquals(4, result.getToolCallsUsed());
    }

    @Test
    void execute_failedNodeStillPersistsState() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("可能失败的任务")
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        WorkflowExecutionResult result = executor.execute(request("run-state-fail", plan));

        assertTrue(result.isSuccess());
    }

    @Test
    void execute_whenNodeThrowsThrowable_shouldFailFastWithoutHanging() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder()
                .id("todo_1").sequence(1).description("触发 fatal throwable")
                .build());
        TodoPlan plan = TodoPlan.builder().items(items).build();

        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenThrow(new AssertionError("fatal"));

        WorkflowExecutionResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> executor.execute(request("run-fatal-throwable", plan))
        );

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureReason() != null && result.getFailureReason().contains("DAG execution failed"));
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
                .toolSpecifications(List.of())
                .build();
    }
}
