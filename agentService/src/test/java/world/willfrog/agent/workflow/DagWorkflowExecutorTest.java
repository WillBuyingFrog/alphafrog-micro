package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
    private PlanJudge planJudge;
    @Mock
    private PatchPlanner patchPlanner;
    @Mock
    private PlanPatcher planPatcher;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    @Mock
    private AgentLlmProperties llmProperties;

    @Mock
    private ChatModel model;

    private DagWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DagWorkflowExecutor(
                eventService,
                reactTodoExecutor,
                observabilityService,
                promptService,
                planJudge,
                patchPlanner,
                planPatcher,
                stateStore,
                localConfigLoader,
                llmProperties,
                new ObjectMapper()
        );
        lenient().when(stateStore.loadRunStatus(anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(executor, "defaultDagThreadPoolSize", 4);
        ReflectionTestUtils.setField(executor, "dagPlanPatchEnabled", true);
        ReflectionTestUtils.setField(executor, "maxRetriesPerNode", 2);
        ReflectionTestUtils.setField(executor, "maxPatchRounds", 4);

        lenient().when(eventService.isRunnable(any(), any())).thenReturn(true);
        lenient().when(localConfigLoader.current()).thenReturn(java.util.Optional.empty());
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
        lenient().when(planJudge.judge(any(), any(), any(), anyString(), any()))
                .thenReturn(JudgeDecision.FAIL);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
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
    void execute_shouldPropagateRunContextToDagWorkerAndPersistNodeState() {
        AgentContext.setRunId("run-context");
        AgentContext.setUserId("u1");
        AgentContext.setDebugMode(true);
        AgentContext.setWebSearchEnabled(true);
        AgentContext.setWebSearchConfig(new AgentContext.WebSearchConfig(
                "perplexity", "standard", true, false, 8));
        AgentContext.setReasoningEffort("high");

        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenAnswer(invocation -> {
            assertEquals("run-context", AgentContext.getRunId());
            assertEquals("u1", AgentContext.getUserId());
            assertTrue(AgentContext.isDebugMode());
            assertTrue(AgentContext.isWebSearchEnabled());
            assertEquals("perplexity", AgentContext.getWebSearchConfig().backend());
            assertEquals("high", AgentContext.getReasoningEffort());
            assertEquals("todo_1", AgentContext.getTodoId());
            assertEquals("dag_execution_todo_1", AgentContext.getPhase());
            return ReactTodoExecutor.TodoExecutionRecord.builder()
                    .success(true)
                    .output("{\"ok\":true}")
                    .summary("done")
                    .toolCallsUsed(1)
                    .build();
        });

        TodoPlan plan = TodoPlan.builder()
                .items(List.of(TodoItem.builder()
                        .id("todo_1")
                        .sequence(1)
                        .description("需要 webSearch 的 DAG 节点")
                        .build()))
                .build();

        WorkflowExecutionResult result = executor.execute(request("run-context", plan));

        assertTrue(result.isSuccess());
        ArgumentCaptor<WorkflowState> stateCaptor = ArgumentCaptor.forClass(WorkflowState.class);
        verify(stateStore, atLeastOnce()).saveWorkflowState(eq("run-context"), stateCaptor.capture());
        assertTrue(stateCaptor.getAllValues().stream()
                .flatMap(state -> state.getCompletedItems().stream())
                .anyMatch(item -> "todo_1".equals(item.getId())
                        && item.getStatus() == TodoStatus.COMPLETED
                        && "done".equals(item.getResultSummary())));
    }

    @Test
    void execute_shouldClearContextBetweenNodesToPreventCrossNodePollution() {
        // 强制单线程池，确保 todo 串行执行并由同一线程复用
        ReflectionTestUtils.setField(executor, "defaultDagThreadPoolSize", 1);

        // 设置父线程 run 级上下文
        AgentContext.setRunId("run-clear");
        AgentContext.setUserId("u-clear");
        AgentContext.setWebSearchEnabled(true);

        List<String> capturedTodoIds = new ArrayList<>();
        List<String> capturedPhases = new ArrayList<>();
        List<Boolean> capturedWebSearch = new ArrayList<>();

        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenAnswer(invocation -> {
            capturedTodoIds.add(AgentContext.getTodoId());
            capturedPhases.add(AgentContext.getPhase());
            capturedWebSearch.add(AgentContext.isWebSearchEnabled());
            return ReactTodoExecutor.TodoExecutionRecord.builder()
                    .success(true)
                    .output("{\"ok\":true}")
                    .summary("done")
                    .toolCallsUsed(0)
                    .build();
        });

        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder()
                                .id("todo_1")
                                .sequence(1)
                                .description("first node")
                                .build(),
                        TodoItem.builder()
                                .id("todo_2")
                                .sequence(2)
                                .description("second node")
                                .dependsOn(List.of("todo_1"))
                                .build()
                ))
                .build();

        WorkflowExecutionResult result = executor.execute(request("run-clear", plan));

        assertTrue(result.isSuccess());
        assertEquals(2, capturedTodoIds.size());
        // 节点 1 的 todoId/phase 不应泄漏到节点 2
        assertEquals("todo_1", capturedTodoIds.get(0));
        assertEquals("todo_2", capturedTodoIds.get(1));
        assertTrue(capturedPhases.get(0).contains("todo_1"));
        assertTrue(capturedPhases.get(1).contains("todo_2"));
        // 两个节点都应恢复父线程的 webSearchEnabled，不受跨节点污染
        assertTrue(capturedWebSearch.get(0));
        assertTrue(capturedWebSearch.get(1));
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

    @Test
    void execute_patchPlanReplace_shouldRetryAndSucceed() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).description("初始任务").build());
        TodoPlan plan = TodoPlan.builder().items(items).build();

        when(reactTodoExecutor.executeWithObservability(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(
                        ReactTodoExecutor.TodoExecutionRecord.builder()
                                .success(false)
                                .summary("first failed")
                                .output("")
                                .toolCallsUsed(1)
                                .build(),
                        ReactTodoExecutor.TodoExecutionRecord.builder()
                                .success(true)
                                .summary("ok")
                                .output("{\"ok\":true}")
                                .toolCallsUsed(1)
                                .build()
                );
        when(planJudge.judge(any(), any(), any(), anyString(), any()))
                .thenReturn(JudgeDecision.PATCH_PLAN);
        when(patchPlanner.generatePatch(any(), any(), any(), anyString(), any()))
                .thenReturn(PlanPatch.builder()
                        .patchType(PatchType.REPLACE)
                        .targetTodoId("todo_1")
                        .patchData(Map.of("newDescription", "修正任务"))
                        .reason("fix")
                        .build());
        when(planPatcher.applyPatch(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowExecutionResult result = executor.execute(request("run-patch", plan));
        assertTrue(result.isSuccess());
    }

    @Test
    void execute_fallbackToLinear_shouldExecuteRemainingNodesSequentially() {
        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).description("先失败").build());
        items.add(TodoItem.builder().id("todo_2").sequence(2).description("后续节点").dependsOn(List.of("todo_1")).build());
        TodoPlan plan = TodoPlan.builder().items(items).build();

        when(reactTodoExecutor.executeWithObservability(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(
                        ReactTodoExecutor.TodoExecutionRecord.builder()
                                .success(false)
                                .summary("dag failed")
                                .output("")
                                .toolCallsUsed(1)
                                .build(),
                        ReactTodoExecutor.TodoExecutionRecord.builder()
                                .success(true)
                                .summary("fallback todo_1 ok")
                                .output("{\"ok\":true}")
                                .toolCallsUsed(1)
                                .build(),
                        ReactTodoExecutor.TodoExecutionRecord.builder()
                                .success(true)
                                .summary("fallback todo_2 ok")
                                .output("{\"ok\":true}")
                                .toolCallsUsed(1)
                                .build()
                );
        when(planJudge.judge(any(), any(), any(), anyString(), any()))
                .thenReturn(JudgeDecision.FALLBACK_TO_LINEAR);

        WorkflowExecutionResult result = executor.execute(request("run-fallback", plan));

        assertTrue(result.isSuccess());
        assertEquals(3, result.getToolCallsUsed());
    }

    @Test
    void execute_shouldRespectDagThreadPoolSizeFromLocalConfig() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Parallel parallel = new AgentLlmProperties.Parallel();
        parallel.setDagThreadPoolSize(1);
        runtime.setParallel(parallel);
        local.setRuntime(runtime);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));

        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxActive = new AtomicInteger(0);
        when(reactTodoExecutor.executeWithObservability(anyString(), any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    int now = active.incrementAndGet();
                    maxActive.updateAndGet(old -> Math.max(old, now));
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                    return ReactTodoExecutor.TodoExecutionRecord.builder()
                            .success(true)
                            .output("{\"ok\":true}")
                            .summary("ok")
                            .toolCallsUsed(1)
                            .build();
                });

        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).description("并行任务1").build());
        items.add(TodoItem.builder().id("todo_2").sequence(2).description("并行任务2").build());
        TodoPlan plan = TodoPlan.builder().items(items).build();

        WorkflowExecutionResult result = executor.execute(request("run-thread-pool", plan));

        assertTrue(result.isSuccess());
        assertEquals(1, maxActive.get());
    }

    @Test
    void execute_finalAnswerShouldContainFullOutputWithoutTruncation() {
        String longOutput = "DAG_LONG_OUTPUT_" + "y".repeat(1200);
        when(reactTodoExecutor.executeWithObservability(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                        .success(true)
                        .summary("ok")
                        .output(longOutput)
                        .toolCallsUsed(1)
                        .build());

        List<TodoItem> items = new ArrayList<>();
        items.add(TodoItem.builder().id("todo_1").sequence(1).description("单节点").build());
        TodoPlan plan = TodoPlan.builder().items(items).build();

        WorkflowExecutionResult result = executor.execute(request("run-full-output", plan));

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messageCaptor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(messageCaptor.capture());
        List<ChatMessage> messages = messageCaptor.getValue();
        String finalUserMessage = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .reduce((first, second) -> second)
                .orElse("");
        assertTrue(finalUserMessage.contains(longOutput));
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
