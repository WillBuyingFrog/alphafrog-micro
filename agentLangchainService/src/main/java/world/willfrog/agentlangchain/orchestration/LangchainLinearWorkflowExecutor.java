package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningRequest;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class LangchainLinearWorkflowExecutor {

    private static final int DEFAULT_MAX_TOOL_ROUND_TRIPS = 8;

    private final LangchainAiPlanner planner;
    private final AgentPromptService promptService;
    private final Optional<ToolProvider> toolProvider;

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
            AgentContext.setExtractedEntities(plan.getExtractedEntities());

            List<LangchainCompletedTodo> completedTodos = new ArrayList<>();
            for (TodoItem item : plan.getItems()) {
                AgentContext.setPhase("linear_execution");
                AgentContext.setStage("todo_execution");
                AgentContext.setTodoContext(item.getId(), item.getSequence());
                String output = buildTodoExecutor(request, toolCalls)
                        .execute(
                                promptService.dynamicContextPrefix() + "\n" + request.getUserGoal(),
                                renderCompletedTodos(completedTodos),
                                item.getDescription()
                        );
                if (isBlank(output)) {
                    return failure(plan, completedTodos, "empty_todo_output:" + item.getId(), toolCalls.get());
                }
                completedTodos.add(LangchainCompletedTodo.builder()
                        .todoId(item.getId())
                        .sequence(item.getSequence())
                        .description(item.getDescription())
                        .output(output.trim())
                        .summary(output.trim())
                        .build());
            }

            AgentContext.setPhase("summarizing");
            AgentContext.setStage("final_answer");
            String finalAnswer = buildFinalAnswerWriter(request)
                    .answer(
                            promptService.dynamicContextPrefix() + "\n"
                                    + promptService.finalAnswerStageInstruction() + "\n\n"
                                    + request.getUserGoal(),
                            renderCompletedTodos(completedTodos));
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

    private LangchainTodoExecutionAiService buildTodoExecutor(LangchainLinearWorkflowRequest request,
                                                              AtomicInteger toolCalls) {
        AiServices<LangchainTodoExecutionAiService> builder = AiServices
                .builder(LangchainTodoExecutionAiService.class)
                .chatModel(request.executionModelOrDefault())
                .systemMessageProvider(ignored -> promptService.dagReactSystemPrompt())
                .maxToolCallingRoundTrips(resolveMaxToolRoundTrips(request.getMaxToolRoundTrips()))
                .afterToolExecution(ignored -> toolCalls.incrementAndGet());
        toolProvider.ifPresent(builder::toolProvider);
        return builder.build();
    }

    private LangchainFinalAnswerAiService buildFinalAnswerWriter(LangchainLinearWorkflowRequest request) {
        return AiServices.builder(LangchainFinalAnswerAiService.class)
                .chatModel(request.finalAnswerModelOrDefault())
                .systemMessageProvider(ignored -> promptService.workflowFinalSystemPrompt())
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

    private int resolveMaxToolRoundTrips(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_MAX_TOOL_ROUND_TRIPS;
        }
        return Math.max(1, Math.min(requested, 30));
    }

    private String renderCompletedTodos(List<LangchainCompletedTodo> completedTodos) {
        if (completedTodos == null || completedTodos.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (LangchainCompletedTodo todo : completedTodos) {
            builder.append("- ")
                    .append(todo.getTodoId())
                    .append(" / ")
                    .append(todo.getDescription())
                    .append("\n  output: ")
                    .append(todo.getOutput())
                    .append("\n");
        }
        return builder.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
