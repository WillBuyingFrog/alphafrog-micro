package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentCitationService;
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
 * DAG 并行工作流执行器（ReAct 模式）。
 *
 * <h3>在整体架构中的位置</h3>
 * 本执行器是 {@link WorkflowExecutor} 接口的两种实现之一（另一个是 {@link LinearWorkflowExecutor}）。
 * 由 {@link world.willfrog.agent.service.AgentRunExecutor} 在 Plan 生成后通过
 * {@link WorkflowExecutorFactory} 选择本执行器或线性执行器；通常仅当 Plan 中存在显式 dependsOn
 * 关系且 executionMode 为 DAG / AUTO（具备可并行结构）时本执行器才会被选中。
 *
 * <h3>核心执行流程（{@link #execute}）</h3>
 * <ol>
 *   <li><b>构建依赖图</b>：将 Plan 的 TodoItem 列表转为 {@link ExecutionGraph}，
 *       同时检测有无环依赖（{@link ExecutionGraph#hasCycle}）。</li>
 *   <li><b>并行执行节点</b>：通过 {@link CountDownLatch} 维护依赖闭锁，
 *       使用 FixedThreadPool 调度可执行的节点。每个节点内部依旧调用 {@link ReactTodoExecutor}
 *       执行 ReAct 循环。</li>
 *   <li><b>跨线程上下文还原</b>：在 worker 线程中调用 {@link AgentContext#restoreRunContext}
 *       恢复父线程的 AgentContext（包括 phase/stage/runId 等 ThreadLocal 值）。</li>
 *   <li><b>状态持久化</b>：每个节点状态变化时通过 {@link AgentRunStateStore#saveWorkflowState}
 *       写入 Redis，便于前端实时展示 DAG 节点进度。</li>
 *   <li><b>失败恢复</b>（仅当 dagPlanPatchEnabled=true 时启用）：
 *     <ul>
 *       <li>调用 {@link PlanJudge} 让 LLM 对失败做出判断。</li>
 *       <li>RETRY/PATCH_PLAN：在 maxPatchRounds 上限内重新构建 Plan 后再次执行整张图。</li>
 *       <li>FALLBACK_TO_LINEAR：将剩余未完成的 Todo 改为线性顺序执行
 *           （{@link #fallbackToLinear}），适用于复杂依赖让 DAG 难以恢复的场景。</li>
 *       <li>FAIL/ABORT：立即终止执行。</li>
 *     </ul>
 *   </li>
 *   <li><b>生成最终回答</b>：所有节点完成后，组装上下文 + 引用表，调用 LLM 生成 Markdown 答案。</li>
 * </ol>
 *
 * <h3>并发控制</h3>
 * <ul>
 *   <li>节点结果存储 {@link ConcurrentHashMap}，统计计数器使用 {@link AtomicInteger}。</li>
 *   <li>每节点闭锁数 = 该节点的依赖数；上游节点完成后调用 {@code countDown()} 释放下游。</li>
 *   <li>WorkflowState 的持久化通过外部 lock 串行化，避免多个 worker 并发写脏数据。</li>
 *   <li>所有 worker 完成由一个 {@link CompletableFuture#complete} 总闭锁触发，整体超时 30 分钟。</li>
 * </ul>
 *
 * @see LinearWorkflowExecutor
 * @see ReactTodoExecutor
 * @see PlanJudge
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DagWorkflowExecutor implements WorkflowExecutor {

    /** 事件流服务，用于发送 DAG_* 系列事件，便于前端实时呈现 */
    private final AgentEventService eventService;
    /** 单个节点的 ReAct 执行器，与 LinearWorkflowExecutor 共用 */
    private final ReactTodoExecutor reactTodoExecutor;
    /** 观测数据服务（保留以便扩展，目前主要由 reactTodoExecutor 内部使用） */
    private final AgentObservabilityService observabilityService;
    /** 提示词服务，提供 dagReactSystemPrompt、finalAnswerStageInstruction 等 */
    private final AgentPromptService promptService;
    /** Plan 质量判断器，对失败节点输出决策（RETRY/PATCH/FALLBACK/FAIL 等） */
    private final PlanJudge planJudge;
    /** Patch 计划生成器（PATCH_PLAN 决策时生成新的 PlanPatch） */
    private final PatchPlanner patchPlanner;
    /** Patch 应用器，将 PlanPatch 应用到 TodoPlan */
    private final PlanPatcher planPatcher;
    /** Run 级 Redis 状态缓存：检查 run 是否被取消、持久化 WorkflowState */
    private final AgentRunStateStore stateStore;
    /** 本地 agent-llm 配置热加载器（用于动态读取线程池规模等参数） */
    private final AgentLlmLocalConfigLoader localConfigLoader;
    /** 静态 agent-llm 配置 bean */
    private final AgentLlmProperties llmProperties;
    /** JSON 工具，用于解析节点 output 中的 dataset_id 等结构化字段 */
    private final ObjectMapper objectMapper;
    /** 引用来源服务，从已完成任务中构建引用编号表 */
    private final AgentCitationService citationService;
    /** 轻量失败分类器，避免所有失败都进入 LLM Judge。 */
    private final WorkflowFailureClassifier failureClassifier;

    /** 观测阶段前缀：每个节点会再追加 _todoId 形成完整 phase 名 */
    private static final String PHASE_DAG_EXECUTION = "dag_execution";

    /** DAG 并行线程池规模（默认 4，可被热加载配置或 application.yml 覆盖） */
    @Value("${agent.flow.dag.thread-pool-size:4}")
    private int defaultDagThreadPoolSize;

    /** 是否启用 DAG 模式下的 Plan Patch 失败恢复机制 */
    @Value("${agent.flow.dag.plan-patch.enabled:true}")
    private boolean dagPlanPatchEnabled;

    /** 单个失败节点最多被 patch/retry 的次数（默认 2） */
    @Value("${agent.flow.dag.plan-patch.max-retries-per-node:2}")
    private int maxRetriesPerNode;

    /** 整个 Run 的 DAG 重执行总轮数上限（默认 4） */
    @Value("${agent.flow.dag.plan-patch.max-rounds:4}")
    private int maxPatchRounds;

    /**
     * 执行 DAG 工作流。
     *
     * <p>外层是 "整张 DAG 执行 → 失败诊断 → patch/retry → 再次执行" 的循环；
     * 内层 {@link #runSingleDag} 才是单次完整的并行执行。</p>
     *
     * @param request 工作流请求（含 run、plan、ChatModel、ToolSpecification 等）
     * @return 工作流执行结果（含成功标志、最终答案、引用表、工具调用统计）
     */
    @Override
    public WorkflowExecutionResult execute(WorkflowRequest request) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        TodoPlan originalPlan = request.getPlan();
        List<TodoItem> originalItems = originalPlan.getItems();

        // 空 Plan 短路：没有节点时视为已完成
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

        // 每个失败节点的累计重试/patch 次数，用于配额控制
        Map<String, Integer> retryByFailedNode = new HashMap<>();
        int patchRound = 0;
        // workingPlan 在 patch 过程中会被替换，必须先深拷贝避免污染调用方传入的 Plan
        TodoPlan workingPlan = clonePlan(originalPlan);
        DagRunOutcome lastOutcome = null;

        // ── 外层 patch 循环：单次 DAG 执行 + 失败诊断 + 决策 ──
        while (patchRound <= maxPatchRounds) {
            DagRunOutcome outcome = runSingleDag(workingPlan, request);
            lastOutcome = outcome;
            // 成功：生成最终回答并返回
            if (outcome.success()) {
                FinalAnswerResult finalAnswer = generateFinalAnswer(outcome.sharedContext(), request);
                recordDagCompletion(runId, userId, true, startedAt, null, outcome.totalToolCallsUsed());
                return WorkflowExecutionResult.builder()
                        .success(true)
                        .finalAnswer(finalAnswer.answer())
                        .citationMap(finalAnswer.citationMap())
                        .toolCallsUsed(outcome.totalToolCallsUsed())
                        .build();
            }

            // Plan Patch 未启用或没有定位到失败节点：直接失败返回
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

            // 调用 PlanJudge 让 LLM 对失败原因做出诊断
            String failedNodeId = outcome.failedItem().getId();
            int usedRetries = retryByFailedNode.getOrDefault(failedNodeId, 0);
            TodoExecutionRecord failedRecord = toLegacyRecord(outcome.failedRecord());
            WorkflowFailureClassifier.FailureClassification classification = failureClassifier.classify(outcome.failedRecord());
            eventService.append(runId, userId, "FAILURE_CLASSIFIED", Map.of(
                    "todoId", failedNodeId,
                    "category", classification.category().name(),
                    "action", classification.action().name(),
                    "errorCode", nvl(classification.errorCode())
            ));
            if (classification.action() == WorkflowFailureClassifier.RecoveryAction.FAIL_FAST) {
                String reason = nvl(outcome.failureReason());
                recordDagCompletion(runId, userId, false, startedAt, reason, outcome.totalToolCallsUsed());
                return WorkflowExecutionResult.builder()
                        .success(false)
                        .failureReason(reason)
                        .toolCallsUsed(outcome.totalToolCallsUsed())
                        .build();
            }
            if (classification.action() == WorkflowFailureClassifier.RecoveryAction.RETRY_CURRENT) {
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
                        "max_retries", maxRetriesPerNode,
                        "source", classification.category().name()
                ));
                continue;
            }
            JudgeDecision decision = planJudge.judge(
                    failedRecord,
                    workingPlan,
                    outcome.judgeContext(),
                    request.getUserGoal(),
                    request.getModel()
            );

            // 决策：回退到线性执行（适用于 DAG 依赖复杂、难以 patch 的情况）
            if (decision == JudgeDecision.FALLBACK_TO_LINEAR) {
                FallbackResult fallbackResult = fallbackToLinear(workingPlan, outcome.sharedContext(), request);
                int totalToolCalls = outcome.totalToolCallsUsed() + fallbackResult.toolCallsUsed();
                if (fallbackResult.success()) {
                    FinalAnswerResult finalAnswer = generateFinalAnswer(outcome.sharedContext(), request);
                    recordDagCompletion(runId, userId, true, startedAt, null, totalToolCalls);
                    return WorkflowExecutionResult.builder()
                            .success(true)
                            .finalAnswer(finalAnswer.answer())
                            .citationMap(finalAnswer.citationMap())
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

            // 决策：让 LLM 生成 PlanPatch 修改 Plan 结构后重新执行
            if (decision == JudgeDecision.PATCH_PLAN) {
                // 配额检查：单节点 patch 次数 / 整体 patch 轮数
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
                // DAG 模式目前只支持 REPLACE 类型的 patch（替换失败节点）
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
                // 应用 patch，进入下一轮 DAG 执行
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

            // 决策：原地重试当前失败节点（不修改 Plan 结构，直接重跑整张 DAG）
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

            // 其他决策（FAIL/ABORT 或未识别）：直接终止
            String reason = outcome.failureReason();
            recordDagCompletion(runId, userId, false, startedAt, reason, outcome.totalToolCallsUsed());
            return WorkflowExecutionResult.builder()
                    .success(false)
                    .failureReason(reason)
                    .toolCallsUsed(outcome.totalToolCallsUsed())
                    .build();
        }

        // 达到 maxPatchRounds 上限：返回最后一次 outcome 的失败原因
        String reason = lastOutcome == null ? "dag_execution_failed" : lastOutcome.failureReason();
        recordDagCompletion(runId, userId, false, startedAt, reason, lastOutcome == null ? 0 : lastOutcome.totalToolCallsUsed());
        return WorkflowExecutionResult.builder()
                .success(false)
                .failureReason(reason)
                .toolCallsUsed(lastOutcome == null ? 0 : lastOutcome.totalToolCallsUsed())
                .build();
    }

    /**
     * 执行一次完整的 DAG 并行调度，作为外层 patch 循环的"单步"。
     *
     * <p>步骤：</p>
     * <ol>
     *   <li>构建依赖图，环依赖直接返回失败。</li>
     *   <li>创建本次执行的 {@link SharedExecutionContext}（线程安全，跨 worker 共享）。</li>
     *   <li>调度 {@link #executeDagParallel} 进行并行执行，收集每个节点的 record。</li>
     *   <li>按 Plan 顺序遍历 record：任一节点失败（或缺失结果）立即返回失败信息，
     *       并构造 judgeContext 供后续 PlanJudge 诊断。</li>
     *   <li>全部成功则返回 {@link DagRunOutcome#success}。</li>
     * </ol>
     *
     * @param plan    本轮要执行的 Plan（可能是初始 Plan，也可能是 patch 后的新 Plan）
     * @param request 工作流请求
     * @return 本轮执行的聚合结果（成功或失败 + 上下文）
     */
    private DagRunOutcome runSingleDag(TodoPlan plan, WorkflowRequest request) {
        List<TodoItem> items = plan.getItems();
        ExecutionGraph graph = buildExecutionGraph(items);
        if (graph.hasCycle()) {
            return DagRunOutcome.failure("dag_circular_dependency", null, null, null, Map.of(), 0);
        }

        // 共享上下文：所有 worker 都会向其追加 completed todos 和 dataset refs
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
            // 并行执行整体异常（如超时）：直接失败
            String reason = "DAG execution failed: " + nvl(e.getMessage());
            return DagRunOutcome.failure(reason, null, null, sharedContext, Map.of(), 0);
        }

        // 按 Plan 顺序检查 record：发现第一个失败节点即返回（保留前序成功节点的上下文）
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

    /**
     * 真正的并行调度核心：用 CountDownLatch 表示依赖关系，FixedThreadPool 调度节点 worker。
     *
     * <h4>并发模型</h4>
     * <ol>
     *   <li>为每个节点创建一个 latch，其计数 = 依赖该节点的上游数量。</li>
     *   <li>所有 worker 同时 submit 到线程池：每个 worker 先 await 自己的 latch，
     *       然后执行（或跳过），最后给所有下游 latch 减 1。</li>
     *   <li>无依赖的根节点初始 latch=0，会立刻开始执行。</li>
     *   <li>所有 worker 完成后由 {@link CompletableFuture#complete} 触发整体返回。</li>
     * </ol>
     *
     * <h4>跨线程上下文</h4>
     * <ul>
     *   <li>父线程的 {@link AgentContext}（runId、userId、phase、stage 等 ThreadLocal）
     *       通过 {@link AgentContext#captureRunContext}/{@link AgentContext#restoreRunContext} 在 worker 起始处还原。</li>
     *   <li>worker 结束后必须调用 {@link AgentContext#clear} 清理，避免线程被复用时污染下一次任务。</li>
     * </ul>
     *
     * <h4>失败传播</h4>
     * worker 中若依赖节点失败，则当前节点直接被标记 SKIPPED 而不发起 LLM 调用，
     * 节省 token 并防止把错误传染到下游。
     *
     * @param graph         依赖图
     * @param sharedContext 跨线程共享的执行上下文
     * @param request       工作流请求
     * @return 并行结果（resultMap + 总工具调用次数）
     * @throws Exception    并行执行超时或线程池异常时抛出
     */
    private DagParallelResult executeDagParallel(ExecutionGraph graph,
                                                 SharedExecutionContext sharedContext,
                                                 WorkflowRequest request) throws Exception {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        List<TodoItem> items = request.getPlan().getItems();

        // 每节点一个 latch，countDown 由上游执行完毕时触发
        Map<String, CountDownLatch> nodeLatches = new ConcurrentHashMap<>();
        // 节点最终结果，由 worker 写入
        Map<String, ReactTodoExecutor.TodoExecutionRecord> results = new ConcurrentHashMap<>();
        // 节点成败标志，下游 worker 通过它判断是否要跳过
        Map<String, Boolean> nodeSuccess = new ConcurrentHashMap<>();
        AtomicInteger totalToolCallsUsed = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        // 所有 worker 完成后用于唤醒主线程
        CompletableFuture<Void> allDone = new CompletableFuture<>();
        // 状态持久化的串行化锁（多 worker 并发写同一个 WorkflowState）
        Object workflowStateLock = new Object();
        // 每个节点的最新状态副本，用于 saveWorkflowState
        Map<String, TodoItem> nodeStates = new LinkedHashMap<>();
        // 捕获父线程的 AgentContext，worker 启动时还原
        AgentContext.ContextSnapshot parentContext = AgentContext.captureRunContext();

        // 初始化每个节点的 latch：计数 = 依赖个数
        for (TodoItem item : items) {
            nodeLatches.put(item.getId(), new CountDownLatch(graph.getDependencies(item.getId()).size()));
        }

        int poolSize = resolveDagThreadPoolSize();
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            for (TodoItem item : items) {
                executor.submit(() -> {
                    try {
                        // 还原父线程的 AgentContext，使 worker 中的 ThreadLocal 与 run 一致
                        AgentContext.restoreRunContext(parentContext);
                        // 等待所有依赖完成
                        nodeLatches.get(item.getId()).await();
                        // 若任一上游失败，直接跳过当前节点（不发 LLM 调用）
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
                            // 设置当前节点的观测上下文（todo_id + phase）
                            AgentContext.setTodoContext(item.getId(), item.getSequence());
                            AgentContext.setPhase(PHASE_DAG_EXECUTION + "_" + item.getId());
                            // 写入"运行中"状态，便于前端实时呈现
                            persistDagNodeState(runId, items, workflowStateLock, nodeStates,
                                    item, TodoStatus.RUNNING, null, totalToolCallsUsed.get());
                            try {
                                // 调用 ReAct 执行器跑单节点（与 LinearWorkflowExecutor 完全相同的执行原语）
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
                                    // 成功节点的输出回写到共享上下文，供下游引用
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
                                // 清理节点级 ThreadLocal，避免线程复用时污染下一个节点
                                AgentContext.clearTodoContext();
                                AgentContext.clearPhase();
                            }
                        }
                    } catch (Throwable t) {
                        // worker 内任意异常都不能传播到线程池（否则下游 latch 永不释放）
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
                        // 清空整个 AgentContext（线程被线程池复用时不能有残留）
                        AgentContext.clear();
                        // 释放所有下游 latch（无论成功失败，下游都会进入 worker，看到 nodeSuccess 后再决定是否跳过）
                        for (String downstream : graph.getDependents(item.getId())) {
                            CountDownLatch latch = nodeLatches.get(downstream);
                            if (latch != null) {
                                latch.countDown();
                            }
                        }
                        // 所有节点 worker 完成后唤醒主线程
                        if (completedCount.incrementAndGet() == items.size()) {
                            allDone.complete(null);
                        }
                    }
                });
            }

            // 整体执行超时 30 分钟，超出则强制 shutdown
            allDone.get(30, TimeUnit.MINUTES);
            return new DagParallelResult(results, totalToolCallsUsed.get());
        } catch (TimeoutException e) {
            throw new RuntimeException("DAG execution failed: timeout", e);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 在锁保护下持久化单节点状态到 {@link AgentRunStateStore}。
     *
     * <p>每次某个节点状态变化时调用：</p>
     * <ol>
     *   <li>更新本节点的 TodoItem 副本（含 status / completedAt / resultSummary / output）。</li>
     *   <li>遍历整张 Plan，按当前状态划分为 completedItems / completedNodeIds / runningNodeIds。</li>
     *   <li>整体打包为 {@link WorkflowState} 后写入 stateStore（前端订阅此 state 渲染 DAG 节点）。</li>
     * </ol>
     *
     * <p>注意：所有节点 worker 都会并发调用本方法，因此 synchronized(lock) 必须串行化写入，
     * 否则会出现节点状态被覆盖、completedItems 抖动等问题。</p>
     *
     * @param runId              run ID（为空时跳过）
     * @param planItems          整张 Plan 的节点列表（用于遍历构建快照）
     * @param lock               串行化锁
     * @param nodeStates         节点最新状态副本表（mutable，由本方法更新）
     * @param item               当前节点
     * @param status             节点新状态
     * @param record             节点执行结果（可为 null，如 RUNNING 状态）
     * @param totalToolCallsUsed 截至当前的工具调用累计数
     */
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
            // 重新分类节点：根据当前所有节点的状态构建 completedItems / running / completed 集合
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

    /**
     * 复制一个 TodoItem，仅修改 status/completedAt/resultSummary/output 字段。
     *
     * <p>用于状态持久化时保留 Plan 中的不变字段（id、sequence、description、dependsOn 等），
     * 同时更新可变字段以反映最新执行情况。</p>
     *
     * @param item   原始 Plan 中的 TodoItem
     * @param status 新状态
     * @param record 执行结果（可为 null）
     * @return 状态更新后的副本
     */
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
                // 终态时才记录 completedAt，避免 RUNNING 中途产生误导性时间戳
                .completedAt(isTerminal(status) ? Instant.now() : null)
                .resultSummary(record == null ? null : nvl(record.getSummary()))
                .output(record == null ? null : nvl(record.getOutput()))
                .build();
    }

    /** 判断节点状态是否为终态（COMPLETED / FAILED / SKIPPED）。 */
    private boolean isTerminal(TodoStatus status) {
        return status == TodoStatus.COMPLETED || status == TodoStatus.FAILED || status == TodoStatus.SKIPPED;
    }

    /**
     * 在依赖集合中查找第一个失败（或缺失）的上游节点 ID。
     *
     * <p>用于 worker 在 latch 解锁后判断当前节点是否应该被跳过——只要任何一个上游失败，
     * 当前节点就不该执行（其结果不可信，且会浪费 LLM 资源）。</p>
     *
     * @param deps        当前节点的依赖 ID 集合
     * @param nodeSuccess 全图节点的成败标志
     * @return 首个失败/缺失的依赖 ID；全部成功时返回 null
     */
    private String findFailedDependency(Set<String> deps, Map<String, Boolean> nodeSuccess) {
        for (String depId : deps) {
            Boolean ok = nodeSuccess.get(depId);
            if (ok == null || !ok) {
                return depId;
            }
        }
        return null;
    }

    /**
     * 将剩余未完成的 Todo 切换为线性顺序执行（DAG → Linear 回退）。
     *
     * <p>触发时机：PlanJudge 判定 {@link JudgeDecision#FALLBACK_TO_LINEAR}，
     * 通常是 LLM 认为 DAG 依赖描述错误或并行结构不合理，串行执行成功概率更大。</p>
     *
     * <p>执行规则：</p>
     * <ol>
     *   <li>从 sharedContext 中提取已完成 Todo 的 ID 集合。</li>
     *   <li>过滤出剩余 Todo 并按 sequence 排序。</li>
     *   <li>逐个调用 {@link ReactTodoExecutor#executeWithObservability}（与线性执行器相同）。</li>
     *   <li>任一节点失败立即返回失败信息（不再继续尝试后续节点）。</li>
     * </ol>
     *
     * @param plan          当前 Plan
     * @param sharedContext 已有的共享执行上下文（含 DAG 阶段已完成的节点）
     * @param request       工作流请求
     * @return 回退执行结果（成功或失败 + 累计工具调用数）
     */
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
            // 把成功节点回写共享上下文，并提取其中的 dataset_id 供后续节点使用
            sharedContext.addCompletedTodo(item, record);
            extractDatasetIds(record, sharedContext);
        }
        return FallbackResult.success(toolCallsUsed);
    }

    /**
     * 从节点执行结果（JSON 字符串）中提取 dataset_id，注册到共享上下文。
     *
     * <p>支持的格式（与 Linear 执行器保持一致，但 DAG 版还额外解析数组形式）：</p>
     * <ul>
     *   <li>{@code data.dataset_id} — 单个 ID 字符串。</li>
     *   <li>{@code data.dataset_ids} — 字符串数组或逗号分隔字符串。</li>
     * </ul>
     *
     * <p>所有提取到的 ID 会以 {@code "/sandbox/input/<id>"} 作为路径注册到 sharedContext，
     * 供下游节点在 ReAct 中通过 dataset_ids 参数引用。</p>
     *
     * @param record  节点执行结果
     * @param context 共享执行上下文
     */
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
                    // 兼容逗号分隔字符串
                    for (String id : ids.asText("").split(",")) {
                        String trimmed = id.trim();
                        if (!trimmed.isBlank()) {
                            context.registerDatasetRef(trimmed, "/sandbox/input/" + trimmed);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 节点输出不是 JSON 是常态（例如纯文本回答），降级到 debug
            log.debug("Failed to extract dataset ids from record output: {}", e.getMessage());
        }
    }

    /**
     * 基于共享上下文为单个节点构建 ReAct 执行上下文。
     *
     * <p>每次调用都深拷贝 completedTodos 和 datasetRefs，避免 worker 间相互污染。</p>
     */
    private ReactTodoExecutor.TodoExecutionContext buildTodoContext(SharedExecutionContext sharedContext) {
        return ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal(sharedContext.getUserGoal())
                .availableTools(sharedContext.getAvailableTools())
                .toolSpecifications(sharedContext.getToolSpecifications())
                .completedTodos(new ArrayList<>(sharedContext.getCompletedTodos()))
                .datasetRefs(new HashMap<>(sharedContext.getDatasetRefs()))
                .build();
    }

    /**
     * 汇总所有已完成 Todo 的输出，调用 LLM 生成最终 Markdown 回答。
     *
     * <p>步骤：</p>
     * <ol>
     *   <li>从已完成 Todo 中提取引用来源，构建去重编号后的引用表。</li>
     *   <li>组装提示词（与 {@link LinearWorkflowExecutor#generateFinalAnswer} 结构一致）：
     *       System Prompt + finalAnswerStageInstruction + dynamicPrefix + 用户问题 + 已完成任务 + 引用块。</li>
     *   <li>切换 AgentContext 的 phase=summarizing / stage=final_answer / reasoningEffort，
     *       调用 LLM 后在 finally 中恢复原值。</li>
     *   <li>若 request 配置了独立的 finalAnswerModel 则用之，否则复用 execution 阶段的 model。</li>
     *   <li>异常时退化：取最后一个已完成 Todo 的 output 作为回答。</li>
     * </ol>
     *
     * @param context 共享执行上下文
     * @param request 工作流请求
     * @return 最终回答 + 引用表
     */
    private FinalAnswerResult generateFinalAnswer(SharedExecutionContext context, WorkflowRequest request) {
        AgentCitationService.CitationMap citationMap = citationService.buildCitationMap(context.getCompletedTodos());
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(promptService.dagReactSystemPrompt()));

            // 组装上下文提示词
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
            contextText.append(citationService.buildPromptBlock(citationMap));
            contextText.append("\n请根据以上所有任务结果，生成对用户问题可直接展示的最终回答。");
            contextText.append("\n请直接输出 Markdown，不要把回答包在 JSON 或代码块里。若使用搜索证据，请在相关句子后标注引用序号，例如 [1] [2]。");

            messages.add(new UserMessage(contextText.toString()));
            // 切换 AgentContext 至 final_answer 阶段，调用结束后恢复
            String previousPhase = AgentContext.getPhase();
            String previousStage = AgentContext.getStage();
            String previousReasoningEffort = AgentContext.getReasoningEffort();
            AgentContext.setPhase(AgentObservabilityService.PHASE_SUMMARIZING);
            AgentContext.setStage("final_answer");
            if (request.getFinalAnswerReasoningEffort() != null && !request.getFinalAnswerReasoningEffort().isBlank()) {
                AgentContext.setReasoningEffort(request.getFinalAnswerReasoningEffort());
            }
            // final-answer 可使用独立模型；若未配置则复用 execution 模型
            ChatModel finalAnswerModel = request.getFinalAnswerModel() == null ? request.getModel() : request.getFinalAnswerModel();
            ChatResponse response;
            try {
                response = finalAnswerModel.chat(messages);
            } finally {
                // 恢复 AgentContext 各项 ThreadLocal，避免污染后续逻辑
                if (previousPhase == null || previousPhase.isBlank()) {
                    AgentContext.clearPhase();
                } else {
                    AgentContext.setPhase(previousPhase);
                }
                if (previousStage == null || previousStage.isBlank()) {
                    AgentContext.clearStage();
                } else {
                    AgentContext.setStage(previousStage);
                }
                if (previousReasoningEffort == null || previousReasoningEffort.isBlank()) {
                    AgentContext.clearReasoningEffort();
                } else {
                    AgentContext.setReasoningEffort(previousReasoningEffort);
                }
            }
            var aiMessage = response == null ? null : response.aiMessage();
            return new FinalAnswerResult(aiMessage != null ? aiMessage.text() : "", citationMap);
        } catch (Exception e) {
            log.error("Failed to generate DAG final answer", e);
            // 降级：把最后一个完成节点的 output 当作回答返回，确保用户能看到点东西
            List<CompletedTodoInfo> completed = context.getCompletedTodos();
            if (completed.isEmpty()) {
                return new FinalAnswerResult("无执行结果", citationMap);
            }
            String fallback = completed.get(completed.size() - 1).getOutput();
            return new FinalAnswerResult(fallback != null ? fallback : "无法生成回答: " + e.getMessage(), citationMap);
        }
    }

    /**
     * 解析当前应使用的 DAG 并行线程池规模。
     *
     * <p>优先级：热加载 local config &gt; 静态 yml properties &gt; @Value 默认值。
     * 结果会被 clamp 到 [1, 32]，避免外部配置错误导致线程池过大。</p>
     */
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

    /** 将整数夹到 [min, max] 区间。 */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 将 ReactTodoExecutor 的执行记录转为轻量的 TodoExecutionRecord，
     * 供 PlanJudge 和 PatchPlanner 使用。
     *
     * <p>TODO: 与 {@link LinearWorkflowExecutor#toLegacyRecord} 重复，
     * 后续应考虑提取到 utility 中。</p>
     */
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

    /**
     * 深拷贝 TodoPlan，确保后续 patch 操作不会污染调用方传入的对象。
     *
     * <p>注意 dependsOn 列表必须独立拷贝（否则修改一处会影响另一处）。
     * extractedEntities 和 dagMetadata 字段一并复制。</p>
     */
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
        cloned.setExtractedEntities(plan.getExtractedEntities() == null ? List.of() : new ArrayList<>(plan.getExtractedEntities()));
        cloned.setExecutionMode(plan.getExecutionMode());
        cloned.setDagMetadata(plan.getDagMetadata());
        return cloned;
    }

    /**
     * 追加 DAG_EXECUTION_COMPLETED 事件，包含整体成败、耗时、工具调用统计。
     *
     * <p>所有 DAG 退出路径（成功/失败/超限/取消/异常）都应调用本方法，
     * 确保前端可以根据该事件展示最终结果。</p>
     */
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

    /**
     * 根据 TodoItem 列表构建依赖图：建立正向（dependencies）和反向（dependents）两套邻接表。
     *
     * <p>注意：dependsOn 中引用的 ID 若不在 itemMap 中（例如指向一个已被 patch 删除的节点），
     * 会被忽略而非报错——保留 forward compatibility 让 patch 后的 Plan 也能正确构图。</p>
     *
     * @param items Plan 中的节点列表
     * @return 完整的依赖图
     */
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
                // 仅当依赖确实存在于当前 Plan 中时才建立边
                if (itemMap.containsKey(depId)) {
                    dependencies.get(item.getId()).add(depId);
                    dependents.get(depId).add(item.getId());
                }
            }
        }
        return new ExecutionGraph(itemMap, dependencies, dependents);
    }

    /** 空安全：null 转为空字符串。 */
    private String nvl(String value) {
        return value == null ? "" : value;
    }

    // ──────────────────────────────────────────────────────────────────
    // 内部数据类型
    // ──────────────────────────────────────────────────────────────────

    /** 最终回答和引用表的不可变记录。 */
    private record FinalAnswerResult(String answer, AgentCitationService.CitationMap citationMap) {
    }

    /** 一次 DAG 并行执行的原始返回值：每节点结果 + 累计工具调用数。 */
    private record DagParallelResult(
            Map<String, ReactTodoExecutor.TodoExecutionRecord> resultMap,
            int totalToolCallsUsed
    ) {
    }

    /**
     * 单次 DAG 完整执行的聚合结果，作为外层 patch 循环的"单步"输出。
     *
     * <p>无论成功失败都会构造此对象，包含足够的上下文供后续 PlanJudge 诊断或生成最终回答。</p>
     */
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

    /** Fallback 到线性执行的结果。 */
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

    /**
     * DAG 依赖图的内部表示，包含正反两套邻接表以及环检测能力。
     *
     * <p>设计意图：让所有"获取依赖"和"获取下游"的查询都是 O(1)，
     * 这样 worker 在 latch 释放时能迅速广播到所有下游。</p>
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class ExecutionGraph {
        /** 节点 ID → 节点对象的反向索引 */
        private Map<String, TodoItem> itemMap;
        /** nodeId → 它依赖的上游 ID 集合 */
        private Map<String, Set<String>> dependencies;
        /** nodeId → 依赖它的下游 ID 集合 */
        private Map<String, Set<String>> dependents;

        /** 取节点的依赖（上游）集合；不存在时返回空集合。 */
        public Set<String> getDependencies(String nodeId) {
            return dependencies.getOrDefault(nodeId, Set.of());
        }

        /** 取节点的下游集合；不存在时返回空集合。 */
        public Set<String> getDependents(String nodeId) {
            return dependents.getOrDefault(nodeId, Set.of());
        }

        /**
         * 检测整张图中是否存在环依赖。
         *
         * <p>使用经典三色 DFS：visiting 表示正在 DFS 路径上，visited 表示已完成检查。
         * 若 DFS 中再次访问 visiting 节点，说明有环。</p>
         */
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

        /** 从 nodeId 出发递归检查是否存在环。 */
        private boolean hasCycleFrom(String nodeId, Set<String> visiting, Set<String> visited) {
            if (visited.contains(nodeId)) {
                return false;
            }
            // visiting.add 返回 false 说明节点已在当前递归路径上，存在环
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

    /**
     * DAG 并行执行期间所有 worker 共享的执行上下文。
     *
     * <p>线程安全设计：</p>
     * <ul>
     *   <li>{@code completedTodos} 使用 {@link CopyOnWriteArrayList}，写少读多场景；
     *       多 worker 并发写时不会丢失元素。</li>
     *   <li>{@code datasetRefs} 使用 {@link ConcurrentHashMap}，支持并发 put。</li>
     *   <li>对外暴露的 getter 都返回拷贝，避免调用方修改内部结构。</li>
     * </ul>
     */
    private static class SharedExecutionContext {
        /** 用户原始目标（整个 run 的顶层问题） */
        private final String userGoal;
        /** 工具白名单：本次 run 允许 LLM 调用的工具名称集合 */
        private final Set<String> availableTools;
        /** 工具规范列表（带参数 schema），用于 LangChain4j 原生 tool_calls */
        private final List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications;
        /** 已完成 Todo 的累计信息（worker 并发写） */
        private final List<CompletedTodoInfo> completedTodos = new CopyOnWriteArrayList<>();
        /** 已注册的 dataset_id → 沙箱路径映射 */
        private final Map<String, String> datasetRefs = new ConcurrentHashMap<>();

        SharedExecutionContext(String userGoal,
                               Set<String> availableTools,
                               List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications) {
            this.userGoal = userGoal;
            this.availableTools = availableTools;
            // 防御性拷贝：调用方传入的列表后续不能影响内部状态
            this.toolSpecifications = toolSpecifications == null ? List.of() : new ArrayList<>(toolSpecifications);
        }

        /**
         * 把一个成功节点的执行结果追加到 completedTodos。
         *
         * <p>会保存完整的 messageHistory 以便后续节点恢复 CoT 上下文。</p>
         */
        void addCompletedTodo(TodoItem item, ReactTodoExecutor.TodoExecutionRecord record) {
            completedTodos.add(CompletedTodoInfo.builder()
                    .todoId(item.getId())
                    .description(item.getDescription())
                    .output(record.getOutput())
                    .summary(record.getSummary())
                    .messageHistory(record.getMessageHistory())
                    .build());
        }

        /** 注册一个 dataset_id → 路径的映射（线程安全）。 */
        void registerDatasetRef(String datasetId, String path) {
            datasetRefs.put(datasetId, path);
        }

        String getUserGoal() {
            return userGoal;
        }

        Set<String> getAvailableTools() {
            return availableTools;
        }

        /** 返回工具规范列表的拷贝，调用方不能修改内部结构。 */
        List<dev.langchain4j.agent.tool.ToolSpecification> getToolSpecifications() {
            return new ArrayList<>(toolSpecifications);
        }

        /** 返回已完成 Todo 列表的拷贝。 */
        List<CompletedTodoInfo> getCompletedTodos() {
            return new ArrayList<>(completedTodos);
        }

        /** 返回 dataset 引用映射的拷贝。 */
        Map<String, String> getDatasetRefs() {
            return new HashMap<>(datasetRefs);
        }
    }
}
