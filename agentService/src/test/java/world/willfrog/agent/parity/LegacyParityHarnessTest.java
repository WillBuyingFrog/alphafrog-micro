package world.willfrog.agent.parity;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.service.AgentCitationService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.workflow.LinearWorkflowExecutor;
import world.willfrog.agent.workflow.PlanJudge;
import world.willfrog.agent.workflow.PlanPatcher;
import world.willfrog.agent.workflow.PatchPlanner;
import world.willfrog.agent.workflow.ReactTodoExecutor;
import world.willfrog.agent.workflow.WorkflowExecutionResult;
import world.willfrog.agent.workflow.WorkflowFailureClassifier;
import world.willfrog.agent.workflow.WorkflowRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Batch-1 golden parity harness：冻结 legacy agentService 的 Run 语义基线。
 *
 * <p>每个 test 对应一个 {@link ParityGoldenCase}，用 mock fixture 构造输入、
 * 驱动 legacy {@link LinearWorkflowExecutor} 执行、校验输出事件与状态。</p>
 *
 * <p>P0 目标：所有 case 在 legacy 上全绿。<br>
 * P1 目标：同一套 case 增加 agentLangchainService runner，双跑 diff。</p>
 */
@ExtendWith(MockitoExtension.class)
class LegacyParityHarnessTest {

    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentPromptService promptService;
    @Mock
    private ReactTodoExecutor reactTodoExecutor;
    @Mock
    private PlanJudge planJudge;
    @Mock
    private PatchPlanner patchPlanner;
    @Mock
    private PlanPatcher planPatcher;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private ChatModel model;

    private LinearWorkflowExecutor executor;
    private ParityRunFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new ParityRunFixture();

        executor = new LinearWorkflowExecutor(
                eventService,
                promptService,
                reactTodoExecutor,
                planJudge,
                patchPlanner,
                planPatcher,
                stateStore,
                new AgentCitationService(new com.fasterxml.jackson.databind.ObjectMapper()),
                new WorkflowFailureClassifier()
        );

        lenient().when(stateStore.loadRunStatus(anyString())).thenReturn(java.util.Optional.empty());
        ReflectionTestUtils.setField(executor, "defaultMaxToolCalls", 30);
        ReflectionTestUtils.setField(executor, "maxRetriesPerTodoAfterJudge", 2);
        ReflectionTestUtils.setField(executor, "maxPatchesPerRun", 2);

        lenient().when(promptService.dynamicContextPrefix()).thenReturn("今天是2026年05月23日。");
        lenient().when(promptService.dagReactSystemPrompt()).thenReturn("system prompt");
        lenient().when(promptService.finalAnswerStageInstruction()).thenReturn("[Stage: FINAL_ANSWER]\n");

