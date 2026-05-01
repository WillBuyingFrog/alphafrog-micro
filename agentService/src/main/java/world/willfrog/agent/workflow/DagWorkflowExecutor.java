package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * DAG 并行执行器（ReAct 模式）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DagWorkflowExecutor implements WorkflowExecutor {

    private final AgentEventService eventService;
    private final ReactTodoExecutor reactTodoExecutor;
    private final AgentObservabilityService observabilityService;
    private final AgentPromptService promptService;
    private final PlanJudge planJudge;
    private final PatchPlanner patchPlanner;
    private final PlanPatcher planPatcher;
    private final AgentRunStateStore stateStore;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    private static final String PHASE_DAG_EXECUTION = "dag_execution";

    @Value("${agent.flow.dag.thread-pool-size:4}")
    private int defaultDagThreadPoolSize;

    @Value("${agent.flow.dag.plan-patch.enabled:true}")
    private boolean dagPlanPatchEnabled;

    @Value("${agent.flow.dag.plan-patch.max-retries-per-node:2}")
    private int maxRetriesPerNode;

    @Value("${agent.flow.dag.plan-patch.max-rounds:4}")
    private int maxPatchRounds;

    @Override
    public WorkflowExecutionResult execute(WorkflowRequest request) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        TodoPlan originalPlan = request.getPlan();
        List<TodoItem> originalItems = originalPlan.getItems();

        if (originalItems == null || originalItems.isEmpty()) {
            return WorkflowExecutionResult.builder()
                    .success(true)
                    .finalAnswer("")
                    .build();
        }

        long startedAt = System.currentTimeMillis();
        eventService.append(runId, userId, "DAG_EXECUTION_STARTED", Map.of(
                "total_nodes", originalItems.size(),
                "dag_patch_enabled", dagPlanPatchEnabled
        ));

        Map<String, Integer> retryByFailedNode = new HashMap<>();
        int patchRound = 0;
        TodoPlan workingPlan = clonePlan(originalPlan);
        DagRunOutcome lastOutcome = null;

        while (patchRound <= maxPatchRounds) {
            DagRunOutcome outcome = runSingleDag(workingPlan, request);
            lastOutcome = outcome;
            if (outcome.success()) {
                String finalAnswer = generateFinalAnswer(outcome.sharedContext(), request);
                recordDagCompletion(runId, userId, true, startedAt, null, outcome.totalToolCallsUsed());
                return WorkflowExecutionResult.builder()
                        .success(true)
                        .finalAnswer(finalAnswer)
                        .toolCallsUsed(outcome.totalToolCallsUsed())
                        .build();
            }

            if (!dagPlanPatchEnabled || outcome.failedItem() == null) {
                recordDagCompletion(runId, userId, false, startedAt, outcome.failureReason(), outcome.totalToolCallsUsed());
                return WorkflowExecutionResult.builder()
                        .success(false)
                        .failureReason(outcome.failureReason())
                        .toolCallsUsed(outcome.totalToolCallsUsed())
                        .build();
            }

            // 若 run 已被取消或置为失败，跳过 plan patch，避免无效重试消耗 LLM 资源
            Optional<String> runStatus = stateStore.loadRunStatus(runId);
            if (runStatus.isPresent() &&
                    (runStatus.get().equals(AgentRunStatus.CANCELED.name()) ||
                     runStatus.get().equals(AgentRunStatus.FAILED.name()))) {
                log.info("Run {} has been {}, skipping DAG plan patch", runId, runStatus.get());
                String reason = "run_" + runStatus.get().toLowerCase() + ":" + nvl(outcome.failureReason());
                recordDagCompletion(runId, userId, false, startedAt, reason, outcome.totalToolCallsUsed());
                return WorkflowExecutionResult.builder()
                        .success(false)
                        .failureReason(reason)
                        .toolCallsUsed(outcome.totalToolCallsUsed())
                        .build();
            }

            String failedNodeId = outcome.failedItem().getId();
            int usedRetries = retryByFailedNode.getOrDefault(failedNodeId, 0);
            TodoExecutionRecord failedRecord = toLegacyRecord(outcome.failedRecord());
            JudgeDecision decision = planJudge.judge(
                    failedRecord,
                    workingPlan,
                    outcome.judgeContext(),
                    request.getUserGoal(),
                    request.getModel()
            );

            if (decision == JudgeDecision.FALLBACK_TO_LINEAR) {
                FallbackResult fallbackResult = fallbackToLinear(workingPlan, outcome.sharedContext(), request);
                int totalToolCalls = outcome.totalToolCallsUsed() + fallbackResult.toolCallsUsed();
                if (fallbackResult.success()) {
                    String finalAnswer = generateFinalAnswer(outcome.sharedContext(), request);
                    recordDagCompletion(runId, userId, true, startedAt, null, totalToolCalls);
                    return WorkflowExecutionResult.builder()
                            .success(true)
                            .finalAnswer(finalAnswer)
                            .toolCallsUsed(totalToolCalls)
                            .build();
                }
                recordDagCompletion(runId, userId, false, startedAt, fallbackResult.failureReason(), totalToolCalls);
                return WorkflowExecutionResult.builder()
                        .success(false)
                        .failureReason(fallbackResult.failureReason())
                        .toolCallsUsed(totalToolCalls)
                        .build();
            }

            if (decision == JudgeDecision.PATCH_PLAN) {
                if (usedRetries >= maxRetriesPerNode || patchRound >= maxPatchRounds) {
                    String reason = "dag_patch_retry_exhausted:" + failedNodeId;
                    recordDagCompletion(runId, userId, false, startedAt, reason, outcome.totalToolCallsUsed());
                    return WorkflowExecutionResult.builder()
                            .success(false)
                            .failureReason(reason)
                            .toolCallsUsed(outcome.totalToolCallsUsed())
                            .build();
                }
                PlanPatch patch = patchPlanner.generatePatch(
                        failedRecord,
                        workingPlan,
                        outcome.judgeContext(),
                        request.getUserGoal(),
                        request.getModel()
                );
                if (patch == null || patch.getPatchType() != PatchType.REPLACE) {
                    String reason = "dag_patch_invalid_or_unsupported:" + failedNodeId;
                    recordDagCompletion(runId, userId, false, startedAt, reason, outcome.totalToolCallsUsed());
                    return WorkflowExecutionResult.builder()
                            .success(false)
                            .failureReason(reason)
                            .toolCallsUsed(outcome.totalToolCallsUsed())
                            .build();
                }
                TodoPlan patched = planPatcher.applyPatch(workingPlan, patch);
                if (patched == null || patched.getItems() == null || patched.getItems().isEmpty()) {
                    String reason = "dag_patch_apply_failed:" + failedNodeId;
                    recordDagCompletion(runId, userId, false, startedAt, reason, outcome.totalToolCallsUsed());
                    return WorkflowExecutionResult.builder()
                            .success(false)
                            .failureReason(reason)
                            .toolCallsUsed(outcome.totalToolCallsUsed())
                            .build();
                }
                retryByFailedNode.put(failedNodeId, usedRetries + 1);
                patchRound++;
                workingPlan = patched;
                eventService.append(runId, userId, "DAG_PLAN_PATCH_APPLIED", Map.of(
                        "failed_node_id", failedNodeId,
                        "patch_type", patch.getPatchType().name(),
                        "patch_round", patchRound,
                        "max_patch_rounds", maxPatchRounds
                ));
                continue;
            }

            if (decision == JudgeDecision.RETRY || decision == JudgeDecision.CONTINUE_WITH_RECOVERY_PARAMS) {
                if (usedRetries >= maxRetriesPerNode) {
                    String reason = "dag_retry_exhausted:" + failedNodeId;
                    recordDagCompletion(runId, userId, false, startedAt, reason, outcome.totalToolCallsUsed());
                    return WorkflowExecutionResult.builder()
                            .success(false)
                            .failureReason(reason)
                            .toolCallsUsed(outcome.totalToolCallsUsed())
                            .build();
                }
                retryByFailedNode.put(failedNodeId, usedRetries + 1);
                patchRound++;
                eventService.append(runId, userId, "DAG_NODE_RETRY_SCHEDULED", Map.of(
                        "failed_node_id", failedNodeId,
                        "retry_count", usedRetries + 1,
                        "max_retries", maxRetriesPerNode
                ));
                continue;
            }

            String reason = outcome.failureReason();
            recordDagCompletion(runId, userId, false, startedAt, reason, outcome.totalToolCallsUsed());
            return WorkflowExecutionResult.builder()
                    .success(false)
                    .failureReason(reason)
                    .toolCallsUsed(outcome.totalToolCallsUsed())
                    .build();
        }

        String reason = lastOutcome == null ? "dag_execution_failed" : lastOutcome.failureReason();
        recordDagCompletion(runId, userId, false, startedAt, reason, lastOutcome == null ? 0 : lastOutcome.totalToolCallsUsed());
        return WorkflowExecutionResult.builder()
                .success(false)
                .failureReason(reason)
                .toolCallsUsed(lastOutcome == null ? 0 : lastOutcome.totalToolCallsUsed())
                .build();
    }

    private DagRunOutcome runSingleDag(TodoPlan plan, WorkflowRequest request) {
        List<TodoItem> items = plan.getItems();
        ExecutionGraph graph = buildExecutionGraph(items);
        if (graph.hasCycle()) {
            return DagRunOutcome.failure("dag_circular_dependency", null, null, null, Map.of(), 0);
        }

        SharedExecutionContext sharedContext = new SharedExecutionContext(
                request.getUserGoal(),
                request.getToolSpecifications().stream()
                        .map(dev.langchain4j.agent.tool.ToolSpecification::name)
                        .collect(Collectors.toSet()),
                request.getToolSpecifications()
        );

        Map<String, ReactTodoExecutor.TodoExecutionRecord> resultMap;
        int totalToolCalls;
        try {
            DagParallelResult parallelResult = executeDagParallel(graph, sharedContext, request);
            resultMap = parallelResult.resultMap();
            totalToolCalls = parallelResult.totalToolCallsUsed();
        } catch (Exception e) {
            String reason = "DAG execution failed: " + nvl(e.getMessage());
            return DagRunOutcome.failure(reason, null, null, sharedContext, Map.of(), 0);
        }

        Map<String, TodoExecutionRecord> judgeContext = new LinkedHashMap<>();
        for (TodoItem item : items) {
            ReactTodoExecutor.TodoExecutionRecord record = resultMap.get(item.getId());
            if (record != null) {
                judgeContext.put(item.getId(), toLegacyRecord(record));
            }
            if (record == null || !record.isSuccess()) {
                String reason = record == null ? "No result" : nvl(record.getSummary());
                return DagRunOutcome.failure(reason, item, record, sharedContext, judgeContext, totalToolCalls);
            }
        }
        return DagRunOutcome.success(sharedContext, judgeContext, totalToolCalls);
    }

    private DagParallelResult executeDagParallel(ExecutionGraph graph,
                                                 SharedExecutionContext sharedContext,
                                                 WorkflowRequest request) throws Exception {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        List<TodoItem> items = request.getPlan().getItems();

        Map<String, CountDownLatch> nodeLatches = new ConcurrentHashMap<>();
        Map<String, ReactTodoExecutor.TodoExecutionRecord> results = new ConcurrentHashMap<>();
        Map<String, Boolean> nodeSuccess = new ConcurrentHashMap<>();
        AtomicInteger totalToolCallsUsed = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        CompletableFuture<Void> allDone = new CompletableFuture<>();
        Object workflowStateLock = new Object();
        Map<String, TodoItem> nodeStates = new LinkedHashMap<>();
        AgentContext.ContextSnapshot parentContext = AgentContext.captureRunContext();

        for (TodoItem item : items) {
            nodeLatches.put(item.getId(), new CountDownLatch(graph.getDependencies(item.getId()).size()));
        }

        int poolSize = resolveDagThreadPoolSize();
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            for (TodoItem item : items) {
                executor.submit(() -> {
                    try {
                        AgentContext.restoreRunContext(parentContext);
                        nodeLatches.get(item.getId()).await();
                        String failedDependency = findFailedDependency(graph.getDependencies(item.getId()), nodeSuccess);
                        if (failedDependency != null) {
                            ReactTodoExecutor.TodoExecutionRecord skipped = ReactTodoExecutor.TodoExecutionRecord.builder()
                                    .success(false)
                                    .summary("Skipped: dependency " + failedDependency + " failed")
                                    .output("")
                                    .build();
                            results.put(item.getId(), skipped);
                            nodeSuccess.put(item.getId(), false);
                            persistDagNodeState(runId, items, workflowStateLock, nodeStates,
                                    item, TodoStatus.SKIPPED, skipped, totalToolCallsUsed.get());
                            eventService.append(runId, userId, "DAG_NODE_SKIPPED", Map.of(
                                    "todo_id", item.getId(),
                                    "failed_dependency", failedDependency
                            ));
                        } else {
                            AgentContext.setTodoContext(item.getId(), item.getSequence());
                            AgentContext.setPhase(PHASE_DAG_EXECUTION + "_" + item.getId());
                            persistDagNodeState(runId, items, workflowStateLock, nodeStates,
                                    item, TodoStatus.RUNNING, null, totalToolCallsUsed.get());
                            try {
                                ReactTodoExecutor.TodoExecutionRecord record = reactTodoExecutor.executeWithObservability(
                                        item.getDescription(),
                                        buildTodoContext(sharedContext),
                                        request.getModel(),
                                        runId,
                                        PHASE_DAG_EXECUTION + "_" + item.getId()
                                );
                                results.put(item.getId(), record);
                                nodeSuccess.put(item.getId(), record.isSuccess());
                                totalToolCallsUsed.addAndGet(record.getToolCallsUsed());
                                if (record.isSuccess()) {
                                    sharedContext.addCompletedTodo(item, record);
                                    extractDatasetIds(record, sharedContext);
                                    persistDagNodeState(runId, items, workflowStateLock, nodeStates,
                                            item, TodoStatus.COMPLETED, record, totalToolCallsUsed.get());
                                    eventService.append(runId, userId, "DAG_NODE_COMPLETED", Map.of(
                                            "todo_id", item.getId(),
                                            "tool_calls_used", record.getToolCallsUsed()
                                    ));
                                } else {
                                    persistDagNodeState(runId, items, workflowStateLock, nodeStates,
                                            item, TodoStatus.FAILED, record, totalToolCallsUsed.get());
                                    eventService.append(runId, userId, "DAG_NODE_FAILED", Map.of(
                                            "todo_id", item.getId(),
                                            "summary", nvl(record.getSummary())
                                    ));
                                }
                            } finally {
                                AgentContext.clearTodoContext();
                                AgentContext.clearPhase();
                            }
                        }
                    } catch (Throwable t) {
                        log.error("Failed to execute DAG node {}", item.getId(), t);
                        ReactTodoExecutor.TodoExecutionRecord failed = ReactTodoExecutor.TodoExecutionRecord.builder()
                                .success(false)
                                .summary("DAG execution failed: " + nvl(t.getMessage()))
                                .output("")
                                .build();
                        results.put(item.getId(), failed);
                        nodeSuccess.put(item.getId(), false);
                        persistDagNodeState(runId, items, workflowStateLock, nodeStates,
                                item, TodoStatus.FAILED, failed, totalToolCallsUsed.get());
                    } finally {
                        AgentContext.clear();
                        for (String downstream : graph.getDependents(item.getId())) {
                            CountDownLatch latch = nodeLatches.get(downstream);
                            if (latch != null) {
                                latch.countDown();
                            }
                        }
                        if (completedCount.incrementAndGet() == items.size()) {
                            allDone.complete(null);
                        }
                    }
                });
            }

            allDone.get(30, TimeUnit.MINUTES);
            return new DagParallelResult(results, totalToolCallsUsed.get());
        } catch (TimeoutException e) {
            throw new RuntimeException("DAG execution failed: timeout", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private void persistDagNodeState(String runId,
                                     List<TodoItem> planItems,
                                     Object lock,
                                     Map<String, TodoItem> nodeStates,
                                     TodoItem item,
                                     TodoStatus status,
                                     ReactTodoExecutor.TodoExecutionRecord record,
                                     int totalToolCallsUsed) {
        if (runId == null || runId.isBlank() || item == null || status == null) {
            return;
        }
        synchronized (lock) {
            nodeStates.put(item.getId(), copyWithStatus(item, status, record));
            List<TodoItem> completedItems = new ArrayList<>();
            Set<String> completedNodeIds = new HashSet<>();
            Set<String> runningNodeIds = new HashSet<>();
            for (TodoItem planItem : planItems) {
                TodoItem state = nodeStates.get(planItem.getId());
                if (state == null || state.getStatus() == null || state.getStatus() == TodoStatus.PENDING) {
                    continue;
                }
                if (state.getStatus() == TodoStatus.RUNNING) {
                    runningNodeIds.add(state.getId());
                    continue;
                }
                completedItems.add(state);
                if (state.getStatus() == TodoStatus.COMPLETED) {
                    completedNodeIds.add(state.getId());
                }
            }
            stateStore.saveWorkflowState(runId, WorkflowState.builder()
                    .executionMode(PlanExecutionMode.DAG)
                    .completedItems(completedItems)
                    .completedNodeIds(completedNodeIds)
                    .runningNodeIds(runningNodeIds)
                    .toolCallsUsed(Math.max(0, totalToolCallsUsed))
                    .savedAt(Instant.now())
                    .build());
        }
    }

    private TodoItem copyWithStatus(TodoItem item,
                                    TodoStatus status,
                                    ReactTodoExecutor.TodoExecutionRecord record) {
        return TodoItem.builder()
                .id(item.getId())
                .sequence(item.getSequence())
                .description(item.getDescription())
                .dependsOn(item.getDependsOn() == null ? List.of() : new ArrayList<>(item.getDependsOn()))
                .groupKey(item.getGroupKey())
                .parallelizable(item.isParallelizable())
                .status(status)
                .createdAt(item.getCreatedAt())
                .completedAt(isTerminal(status) ? Instant.now() : null)
                .resultSummary(record == null ? null : nvl(record.getSummary()))
                .output(record == null ? null : nvl(record.getOutput()))
                .build();
    }

    private boolean isTerminal(TodoStatus status) {
        return status == TodoStatus.COMPLETED || status == TodoStatus.FAILED || status == TodoStatus.SKIPPED;
    }

    private String findFailedDependency(Set<String> deps, Map<String, Boolean> nodeSuccess) {
        for (String depId : deps) {
            Boolean ok = nodeSuccess.get(depId);
            if (ok == null || !ok) {
                return depId;
            }
        }
        return null;
    }

    private FallbackResult fallbackToLinear(TodoPlan plan,
                                            SharedExecutionContext sharedContext,
                                            WorkflowRequest request) {
        Set<String> completedIds = sharedContext.getCompletedTodos().stream()
                .map(CompletedTodoInfo::getTodoId)
                .collect(Collectors.toSet());
        List<TodoItem> remaining = plan.getItems().stream()
                .filter(item -> !completedIds.contains(item.getId()))
                .sorted(Comparator.comparingInt(TodoItem::getSequence))
                .toList();
        int toolCallsUsed = 0;
        for (TodoItem item : remaining) {
            ReactTodoExecutor.TodoExecutionRecord record = reactTodoExecutor.executeWithObservability(
                    item.getDescription(),
                    buildTodoContext(sharedContext),
                    request.getModel(),
                    request.getRun().getId(),
                    PHASE_DAG_EXECUTION + "_fallback_linear_" + item.getId()
            );
            toolCallsUsed += record.getToolCallsUsed();
            if (!record.isSuccess()) {
                return FallbackResult.failure("dag_fallback_linear_failed:" + item.getId() + ":" + nvl(record.getSummary()), toolCallsUsed);
            }
            sharedContext.addCompletedTodo(item, record);
            extractDatasetIds(record, sharedContext);
        }
        return FallbackResult.success(toolCallsUsed);
    }

    private void extractDatasetIds(ReactTodoExecutor.TodoExecutionRecord record, SharedExecutionContext context) {
        try {
            JsonNode root = objectMapper.readTree(nvl(record.getOutput()));
            JsonNode data = root.path("data");
            if (data.isObject()) {
                String datasetId = data.path("dataset_id").asText("");
                if (!datasetId.isBlank()) {
                    context.registerDatasetRef(datasetId, "/sandbox/input/" + datasetId);
                }
                JsonNode ids = data.path("dataset_ids");
                if (ids.isArray()) {
                    for (JsonNode idNode : ids) {
                        String id = idNode.asText("");
                        if (!id.isBlank()) {
                            context.registerDatasetRef(id, "/sandbox/input/" + id);
                        }
                    }
                } else if (ids.isTextual()) {
                    for (String id : ids.asText("").split(",")) {
                        String trimmed = id.trim();
                        if (!trimmed.isBlank()) {
                            context.registerDatasetRef(trimmed, "/sandbox/input/" + trimmed);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract dataset ids from record output: {}", e.getMessage());
        }
    }

    private ReactTodoExecutor.TodoExecutionContext buildTodoContext(SharedExecutionContext sharedContext) {
        return ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal(sharedContext.getUserGoal())
                .availableTools(sharedContext.getAvailableTools())
                .toolSpecifications(sharedContext.getToolSpecifications())
                .completedTodos(new ArrayList<>(sharedContext.getCompletedTodos()))
                .datasetRefs(new HashMap<>(sharedContext.getDatasetRefs()))
                .build();
    }

    private String generateFinalAnswer(SharedExecutionContext context, WorkflowRequest request) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(promptService.dagReactSystemPrompt()));

            StringBuilder contextText = new StringBuilder();
            contextText.append(promptService.finalAnswerStageInstruction()).append("\n\n");
            contextText.append(promptService.dynamicContextPrefix()).append("\n\n");
            contextText.append("用户问题：").append(context.getUserGoal()).append("\n\n");
            contextText.append("已完成的任务：\n");
            for (CompletedTodoInfo todo : context.getCompletedTodos()) {
                contextText.append(String.format("- %s: %s\n", todo.getDescription(), todo.getSummary()));
                if (todo.getOutput() != null && !todo.getOutput().isEmpty()) {
                    contextText.append("  输出: ").append(todo.getOutput()).append("\n");
                }
            }
            contextText.append("\n请根据以上所有任务结果，生成对用户问题的最终回答。");
            contextText.append("\n输出格式: {\"answer\":\"<你的最终回答>\"}");

            messages.add(new UserMessage(contextText.toString()));
            ChatResponse response = request.getModel().chat(messages);
            var aiMessage = response.aiMessage();
            return aiMessage != null ? aiMessage.text() : "";
        } catch (Exception e) {
            log.error("Failed to generate DAG final answer", e);
            List<CompletedTodoInfo> completed = context.getCompletedTodos();
            if (completed.isEmpty()) {
                return "无执行结果";
            }
            String fallback = completed.get(completed.size() - 1).getOutput();
            return fallback != null ? fallback : "无法生成回答: " + e.getMessage();
        }
    }

    private int resolveDagThreadPoolSize() {
        Integer local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getParallel)
                .map(AgentLlmProperties.Parallel::getDagThreadPoolSize)
                .orElse(null);
        if (local != null && local > 0) {
            return clamp(local, 1, 32);
        }
        Integer base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getParallel)
                .map(AgentLlmProperties.Parallel::getDagThreadPoolSize)
                .orElse(null);
        if (base != null && base > 0) {
            return clamp(base, 1, 32);
        }
        return clamp(defaultDagThreadPoolSize, 1, 32);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    private TodoPlan clonePlan(TodoPlan plan) {
        TodoPlan cloned = new TodoPlan();
        cloned.setAnalysis(plan.getAnalysis());
        List<TodoItem> clonedItems = new ArrayList<>();
        for (TodoItem item : plan.getItems()) {
            clonedItems.add(TodoItem.builder()
                    .id(item.getId())
                    .sequence(item.getSequence())
                    .description(item.getDescription())
                    .dependsOn(item.getDependsOn() == null ? List.of() : new ArrayList<>(item.getDependsOn()))
                    .groupKey(item.getGroupKey())
                    .parallelizable(item.isParallelizable())
                    .status(item.getStatus())
                    .createdAt(item.getCreatedAt())
                    .completedAt(item.getCompletedAt())
                    .resultSummary(item.getResultSummary())
                    .output(item.getOutput())
                    .build());
        }
        cloned.setItems(clonedItems);
        cloned.setExecutionMode(plan.getExecutionMode());
        cloned.setDagMetadata(plan.getDagMetadata());
        return cloned;
    }

    private void recordDagCompletion(String runId,
                                     String userId,
                                     boolean success,
                                     long startedAt,
                                     String failureReason,
                                     int totalToolCallsUsed) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("success", success);
        payload.put("duration_ms", Math.max(0, System.currentTimeMillis() - startedAt));
        payload.put("total_tool_calls_used", totalToolCallsUsed);
        if (!success) {
            payload.put("failure_reason", nvl(failureReason));
        }
        eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", payload);
    }

    private ExecutionGraph buildExecutionGraph(List<TodoItem> items) {
        Map<String, TodoItem> itemMap = items.stream()
                .collect(Collectors.toMap(TodoItem::getId, i -> i));
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (TodoItem item : items) {
            dependencies.putIfAbsent(item.getId(), new HashSet<>());
            dependents.putIfAbsent(item.getId(), new HashSet<>());
            for (String depId : item.getDependsOn()) {
                dependencies.putIfAbsent(depId, new HashSet<>());
                dependents.putIfAbsent(depId, new HashSet<>());
                if (itemMap.containsKey(depId)) {
                    dependencies.get(item.getId()).add(depId);
                    dependents.get(depId).add(item.getId());
                }
            }
        }
        return new ExecutionGraph(itemMap, dependencies, dependents);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private record DagParallelResult(
            Map<String, ReactTodoExecutor.TodoExecutionRecord> resultMap,
            int totalToolCallsUsed
    ) {
    }

    private record DagRunOutcome(
            boolean success,
            String failureReason,
            TodoItem failedItem,
            ReactTodoExecutor.TodoExecutionRecord failedRecord,
            SharedExecutionContext sharedContext,
            Map<String, TodoExecutionRecord> judgeContext,
            int totalToolCallsUsed
    ) {
        static DagRunOutcome success(SharedExecutionContext sharedContext,
                                     Map<String, TodoExecutionRecord> judgeContext,
                                     int totalToolCallsUsed) {
            return new DagRunOutcome(true, "", null, null, sharedContext, judgeContext, totalToolCallsUsed);
        }

        static DagRunOutcome failure(String failureReason,
                                     TodoItem failedItem,
                                     ReactTodoExecutor.TodoExecutionRecord failedRecord,
                                     SharedExecutionContext sharedContext,
                                     Map<String, TodoExecutionRecord> judgeContext,
                                     int totalToolCallsUsed) {
            return new DagRunOutcome(false, failureReason, failedItem, failedRecord, sharedContext, judgeContext, totalToolCallsUsed);
        }
    }

    private record FallbackResult(
            boolean success,
            String failureReason,
            int toolCallsUsed
    ) {
        static FallbackResult success(int toolCallsUsed) {
            return new FallbackResult(true, "", toolCallsUsed);
        }

        static FallbackResult failure(String failureReason, int toolCallsUsed) {
            return new FallbackResult(false, failureReason, toolCallsUsed);
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ExecutionGraph {
        private Map<String, TodoItem> itemMap;
        private Map<String, Set<String>> dependencies;
        private Map<String, Set<String>> dependents;

        public Set<String> getDependencies(String nodeId) {
            return dependencies.getOrDefault(nodeId, Set.of());
        }

        public Set<String> getDependents(String nodeId) {
            return dependents.getOrDefault(nodeId, Set.of());
        }

        public boolean hasCycle() {
            Set<String> visiting = new HashSet<>();
            Set<String> visited = new HashSet<>();
            for (String nodeId : itemMap.keySet()) {
                if (hasCycleFrom(nodeId, visiting, visited)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasCycleFrom(String nodeId, Set<String> visiting, Set<String> visited) {
            if (visited.contains(nodeId)) {
                return false;
            }
            if (!visiting.add(nodeId)) {
                return true;
            }
            for (String depId : dependencies.getOrDefault(nodeId, Set.of())) {
                if (itemMap.containsKey(depId) && hasCycleFrom(depId, visiting, visited)) {
                    return true;
                }
            }
            visiting.remove(nodeId);
            visited.add(nodeId);
            return false;
        }
    }

    private static class SharedExecutionContext {
        private final String userGoal;
        private final Set<String> availableTools;
        private final List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications;
        private final List<CompletedTodoInfo> completedTodos = new CopyOnWriteArrayList<>();
        private final Map<String, String> datasetRefs = new ConcurrentHashMap<>();

        SharedExecutionContext(String userGoal,
                               Set<String> availableTools,
                               List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications) {
            this.userGoal = userGoal;
            this.availableTools = availableTools;
            this.toolSpecifications = toolSpecifications == null ? List.of() : new ArrayList<>(toolSpecifications);
        }

        void addCompletedTodo(TodoItem item, ReactTodoExecutor.TodoExecutionRecord record) {
            completedTodos.add(CompletedTodoInfo.builder()
                    .todoId(item.getId())
                    .description(item.getDescription())
                    .output(record.getOutput())
                    .summary(record.getSummary())
                    .messageHistory(record.getMessageHistory())
                    .build());
        }

        void registerDatasetRef(String datasetId, String path) {
            datasetRefs.put(datasetId, path);
        }

        String getUserGoal() {
            return userGoal;
        }

        Set<String> getAvailableTools() {
            return availableTools;
        }

        List<dev.langchain4j.agent.tool.ToolSpecification> getToolSpecifications() {
            return new ArrayList<>(toolSpecifications);
        }

        List<CompletedTodoInfo> getCompletedTodos() {
            return new ArrayList<>(completedTodos);
        }

        Map<String, String> getDatasetRefs() {
            return new HashMap<>(datasetRefs);
        }
    }
}
