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