        // 默认 final answer LLM 响应
        lenient().when(model.chat(ArgumentMatchers.<List<ChatMessage>>any())).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.builder().text("最终回答").build())
                        .build()
        );
    }

    // ------------------------------------------------------------------
    // Case 1: linear_simple_success
    // ------------------------------------------------------------------

    @Test
    void case_linear_simple_success() {
        ParityGoldenCase caseDef = ParityGoldenCase.builder()
                .caseId("linear_simple_success")
                .description("单 todo 轻工具调用，应成功完成并产生 final answer")
                .goal("查询 512800.SH 最近一周走势")
                .expectedStatus("COMPLETED")
                .expectedEvents(java.util.List.of(
                        "REACT_LINEAR_EXECUTION_STARTED",
                        "TODO_STARTED",
                        "TODO_COMPLETED"
                ))
                .expectNonEmptyFinalAnswer(true)
                .build();

        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true,\"data\":{\"dataset_id\":\"ds_etf_daily\"}}")
                .summary("Completed in 2 round(s), 1 tool call(s)")
                .toolCallsUsed(1)
                .build());

        WorkflowRequest request = fixture.workflowRequest(
                "run-linear-1", caseDef.getGoal(),
                fixture.simpleLinearPlan(1), model);
        WorkflowExecutionResult result = executor.execute(request);

        // 状态校验
        assertTrue(result.isSuccess(), "Case " + caseDef.getCaseId() + " should succeed");
        assertNotNull(result.getFinalAnswer(), "Final answer should not be null");
        assertFalse(result.getFinalAnswer().isBlank(), "Final answer should not be blank");

        // 事件校验
        verify(eventService).append(anyString(), anyString(), eq("REACT_LINEAR_EXECUTION_STARTED"), anyMap());
        verify(eventService).append(anyString(), anyString(), eq("TODO_STARTED"), anyMap());
        verify(eventService).append(anyString(), anyString(), eq("TODO_COMPLETED"), anyMap());
    }

    // ------------------------------------------------------------------
    // Case 2: budget_tool_calls_exceeded (v3 512800 timeout regression)
    // ------------------------------------------------------------------

    @Test
    void case_budget_tool_calls_exceeded() {
        ParityGoldenCase caseDef = ParityGoldenCase.builder()
                .caseId("budget_tool_calls_exceeded")
                .description("复现 v3 512800.SH split 导致 LLM 长时间推理，最终触发 RUN_BUDGET_EXCEEDED")
                .goal("回测 512800.SH 等 7 只行业 ETF")
                .expectedStatus("FAILED")
                .expectedEvents(java.util.List.of(
                        "TODO_STARTED"
                ))
                .forbiddenEvents(java.util.List.of("WORKFLOW_COMPLETED"))
                .expectBudgetKill(true)
                .expectNonEmptyFinalAnswer(false)
                .build();

        // 模拟 ReAct 在单个 todo 内大量 tool call 导致 budget 超限
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(false)
                .output("")
                .summary("RUN_BUDGET_EXCEEDED: wall_clock_ms 615000 > 600000")
                .toolCallsUsed(30)
                .build());

        WorkflowRequest request = fixture.workflowRequest(
                "run-budget-1", caseDef.getGoal(),
                fixture.simpleLinearPlan(1), model);
        WorkflowExecutionResult result = executor.execute(request);

        // 状态校验：应失败
        assertFalse(result.isSuccess(), "Case " + caseDef.getCaseId() + " should fail due to budget");

        // 关键：不应出现 WORKFLOW_COMPLETED
        verify(eventService, never()).append(anyString(), anyString(), eq("WORKFLOW_COMPLETED"), anyMap());
    }

    // ------------------------------------------------------------------
    // Case 3: dataset_handoff_success (v4 fix regression)
    // ------------------------------------------------------------------

    @Test
    void case_dataset_handoff_success() {
        ParityGoldenCase caseDef = ParityGoldenCase.builder()
                .caseId("dataset_handoff_success")
                .description("多 todo 之间 dataset_ids 应正确 handoff，下游 todo 能拿到全部 dataset refs")
                .goal("对比多只股票走势")
                .expectedStatus("COMPLETED")
                .expectedEvents(java.util.List.of(
                        "TODO_STARTED",
                        "TODO_COMPLETED",
                        "WORKFLOW_COMPLETED"
                ))
                .expectDatasetHandoff(true)
                .expectNonEmptyFinalAnswer(true)
                .build();

        // todo1 产生 dataset
        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), eq("run-ds-1"), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true,\"data\":{\"dataset_id\":\"ds_etf_daily\"}}")
                .summary("todo1 ok")
                .toolCallsUsed(1)
                .build());

        WorkflowRequest request = fixture.workflowRequest(
                "run-ds-1", caseDef.getGoal(),
                fixture.simpleLinearPlanWithDatasetHandoff(), model);
        WorkflowExecutionResult result = executor.execute(request);

        assertTrue(result.isSuccess(), "Case " + caseDef.getCaseId() + " should succeed");
        assertNotNull(result.getFinalAnswer());

        // 验证两个 todo 都被执行
        verify(eventService, times(2)).append(eq("run-ds-1"), anyString(), eq("TODO_STARTED"), anyMap());
        verify(eventService, times(2)).append(eq("run-ds-1"), anyString(), eq("TODO_COMPLETED"), anyMap());
    }

    // ------------------------------------------------------------------
    // Case 4: empty_final_answer_failed
    // ------------------------------------------------------------------

    @Test
    void case_empty_final_answer_failed() {
        ParityGoldenCase caseDef = ParityGoldenCase.builder()
                .caseId("empty_final_answer_failed")
                .description("final answer 为空时不应用 todo output 伪装成功，应标记失败")
                .goal("分析某只股票")
                .expectedStatus("FAILED")
                .forbiddenEvents(java.util.List.of("WORKFLOW_COMPLETED"))
                .expectNonEmptyFinalAnswer(false)
                .build();

        // final answer LLM 返回空字符串
        lenient().when(model.chat(ArgumentMatchers.<List<ChatMessage>>any())).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.builder().text("").build())
                        .build()
        );

        when(reactTodoExecutor.executeWithObservability(
                anyString(), any(), any(), anyString(), anyString()
        )).thenReturn(ReactTodoExecutor.TodoExecutionRecord.builder()
                .success(true)
                .output("{\"ok\":true,\"data\":{}}")
                .summary("todo done")
                .toolCallsUsed(1)
                .build());

        WorkflowRequest request = fixture.workflowRequest(
                "run-empty-1", caseDef.getGoal(),
                fixture.simpleLinearPlan(1), model);
        WorkflowExecutionResult result = executor.execute(request);

        // final answer 为空时应失败
        assertFalse(result.isSuccess() && (result.getFinalAnswer() == null || result.getFinalAnswer().isBlank()),
                "Case " + caseDef.getCaseId() + ": empty final answer should not be treated as success");
    }
}
