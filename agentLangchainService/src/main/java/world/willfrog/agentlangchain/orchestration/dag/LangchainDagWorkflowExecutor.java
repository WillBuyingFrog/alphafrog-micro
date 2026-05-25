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

/**
 * DAG（Directed Acyclic Graph，有向无环图）工作流执行器。
 *
 * <p>负责将 {@link LangchainTodoPlan} 中的 todo items（任务项）按依赖关系构建成 DAG 图，
 * 并通过线程池并发调度无依赖冲突的节点，实现并行执行。与线性执行器（{@link world.willfrog.agentlangchain.orchestration.LangchainLinearWorkflowExecutor}）
 * 的串行执行不同，DAG 执行器可以充分利用 todo 之间的并行性，显著缩短整体执行时间。</p>
 *
 * <p>核心执行流程：</p>
 * <ol>
 *   <li>将 todo items 构建为 {@link LangchainDagExecutionGraph}，并检测是否存在环（circular dependency）；</li>
 *   <li>为每个 todo 节点创建 {@link CompletableFuture} 依赖链，前置依赖完成后才调度执行；</li>
 *   <li>通过 {@link ExecutorService} 线程池并发执行就绪节点；</li>
 *   <li>支持失败传播：若某节点失败，其所有下游节点自动标记为 SKIPPED（跳过）；</li>
 *   <li>所有节点完成后，调用 {@link LangchainTodoNodeExecutor#writeFinalAnswer} 生成最终答案。</li>
 * </ol>
 *
 * <p>线程安全设计：</p>
 * <ul>
 *   <li>{@link LangchainDagSharedContext} 使用 {@link java.util.concurrent.CopyOnWriteArrayList}
 *       和 {@link ConcurrentHashMap} 保证 completed todos 和 dataset refs（数据集引用）的线程安全共享；</li>
 *   <li>{@link LangchainDagStateRecorder} 通过 synchronized 锁将节点状态持久化到 Redis，供前端实时查询进度；</li>
 *   <li>每个工作线程通过 {@link AgentContext.ContextSnapshot} 恢复父线程的运行上下文，确保 observability（可观测性）trace 不丢失。</li>
 * </ul>
 *
 * <p>这个类本身不直接与 LLM 对话，也不直接调用工具；它只负责 DAG 的拓扑调度、
 * 并发控制和失败传播。具体的单个 todo 执行逻辑（LLM 对话、tool-calling 工具调用循环）
 * 委托给 {@link LangchainTodoNodeExecutor}。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LangchainDagWorkflowExecutor {

    /**
     * DAG 执行阶段的 phase 标识，用于 AgentContext 和 observability trace 标记。
     * 每个节点会在此前缀后附加自己的 todoId，如 "dag_execution_todo_3"。
     */
    private static final String PHASE_DAG_EXECUTION = "dag_execution";

    private final LangchainTodoNodeExecutor todoNodeExecutor;
    private final LangchainDagStateRecorder stateRecorder;
    private final AgentEventService eventService;
    private final LangchainRunExecutionGuard executionGuard;

    /**
     * DAG 执行线程池大小，默认 4。线程数受限于两个因素：
     * 1) 配置上限（避免并发 LLM 调用过多导致 rate limit，速率限制）；
     * 2) 实际 todo 数量（避免创建过多空闲线程）。
     */
    @Value("${agent.langchain.dag.thread-pool-size:4}")
    private int dagThreadPoolSize;

    /**
     * DAG 工作流的主入口。由 {@link world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipelineImpl}
     * 在判断应使用 DAG 模式后调用。
     *
     * <p>整体执行流程：</p>
     * <ol>
     *   <li>校验 request 和 plan 的合法性；</li>
     *   <li>将 todo items 构建为 DAG 图并检测环；</li>
     *   <li>发送 DAG_EXECUTION_STARTED 事件；</li>
     *   <li>调用 {@link #executeDagParallel} 并发执行所有节点（核心调度逻辑）；</li>
     *   <li>检查是否被用户取消（cancel/pause）；</li>
     *   <li>遍历所有节点结果：若存在失败或中断，返回对应状态；</li>
     *   <li>所有节点成功 → 设置 phase 为 summarizing，调用 writeFinalAnswer 生成最终答案。</li>
     * </ol>
     *
     * @param request 工作流请求，包含 runId、userId、用户目标、各阶段 ChatModel 等
     * @param plan    planner 生成的 todo 计划，包含 items 及其依赖关系
     * @return 工作流执行结果，成功时包含 finalAnswer（最终答案），失败时包含 failureReason
     */
    public LangchainLinearWorkflowResult executePlanned(LangchainLinearWorkflowRequest request,
                                                        LangchainTodoPlan plan) {
        validate(request, plan);
        AtomicInteger toolCalls = new AtomicInteger();
        try {
            applyRunContext(request);
            List<TodoItem> items = plan.getItems() == null ? List.of() : plan.getItems();
            // 从 todo items 构建 DAG 图：解析每个 item 的 dependsOn（依赖列表）
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

            // sharedContext 是所有工作线程共享的上下文：已完成 todo 的结果和数据集引用
            LangchainDagSharedContext sharedContext = new LangchainDagSharedContext();
            DagParallelRun parallelRun = executeDagParallel(graph, items, request, sharedContext, toolCalls);

            // 检查执行完成后是否被用户取消（cancel/pause 可能发生在节点执行期间）
            List<LangchainCompletedTodo> completedTodos = new ArrayList<>(sharedContext.completedTodosSnapshot());
            Optional<String> stopBeforeAnswer = executionGuard.stopReason(runId, userId);
            if (stopBeforeAnswer.isPresent()) {
                return interrupted(plan, completedTodos, stopBeforeAnswer.get(), toolCalls.get());
            }

            // 遍历所有节点结果，检查是否有失败或中断
            for (TodoItem item : items) {
                LangchainTodoNodeResult nodeResult = parallelRun.results().get(item.getId());
                // 节点被 RUN_INTERRUPTED 标记（通常是用户取消触发）
                if (nodeResult != null && nodeResult.getSummary() != null
                        && nodeResult.getSummary().startsWith("RUN_INTERRUPTED:")) {
                    String controlStatus = nodeResult.getSummary().substring("RUN_INTERRUPTED:".length());
                    return interrupted(plan, completedTodos, controlStatus, toolCalls.get());
                }
                // 节点执行失败或未执行（null 通常意味着调度异常）
                if (nodeResult == null || !nodeResult.isSuccess()) {
                    String reason = nodeResult == null ? "No result" : nvl(nodeResult.getSummary());
                    if (!isBlank(runId) && !isBlank(userId)) {
                        appendDagCompleted(runId, userId, false, reason, toolCalls.get());
                    }
                    return failure(plan, completedTodos, reason, toolCalls.get());
                }
            }

            // 所有节点成功完成，进入 summarizing（总结）阶段生成最终答案
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
            // 清理 ThreadLocal（线程本地变量），避免线程池复用下一个 run 时上下文串扰
            AgentContext.clear();
        }
    }

    /**
     * 并发执行 DAG 所有节点的核心调度方法。
     *
     * <p>调度机制：</p>
     * <ol>
     *   <li>为每个 todo item 创建一个 {@link CompletableFuture}，通过 {@link #scheduleNode} 递归构建依赖链；</li>
     *   <li>每个节点的 CompletableFuture 会先等待其所有前置依赖（dependencies）完成后才触发执行；</li>
     *   <li>就绪的节点被提交到 {@link ExecutorService} 线程池异步执行；</li>
     *   <li>所有节点的 CompletableFuture 通过 {@code CompletableFuture.allOf(...).get(30, TimeUnit.MINUTES)}
     *       等待完成，总超时 30 分钟。</li>
     * </ol>
     *
     * <p>线程上下文传递：父线程的 {@link AgentContext.ContextSnapshot} 被捕获，
     * 每个工作线程在执行节点前恢复该快照，确保 runId/userId/phase 等上下文在 observability trace 中正确关联。</p>
     *
     * @param graph         DAG 执行图，包含节点映射和依赖关系
     * @param items         所有 todo items
     * @param request       工作流请求
     * @param sharedContext 线程安全的共享上下文，用于传递 completed todos 和 dataset refs
     * @param toolCalls     原子计数器，统计整个 DAG 执行过程中的总 tool call 次数
     * @return 包含所有节点执行结果的 DagParallelRun
     * @throws Exception 当总超时或其他调度异常时抛出
     */
    private DagParallelRun executeDagParallel(LangchainDagExecutionGraph graph,
                                              List<TodoItem> items,
                                              LangchainLinearWorkflowRequest request,
                                              LangchainDagSharedContext sharedContext,
                                              AtomicInteger toolCalls) throws Exception {
        String runId = request.getRunId();
        String userId = request.getUserId();
        // 节点执行结果，ConcurrentHashMap 保证多线程安全
        Map<String, LangchainTodoNodeResult> results = new ConcurrentHashMap<>();
        // 节点成功状态，用于失败传播判断
        Map<String, Boolean> nodeSuccess = new ConcurrentHashMap<>();
        // 状态锁，保护 nodeStates（节点状态映射）在持久化时的线程安全
        Object workflowStateLock = new Object();
        Map<String, TodoItem> nodeStates = new LinkedHashMap<>();
        // 捕获父线程的 AgentContext 快照，后续子线程恢复
        AgentContext.ContextSnapshot parentContext = AgentContext.captureRunContext();

        // 线程池大小取配置值和 todo 数量的较小值，避免资源浪费
        int poolSize = Math.max(1, Math.min(dagThreadPoolSize, items.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        AtomicInteger completedCount = new AtomicInteger();
        try {
            // 为每个 item 调度一个 CompletableFuture，内部递归处理依赖
            Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();
            for (TodoItem item : items) {
                scheduleNode(graph, items, item, request, sharedContext, toolCalls, results, nodeSuccess,
                        workflowStateLock, nodeStates, parentContext, executor, completedCount, futures);
            }
            // 等待所有节点完成，30 分钟总超时
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.MINUTES);
            return new DagParallelRun(results);
        } catch (TimeoutException e) {
            throw new RuntimeException("DAG execution failed: timeout", e);
        } finally {
            // 强制关闭线程池，中断正在执行的任务
            executor.shutdownNow();
        }
    }

    /**
     * 递归调度单个 DAG 节点的执行。
     *
     * <p>关键设计：使用 {@link Map#computeIfAbsent} 保证每个节点只被调度一次，
     * 即使多个下游节点都依赖它。这是 DAG 调度中的经典去重模式。</p>
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>先递归调度该节点的所有前置依赖（dependencies）；</li>
     *   <li>通过 {@code CompletableFuture.allOf(dependencyFutures)} 等待所有依赖完成；</li>
     *   <li>依赖全部完成后，通过 {@code thenRunAsync} 将该节点的实际执行逻辑提交到线程池。</li>
     * </ol>
     *
     * <p>这种方式天然支持并行：没有依赖关系的节点会同时被提交到线程池执行；
     * 有依赖关系的节点会在前置节点完成后自动触发。</p>
     *
     * @return 该节点的 CompletableFuture，下游节点可依赖此 Future
     */
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
            // 获取该节点的所有前置依赖的 CompletableFuture
            CompletableFuture<?>[] dependencyFutures = graph.getDependencies(item.getId())
                    .stream()
                    .map(depId -> graph.getItemMap().get(depId))
                    .filter(dep -> dep != null)
                    .map(dep -> scheduleNode(graph, items, dep, request, sharedContext, toolCalls, results,
                            nodeSuccess, workflowStateLock, nodeStates, parentContext, executor, completedCount,
                            futures))
                    .toArray(CompletableFuture[]::new);
            // 所有依赖完成后，异步执行当前节点
            return CompletableFuture.allOf(dependencyFutures)
                    .thenRunAsync(() -> executeNode(graph, items, item, request, sharedContext, toolCalls,
                            results, nodeSuccess, workflowStateLock, nodeStates, parentContext, completedCount),
                            executor);
        });
    }

    /**
     * 实际执行单个 DAG 节点的逻辑。在工作线程中被调用。
     *
     * <p>执行流程（按顺序）：</p>
     * <ol>
     *   <li><b>取消检查</b>：若用户已发送 cancel/pause，节点直接标记为 RUN_INTERRUPTED；</li>
     *   <li><b>上下文恢复</b>：恢复父线程的 AgentContext，确保 observability trace 关联正确；</li>
     *   <li><b>依赖失败检查</b>：若任一前置依赖未成功（nodeSuccess 不为 true），
     *       当前节点标记为 SKIPPED（跳过），并发送 DAG_NODE_SKIPPED 事件；</li>
     *   <li><b>状态持久化</b>：将节点状态标记为 RUNNING，写入 Redis；</li>
     *   <li><b>执行 todo</b>：调用 {@link LangchainTodoNodeExecutor#execute} 进行实际的 LLM 对话和 tool-calling；</li>
     *   <li><b>结果处理</b>：成功 → merge 结果到 sharedContext，标记 COMPLETED；
     *       失败 → 标记 FAILED，发送 DAG_NODE_FAILED 事件。</li>
     * </ol>
     *
     * <p>工具调用计数器 {@code toolCalls} 是原子变量，整个 DAG 的所有节点共享同一个计数器，
     * 累加各自执行过程中的 tool call 次数。</p>
     *
     * <p>Dataset refs（数据集引用）通过 {@code localRefs} 快照机制传递：
     * 每个节点执行前复制当前共享的 dataset refs，执行后将新的 refs merge 回 sharedContext。
     * 这样后续节点可以看到前面所有节点产生的数据集引用。</p>
     */
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
            // 1. 检查是否被用户取消（cancel/pause）
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
            // 2. 恢复父线程的 AgentContext，确保 observability trace 关联正确
            AgentContext.restoreRunContext(parentContext);
            // 3. 检查前置依赖是否失败，实现失败传播
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

            // 4. 设置当前节点的上下文和 phase
            AgentContext.setTodoContext(item.getId(), item.getSequence());
            AgentContext.setPhase(PHASE_DAG_EXECUTION + "_" + item.getId());
            // 5. 持久化 RUNNING 状态到 Redis
            stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                    item, TodoStatus.RUNNING, null, toolCalls.get());
            // 6. 复制当前共享的 dataset refs 快照，节点执行过程中产生的新的 refs 会 merge 回去
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
                // 成功：将结果加入共享上下文，后续依赖节点可以看到
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
                // 失败：状态记录和事件通知，下游节点会因 nodeSuccess=false 而被跳过
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
            // 捕获所有未处理异常和错误（Throwable 比 Exception 更宽，能捕获 Error 如 OOM），
            // 防止单个节点崩溃导致工作线程异常退出但 CompletableFuture 永不完成，进而使整个 DAG 挂死
            log.error("Failed to execute DAG node {}", item.getId(), t);
            LangchainTodoNodeResult failed = LangchainTodoNodeResult.failure(
                    "DAG execution failed: " + nvl(t.getMessage()));
            results.put(item.getId(), failed);
            nodeSuccess.put(item.getId(), false);
            stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                    item, TodoStatus.FAILED, failed, toolCalls.get());
        } finally {
            // 增加完成计数（用于监控和调试），并清理 ThreadLocal
            completedCount.incrementAndGet();
            AgentContext.clear();
        }
    }

    /**
     * 检查给定节点的所有前置依赖是否都已成功完成。
     *
     * <p>失败传播的核心方法：如果任一依赖的 nodeSuccess 不为 true，
     * 返回该失败依赖的 ID，当前节点将被标记为 SKIPPED。</p>
     *
     * @param dependencies 当前节点的依赖 ID 集合
     * @param nodeSuccess  全局节点成功状态映射
     * @return 第一个失败依赖的 ID，若全部成功则返回 null
     */
    private String findFailedDependency(Set<String> dependencies, Map<String, Boolean> nodeSuccess) {
        for (String depId : dependencies) {
            if (!Boolean.TRUE.equals(nodeSuccess.get(depId))) {
                return depId;
            }
        }
        return null;
    }

    /**
     * 发送 DAG 执行完成事件，无论成功或失败都会调用。
     *
     * @param success      是否所有节点都成功
     * @param failureReason 失败原因（成功时为 null）
     * @param toolCalls    总 tool call 次数
     */
    private void appendDagCompleted(String runId, String userId, boolean success, String failureReason, int toolCalls) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", success);
        payload.put("total_tool_calls_used", toolCalls);
        if (!success) {
            payload.put("failure_reason", nvl(failureReason));
        }
        eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", payload);
    }

    /**
     * 构建失败结果对象。
     */
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

    /**
     * 构建被中断结果对象（用户 cancel/pause）。
     */
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

    /**
     * 将 request 中的 runId/userId/webSearchEnabled 设置到 AgentContext。
     * 在 DAG 执行开始前调用，使 observability trace 能关联到正确的 run。
     */
    private void applyRunContext(LangchainLinearWorkflowRequest request) {
        if (!isBlank(request.getRunId())) {
            AgentContext.setRunId(request.getRunId());
        }
        if (!isBlank(request.getUserId())) {
            AgentContext.setUserId(request.getUserId());
        }
        AgentContext.setWebSearchEnabled(Boolean.TRUE.equals(request.getWebSearchEnabled()));
    }

    /**
     * 校验 request 和 plan 的合法性。
     *
     * <p>必须满足：request 非空、plan 非空且有 items、execution model（执行阶段模型）已配置、
     * user goal（用户目标）非空。任何一项不满足都抛出 IllegalArgumentException，
     * 由上层 {@link #executePlanned} 的 catch 块捕获并包装为失败结果。</p>
     */
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

    /**
     * DAG 并行执行的内部结果包装，持有所有节点的执行结果映射。
     */
    private record DagParallelRun(Map<String, LangchainTodoNodeResult> results) {
    }
}
