package world.willfrog.agentlangchain.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.workflow.DatasetRefRegistry;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningRequest;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LINEAR（线性）工作流执行器 —— 按顺序逐个执行 Todo，一步失败则整个 run 失败。
 *
 * <h2>与 DAG 执行器的关系</h2>
 * DAG 是主线（支持并行依赖图），LINEAR 是降级简化版（顺序执行）。
 * 当 planning LLM 判断任务无需并行（或 DAG 执行失败触发 FALLBACK_TO_LINEAR）时走此路径。
 * 面试被问"你们执行模式有几种"，答案在本类和 DAG 执行器。
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li>调用 planner 生成 Todo 列表</li>
 *   <li>逐个执行 Todo（每个 Todo 经过 {@link LangchainTodoNodeExecutor}）</li>
 *   <li>每完成一个 Todo 后注册 dataset ref，供后续 Todo 引用</li>
 *   <li>执行前后各检查一次 cancel/pause 状态</li>
 *   <li>全部完成后调用 final answer 生成</li>
 * </ol>
 *
 * <h2>cancel/pause 防护</h2>
 * 每个 Todo 执行前和 final answer 生成前都通过
 * {@link LangchainRunExecutionGuard#stopReason} 检查是否有未处理的 cancel/pause 信号。
 *
 * @see LangchainDagWorkflowExecutor DAG 并行执行器
 * @see LangchainWorkflowRouting 决策 LINEAR vs DAG 的路由逻辑
 */
@Service
@RequiredArgsConstructor
public class LangchainLinearWorkflowExecutor {

    private final LangchainAiPlanner planner;
    private final LangchainTodoNodeExecutor todoNodeExecutor;
    private final LangchainRunExecutionGuard executionGuard;

    public LangchainLinearWorkflowResult execute(LangchainLinearWorkflowRequest request) {
        validate(request);
        AtomicInteger toolCalls = new AtomicInteger();
        try {
            applyRunContext(request);
            AgentContext.setPhase("planning");
            LangchainTodoPlan plan = planner.plan(LangchainPlanningRequest.builder()
                    .runId(request.getRunId())
                    .userId(request.getUserId())
                    .userGoal(request.getUserGoal())
                    .dialogueContext(request.getDialogueContext())
                    .model(request.planningModelOrDefault())
                    .planningEndpointName(request.getPlanningEndpointName())
                    .planningModelName(request.getPlanningModelName())
                    .planningProviderOrder(request.getPlanningProviderOrder())
                    .toolSpecifications(request.getToolSpecifications())
                    .executionMode(PlanExecutionMode.LINEAR)
                    .maxTodos(request.getMaxTodos())
                    .build());
            return executePlanned(request, plan, toolCalls);
        } catch (Exception e) {
            return LangchainLinearWorkflowResult.builder()
                    .success(false)
                    .failureReason(e.getMessage())
                    .toolCallsUsed(toolCalls.get())
                    .build();
        } finally {
            AgentContext.clear();
        }
    }

    public LangchainLinearWorkflowResult executePlanned(LangchainLinearWorkflowRequest request,
                                                        LangchainTodoPlan plan) {
        validate(request);
        AtomicInteger toolCalls = new AtomicInteger();
        try {
            applyRunContext(request);
            return executePlanned(request, plan, toolCalls);
        } catch (Exception e) {
            return LangchainLinearWorkflowResult.builder()
                    .success(false)
                    .failureReason(e.getMessage())
                    .plan(plan)
                    .toolCallsUsed(toolCalls.get())
                    .build();
        } finally {
            AgentContext.clear();
        }
    }

    private LangchainLinearWorkflowResult executePlanned(LangchainLinearWorkflowRequest request,
                                                         LangchainTodoPlan plan,
                                                         AtomicInteger toolCalls) {
        AgentContext.setExtractedEntities(plan.getExtractedEntities());
        List<LangchainCompletedTodo> completedTodos = new ArrayList<>();
        Map<String, String> datasetRefs = LangchainTodoUserMessageBuilder.newDatasetRefMap();
        for (TodoItem item : plan.getItems()) {
            Optional<String> stop = executionGuard.stopReason(request.getRunId(), request.getUserId());
            if (stop.isPresent()) {
                return interrupted(plan, completedTodos, stop.get(), toolCalls.get());
            }
            AgentContext.setPhase("linear_execution");
            AgentContext.setStage("todo_execution");
            LangchainTodoNodeResult nodeResult = todoNodeExecutor.execute(
                    request, item, completedTodos, datasetRefs, toolCalls);
            if (!nodeResult.isSuccess()) {
                return failure(plan, completedTodos,
                        nvl(nodeResult.getFailureReason(), nodeResult.getSummary()), toolCalls.get());
            }
            String trimmed = nodeResult.getOutput();
            DatasetRefRegistry.registerFromJson(trimmed, datasetRefs);
            completedTodos.add(LangchainCompletedTodo.builder()
                    .todoId(item.getId())
                    .sequence(item.getSequence())
                    .description(item.getDescription())
                    .output(trimmed)
                    .summary(nodeResult.getSummary())
                    .build());
        }

        Optional<String> stopBeforeAnswer = executionGuard.stopReason(request.getRunId(), request.getUserId());
        if (stopBeforeAnswer.isPresent()) {
            return interrupted(plan, completedTodos, stopBeforeAnswer.get(), toolCalls.get());
        }

        AgentContext.setPhase("summarizing");
        AgentContext.setStage("final_answer");
        String finalAnswer = todoNodeExecutor.writeFinalAnswer(request, completedTodos);
        if (isBlank(finalAnswer)) {
            return failure(plan, completedTodos, "empty_final_answer", toolCalls.get());
        }
        return LangchainLinearWorkflowResult.builder()
                .success(true)
                .finalAnswer(finalAnswer.trim())
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCalls.get())
                .build();
    }

    private LangchainLinearWorkflowResult failure(LangchainTodoPlan plan,
                                                  List<LangchainCompletedTodo> completedTodos,
                                                  String reason,
                                                  int toolCallsUsed) {
        return LangchainLinearWorkflowResult.builder()
                .success(false)
                .failureReason(reason)
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCallsUsed)
                .build();
    }

    private LangchainLinearWorkflowResult interrupted(LangchainTodoPlan plan,
                                                      List<LangchainCompletedTodo> completedTodos,
                                                      String controlStatus,
                                                      int toolCallsUsed) {
        return LangchainLinearWorkflowResult.builder()
                .success(false)
                .interrupted(true)
                .failureReason("RUN_INTERRUPTED:" + controlStatus)
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCallsUsed)
                .build();
    }

    private void applyRunContext(LangchainLinearWorkflowRequest request) {
        if (!isBlank(request.getRunId())) {
            AgentContext.setRunId(request.getRunId());
        }
        if (!isBlank(request.getUserId())) {
            AgentContext.setUserId(request.getUserId());
        }
        AgentContext.setWebSearchEnabled(Boolean.TRUE.equals(request.getWebSearchEnabled()));
    }

    private void validate(LangchainLinearWorkflowRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("linear_workflow_request_required");
        }
        if (request.getModel() == null && request.getPlanningModel() == null) {
            throw new IllegalArgumentException("linear_workflow_chat_model_required");
        }
        if (isBlank(request.getUserGoal())) {
            throw new IllegalArgumentException("linear_workflow_user_goal_required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nvl(String primary, String fallback) {
        if (!isBlank(primary)) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }
}
