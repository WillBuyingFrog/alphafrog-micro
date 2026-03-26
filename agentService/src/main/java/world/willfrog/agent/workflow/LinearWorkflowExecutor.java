package world.willfrog.agent.workflow;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ReAct 模式的线性工作流执行器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LinearWorkflowExecutor implements WorkflowExecutor {

    private final AgentEventService eventService;
    private final AgentPromptService promptService;
    private final ReactTodoExecutor reactTodoExecutor;
    private final PlanJudge planJudge;
    private final PatchPlanner patchPlanner;
    private final PlanPatcher planPatcher;
    private final AgentRunStateStore stateStore;

    @Value("${agent.flow.workflow.max-tool-calls:20}")
    private int defaultMaxToolCalls;

    @Value("${agent.flow.plan-patch.max-retries-per-todo:2}")
    private int maxRetriesPerTodoAfterJudge;

    @Value("${agent.flow.plan-patch.max-patches-per-run:2}")
    private int maxPatchesPerRun;

    private static final String PHASE_LINEAR_EXECUTION = "linear_execution";

    @Override
    public WorkflowExecutionResult execute(WorkflowRequest request) {
        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();
        TodoPlan currentPlan = request.getPlan();

        eventService.append(runId, userId, "REACT_LINEAR_EXECUTION_STARTED", Map.of(
                "items_count", currentPlan.getItems().size(),
                "plan_patch_enabled", request.isEnablePlanPatch()
        ));

        Set<String> availableTools = request.getToolSpecifications().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String userGoal = request.getUserGoal();

        List<CompletedTodoInfo> completedTodos = new ArrayList<>();
        Map<String, String> datasetRefs = new HashMap<>();
        Map<String, Integer> retryCountByTodo = new HashMap<>();
        Map<String, TodoExecutionRecord> executionContext = new LinkedHashMap<>();
        Set<String> completedTodoIds = new LinkedHashSet<>();

        int totalToolCalls = 0;
        int patchCount = 0;
        List<TodoItem> pendingItems = new ArrayList<>(currentPlan.getItems());
        int index = 0;

        while (index < pendingItems.size()) {
            TodoItem item = pendingItems.get(index);
            if (completedTodoIds.contains(item.getId())) {
                index++;
                continue;
            }
            if (totalToolCalls >= defaultMaxToolCalls) {
                eventService.append(runId, userId, "TOOL_CALL_LIMIT_REACHED", Map.of("limit", defaultMaxToolCalls));
                return buildFailureResult(completedTodos, "Tool call limit reached");
            }

            eventService.append(runId, userId, "TODO_STARTED", Map.of(
                    "todo_id", item.getId(),
                    "description", item.getDescription()
            ));

            ReactTodoExecutor.TodoExecutionContext todoContext = ReactTodoExecutor.TodoExecutionContext.builder()
                    .userGoal(userGoal)
                    .availableTools(availableTools)
                    .toolSpecifications(request.getToolSpecifications())
                    .completedTodos(new ArrayList<>(completedTodos))
                    .datasetRefs(new HashMap<>(datasetRefs))
                    .build();

            ReactTodoExecutor.TodoExecutionRecord record = reactTodoExecutor.executeWithObservability(
                    item.getDescription(),
                    todoContext,
                    request.getModel(),
                    runId,
                    PHASE_LINEAR_EXECUTION
            );
            totalToolCalls += record.getToolCallsUsed();

            if (record.isSuccess()) {
                datasetRefs.putAll(todoContext.getDatasetRefs());
                completedTodoIds.add(item.getId());
                completedTodos.add(CompletedTodoInfo.builder()
                        .todoId(item.getId())
                        .description(item.getDescription())
                        .output(record.getOutput())
                        .summary(record.getSummary())
                        .messageHistory(record.getMessageHistory())
                        .build());
                executionContext.put(item.getId(), toLegacyRecord(record));
                eventService.append(runId, userId, "TODO_COMPLETED", Map.of(
                        "todo_id", item.getId(),
                        "tool_calls_used", record.getToolCallsUsed(),
                        "summary", nvl(record.getSummary())
                ));
                index++;
                continue;
            }

            eventService.append(runId, userId, "TODO_FAILED", Map.of(
                    "todo_id", item.getId(),
                    "summary", nvl(record.getSummary())
            ));
            TodoExecutionRecord failedRecord = toLegacyRecord(record);

            if (!request.isEnablePlanPatch()) {
                return buildFailureResult(completedTodos, nvl(record.getSummary()));
            }

            // 若 run 已被取消或置为失败，跳过 plan patch，避免无效重试消耗 LLM 资源
            Optional<String> runStatus = stateStore.loadRunStatus(runId);
            if (runStatus.isPresent() &&
                    (runStatus.get().equals(AgentRunStatus.CANCELED.name()) ||
                     runStatus.get().equals(AgentRunStatus.FAILED.name()))) {
                log.info("Run {} has been {}, skipping plan patch", runId, runStatus.get());
                return buildFailureResult(completedTodos,
                        "run_" + runStatus.get().toLowerCase() + ":" + nvl(record.getSummary()));
            }

            JudgeDecision decision = planJudge.judge(
                    failedRecord,
                    currentPlan,
                    executionContext,
                    userGoal,
                    request.getModel()
            );

            if (decision == JudgeDecision.RETRY || decision == JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS) {
                int retries = retryCountByTodo.getOrDefault(item.getId(), 0);
                if (retries >= maxRetriesPerTodoAfterJudge) {
                    return buildFailureResult(completedTodos,
                            "todo_retry_exhausted:" + item.getId() + ":" + nvl(record.getSummary()));
                }
                retryCountByTodo.put(item.getId(), retries + 1);
                eventService.append(runId, userId, "TODO_RETRY_SCHEDULED", Map.of(
                        "todo_id", item.getId(),
                        "retry_count", retries + 1,
                        "max_retries", maxRetriesPerTodoAfterJudge
                ));
                continue;
            }

            if (decision == JudgeDecision.PATCH_PLAN) {
                if (patchCount >= maxPatchesPerRun) {
                    return buildFailureResult(completedTodos,
                            "plan_patch_exhausted:" + item.getId() + ":" + nvl(record.getSummary()));
                }
                PlanPatch patch = patchPlanner.generatePatch(
                        failedRecord,
                        currentPlan,
                        executionContext,
                        userGoal,
                        request.getModel()
                );
                if (patch == null) {
                    return buildFailureResult(completedTodos,
                            "plan_patch_generation_failed:" + item.getId() + ":" + nvl(record.getSummary()));
                }
                TodoPlan patchedPlan = planPatcher.applyPatch(currentPlan, patch);
                if (patchedPlan == null || patchedPlan.getItems() == null || patchedPlan.getItems().isEmpty()) {
                    return buildFailureResult(completedTodos,
                            "plan_patch_apply_failed:" + item.getId() + ":" + nvl(record.getSummary()));
                }
                patchCount++;
                currentPlan = patchedPlan;
                pendingItems = patchedPlan.getItems().stream()
                        .filter(t -> !completedTodoIds.contains(t.getId()))
                        .sorted(java.util.Comparator.comparingInt(TodoItem::getSequence))
                        .collect(Collectors.toCollection(ArrayList::new));
                index = findTodoIndex(pendingItems, item.getId());
                eventService.append(runId, userId, "PLAN_PATCH_APPLIED", Map.of(
                        "todo_id", item.getId(),
                        "patch_type", patch.getPatchType().name(),
                        "patch_count", patchCount,
                        "max_patches", maxPatchesPerRun
                ));
                continue;
            }

            if (decision == JudgeDecision.FAIL || decision == JudgeDecision.ABORT) {
                return buildFailureResult(completedTodos, nvl(record.getSummary()));
            }

            return buildFailureResult(completedTodos, nvl(record.getSummary()));
        }

        String finalAnswer = generateFinalAnswer(userGoal, completedTodos, request.getModel());
        return WorkflowExecutionResult.builder()
                .success(true)
                .finalAnswer(finalAnswer)
                .completedItems(new ArrayList<>())
                .toolCallsUsed(totalToolCalls)
                .build();
    }

    private int findTodoIndex(List<TodoItem> items, String todoId) {
        if (items == null || items.isEmpty() || todoId == null || todoId.isBlank()) {
            return 0;
        }
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            if (item != null && todoId.equals(item.getId())) {
                return i;
            }
        }
        return 0;
    }

    private TodoExecutionRecord toLegacyRecord(ReactTodoExecutor.TodoExecutionRecord record) {
        if (record == null) {
            return TodoExecutionRecord.builder().success(false).summary("empty_record").build();
        }
        return TodoExecutionRecord.builder()
                .success(record.isSuccess())
                .output(nvl(record.getOutput()))
                .summary(nvl(record.getSummary()))
                .toolCallsUsed(record.getToolCallsUsed())
                .build();
    }

    private String generateFinalAnswer(String userGoal,
                                       List<CompletedTodoInfo> completedTodos,
                                       ChatModel model) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(promptService.dagReactSystemPrompt()));

            StringBuilder context = new StringBuilder();
            context.append(promptService.finalAnswerStageInstruction()).append("\n\n");
            context.append(promptService.dynamicContextPrefix()).append("\n\n");
            context.append("用户问题：").append(userGoal).append("\n\n");
            context.append("已完成的任务：\n");
            for (CompletedTodoInfo todo : completedTodos) {
                context.append(String.format("- %s: %s\n", todo.getDescription(), todo.getSummary()));
                if (todo.getOutput() != null && !todo.getOutput().isEmpty()) {
                    context.append("  输出: ").append(todo.getOutput()).append("\n");
                }
            }
            context.append("\n请根据以上所有任务结果，生成对用户问题的最终回答。");
            context.append("\n输出格式: {\"answer\":\"<你的最终回答>\"}");
            messages.add(new UserMessage(context.toString()));

            ChatResponse response = model.chat(messages);
            return response.aiMessage() != null ? response.aiMessage().text() : "";
        } catch (Exception e) {
            log.error("Failed to generate final answer", e);
            return "无法生成回答: " + e.getMessage();
        }
    }

    private WorkflowExecutionResult buildFailureResult(List<CompletedTodoInfo> completedTodos, String errorMessage) {
        StringBuilder combinedOutput = new StringBuilder();
        for (CompletedTodoInfo todo : completedTodos) {
            if (todo.getOutput() != null && !todo.getOutput().isEmpty()) {
                combinedOutput.append(todo.getOutput()).append("\n");
            }
        }
        return WorkflowExecutionResult.builder()
                .success(false)
                .finalAnswer(combinedOutput.toString())
                .failureReason(errorMessage)
                .completedItems(new ArrayList<>())
                .build();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
