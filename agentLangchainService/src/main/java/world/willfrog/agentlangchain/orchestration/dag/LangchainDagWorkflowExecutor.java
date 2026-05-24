package world.willfrog.agentlangchain.orchestration.dag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.workflow.TodoStatus;
import world.willfrog.agentlangchain.orchestration.LangchainCompletedTodo;
import world.willfrog.agentlangchain.orchestration.LangchainLinearWorkflowRequest;
import world.willfrog.agentlangchain.orchestration.LangchainLinearWorkflowResult;
import world.willfrog.agentlangchain.orchestration.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class LangchainDagWorkflowExecutor {

    private static final String PHASE_DAG_EXECUTION = "dag_execution";

    private final LangchainTodoNodeExecutor todoNodeExecutor;
    private final LangchainDagStateRecorder stateRecorder;
    private final AgentEventService eventService;
    private final LangchainRunExecutionGuard executionGuard;

    @Value("${agent.langchain.dag.thread-pool-size:4}")
    private int dagThreadPoolSize;

    public LangchainLinearWorkflowResult executePlanned(LangchainLinearWorkflowRequest request,
                                                        LangchainTodoPlan plan) {
        validate(request, plan);
        AtomicInteger toolCalls = new AtomicInteger();
        try {
            applyRunContext(request);
            List<TodoItem> items = plan.getItems() == null ? List.of() : plan.getItems();
            LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(items);
            if (graph.hasCycle()) {
                return failure(plan, List.of(), "dag_circular_dependency", toolCalls.get());
            }

            String runId = request.getRunId();
            String userId = request.getUserId();
            if (!isBlank(runId) && !isBlank(userId)) {
                eventService.append(runId, userId, "DAG_EXECUTION_STARTED", Map.of(
                        "run_id", runId,
                        "node_count", items.size()
                ));
            }

            LangchainDagSharedContext sharedContext = new LangchainDagSharedContext();
            DagParallelRun parallelRun = executeDagParallel(graph, items, request, sharedContext, toolCalls);

            List<LangchainCompletedTodo> completedTodos = new ArrayList<>(sharedContext.completedTodosSnapshot());
            Optional<String> stopBeforeAnswer = executionGuard.stopReason(runId, userId);
            if (stopBeforeAnswer.isPresent()) {
                return interrupted(plan, completedTodos, stopBeforeAnswer.get(), toolCalls.get());
            }

            for (TodoItem item : items) {
                LangchainTodoNodeResult nodeResult = parallelRun.results().get(item.getId());
                if (nodeResult != null && nodeResult.getSummary() != null
                        && nodeResult.getSummary().startsWith("RUN_INTERRUPTED:")) {
                    String controlStatus = nodeResult.getSummary().substring("RUN_INTERRUPTED:".length());
                    return interrupted(plan, completedTodos, controlStatus, toolCalls.get());
                }
                if (nodeResult == null || !nodeResult.isSuccess()) {
                    String reason = nodeResult == null ? "No result" : nvl(nodeResult.getSummary());
                    if (!isBlank(runId) && !isBlank(userId)) {
                        appendDagCompleted(runId, userId, false, reason, toolCalls.get());
                    }
                    return failure(plan, completedTodos, reason, toolCalls.get());
                }
            }

            AgentContext.setPhase("summarizing");
            AgentContext.setStage("final_answer");
            String finalAnswer = todoNodeExecutor.writeFinalAnswer(request, completedTodos);
            if (isBlank(finalAnswer)) {
                return failure(plan, completedTodos, "empty_final_answer", toolCalls.get());
            }
            if (!isBlank(runId) && !isBlank(userId)) {
                appendDagCompleted(runId, userId, true, null, toolCalls.get());
            }
            return LangchainLinearWorkflowResult.builder()
                    .success(true)
                    .finalAnswer(finalAnswer.trim())
                    .plan(plan)
                    .completedTodos(completedTodos)
                    .toolCallsUsed(toolCalls.get())
                    .build();
        } catch (Exception e) {
            log.error("LangChain DAG workflow failed", e);
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

    private DagParallelRun executeDagParallel(LangchainDagExecutionGraph graph,
                                              List<TodoItem> items,
                                              LangchainLinearWorkflowRequest request,
                                              LangchainDagSharedContext sharedContext,
                                              AtomicInteger toolCalls) throws Exception {
        String runId = request.getRunId();
        String userId = request.getUserId();
        Map<String, LangchainTodoNodeResult> results = new ConcurrentHashMap<>();
        Map<String, Boolean> nodeSuccess = new ConcurrentHashMap<>();
        Object workflowStateLock = new Object();
        Map<String, TodoItem> nodeStates = new LinkedHashMap<>();
        AgentContext.ContextSnapshot parentContext = AgentContext.captureRunContext();

        int poolSize = Math.max(1, Math.min(dagThreadPoolSize, items.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        AtomicInteger completedCount = new AtomicInteger();
        try {
            Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();
            for (TodoItem item : items) {
                scheduleNode(graph, items, item, request, sharedContext, toolCalls, results, nodeSuccess,
                        workflowStateLock, nodeStates, parentContext, executor, completedCount, futures);
            }
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.MINUTES);
            return new DagParallelRun(results);
        } catch (TimeoutException e) {
            throw new RuntimeException("DAG execution failed: timeout", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private CompletableFuture<Void> scheduleNode(LangchainDagExecutionGraph graph,
                                                 List<TodoItem> items,
                                                 TodoItem item,
                                                 LangchainLinearWorkflowRequest request,
                                                 LangchainDagSharedContext sharedContext,
                                                 AtomicInteger toolCalls,
                                                 Map<String, LangchainTodoNodeResult> results,
                                                 Map<String, Boolean> nodeSuccess,
                                                 Object workflowStateLock,
                                                 Map<String, TodoItem> nodeStates,
                                                 AgentContext.ContextSnapshot parentContext,
                                                 ExecutorService executor,
                                                 AtomicInteger completedCount,
                                                 Map<String, CompletableFuture<Void>> futures) {
        return futures.computeIfAbsent(item.getId(), ignored -> {
            CompletableFuture<?>[] dependencyFutures = graph.getDependencies(item.getId())
                    .stream()
                    .map(depId -> graph.getItemMap().get(depId))
                    .filter(dep -> dep != null)
                    .map(dep -> scheduleNode(graph, items, dep, request, sharedContext, toolCalls, results,
                            nodeSuccess, workflowStateLock, nodeStates, parentContext, executor, completedCount,
                            futures))
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(dependencyFutures)
                    .thenRunAsync(() -> executeNode(graph, items, item, request, sharedContext, toolCalls,
                            results, nodeSuccess, workflowStateLock, nodeStates, parentContext, completedCount),
                            executor);
        });
    }

    private void executeNode(LangchainDagExecutionGraph graph,
                             List<TodoItem> items,
                             TodoItem item,
                             LangchainLinearWorkflowRequest request,
                             LangchainDagSharedContext sharedContext,
                             AtomicInteger toolCalls,
                             Map<String, LangchainTodoNodeResult> results,
                             Map<String, Boolean> nodeSuccess,
                             Object workflowStateLock,
                             Map<String, TodoItem> nodeStates,
                             AgentContext.ContextSnapshot parentContext,
                             AtomicInteger completedCount) {
        String runId = request.getRunId();
        String userId = request.getUserId();
        try {
            Optional<String> stop = executionGuard.stopReason(runId, userId);
            if (stop.isPresent()) {
                LangchainTodoNodeResult interrupted = LangchainTodoNodeResult.builder()
                        .success(false)
                        .summary("RUN_INTERRUPTED:" + stop.get())
                        .build();
                results.put(item.getId(), interrupted);
                nodeSuccess.put(item.getId(), false);
                return;
            }
            AgentContext.restoreRunContext(parentContext);
            String failedDependency = findFailedDependency(graph.getDependencies(item.getId()), nodeSuccess);
            if (failedDependency != null) {
                LangchainTodoNodeResult skipped = LangchainTodoNodeResult.skipped(failedDependency);
                results.put(item.getId(), skipped);
                nodeSuccess.put(item.getId(), false);
                stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                        item, TodoStatus.SKIPPED, skipped, toolCalls.get());
                if (!isBlank(runId) && !isBlank(userId)) {
                    eventService.append(runId, userId, "DAG_NODE_SKIPPED", Map.of(
                            "todo_id", item.getId(),
                            "failed_dependency", failedDependency
                    ));
                }
                return;
            }

            AgentContext.setTodoContext(item.getId(), item.getSequence());
            AgentContext.setPhase(PHASE_DAG_EXECUTION + "_" + item.getId());
            stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                    item, TodoStatus.RUNNING, null, toolCalls.get());
            Map<String, String> localRefs = new ConcurrentHashMap<>(sharedContext.datasetRefsSnapshot());
            LangchainTodoNodeResult record = todoNodeExecutor.execute(
                    request,
                    item,
                    sharedContext.completedTodosSnapshot(),
                    localRefs,
                    toolCalls);
            sharedContext.mergeDatasetRefs(localRefs);
            results.put(item.getId(), record);
            nodeSuccess.put(item.getId(), record.isSuccess());
            if (record.isSuccess()) {
                sharedContext.addCompletedTodo(LangchainCompletedTodo.builder()
                        .todoId(item.getId())
                        .sequence(item.getSequence())
                        .description(item.getDescription())
                        .output(record.getOutput())
                        .summary(record.getSummary())
                        .build());
                stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                        item, TodoStatus.COMPLETED, record, toolCalls.get());
                if (!isBlank(runId) && !isBlank(userId)) {
                    eventService.append(runId, userId, "DAG_NODE_COMPLETED", Map.of(
                            "todo_id", item.getId(),
                            "tool_calls_used", record.getToolCallsUsed()
                    ));
                }
            } else {
                stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                        item, TodoStatus.FAILED, record, toolCalls.get());
                if (!isBlank(runId) && !isBlank(userId)) {
                    eventService.append(runId, userId, "DAG_NODE_FAILED", Map.of(
                            "todo_id", item.getId(),
                            "summary", nvl(record.getSummary())
                    ));
                }
            }
        } catch (Throwable t) {
            log.error("Failed to execute DAG node {}", item.getId(), t);
            LangchainTodoNodeResult failed = LangchainTodoNodeResult.failure(
                    "DAG execution failed: " + nvl(t.getMessage()));
            results.put(item.getId(), failed);
            nodeSuccess.put(item.getId(), false);
            stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                    item, TodoStatus.FAILED, failed, toolCalls.get());
        } finally {
            completedCount.incrementAndGet();
            AgentContext.clear();
        }
    }

    private String findFailedDependency(Set<String> dependencies, Map<String, Boolean> nodeSuccess) {
        for (String depId : dependencies) {
            if (!Boolean.TRUE.equals(nodeSuccess.get(depId))) {
                return depId;
            }
        }
        return null;
    }

    private void appendDagCompleted(String runId, String userId, boolean success, String failureReason, int toolCalls) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", success);
        payload.put("total_tool_calls_used", toolCalls);
        if (!success) {
            payload.put("failure_reason", nvl(failureReason));
        }
        eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", payload);
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

    private void validate(LangchainLinearWorkflowRequest request, LangchainTodoPlan plan) {
        if (request == null) {
            throw new IllegalArgumentException("dag_workflow_request_required");
        }
        if (plan == null || plan.getItems() == null || plan.getItems().isEmpty()) {
            throw new IllegalArgumentException("dag_workflow_plan_required");
        }
        if (request.executionModelOrDefault() == null) {
            throw new IllegalArgumentException("dag_workflow_chat_model_required");
        }
        if (isBlank(request.getUserGoal())) {
            throw new IllegalArgumentException("dag_workflow_user_goal_required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private record DagParallelRun(Map<String, LangchainTodoNodeResult> results) {
    }
}
