package world.willfrog.agent.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentObservabilityService;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * DAG 并行执行器（ReAct 模式）。
 *
 * <p>核心设计：
 * <ul>
 *   <li>通过拓扑排序确定执行顺序</li>
 *   <li>无依赖关系的节点并行执行</li>
 *   <li>通过线程安全的全局上下文共享执行结果</li>
 *   <li>每个节点执行时从全局上下文构建 ReAct 消息</li>
 * </ul>
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DagWorkflowExecutor implements WorkflowExecutor {

    private final AgentEventService eventService;
    private final ReactTodoExecutor reactTodoExecutor;
    private final AgentObservabilityService observabilityService;

    private static final int THREAD_POOL_SIZE = 4;
    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    
    // DAG 执行阶段标识
    private static final String PHASE_DAG_EXECUTION = "dag_execution";

    @Override
    public WorkflowExecutionResult execute(WorkflowRequest request) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        TodoPlan plan = request.getPlan();
        
        long dagStartTime = System.currentTimeMillis();
        Instant dagStartInstant = Instant.now();

        List<TodoItem> items = plan.getItems();
        if (items.isEmpty()) {
            return WorkflowExecutionResult.builder()
                    .success(true)
                    .finalAnswer("")
                    .build();
        }

        // 构建执行图
        ExecutionGraph graph = buildExecutionGraph(items);
        if (graph.hasCycle()) {
            String reason = "dag_circular_dependency";
            log.warn("Detected circular dependency in DAG plan: runId={}", runId);
            eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", Map.of(
                    "success", false,
                    "failure_reason", reason,
                    "total_nodes", items.size()
            ));
            return WorkflowExecutionResult.builder()
                    .success(false)
                    .finalAnswer("")
                    .failureReason(reason)
                    .build();
        }
        
        // 记录 DAG 特有指标
        DagMetrics dagMetrics = new DagMetrics(
                items.size(),
                graph.getEdgeCount(),
                graph.getMaxParallelism(),
                graph.calculateDepth()
        );
        
        eventService.append(runId, userId, "DAG_EXECUTION_STARTED", Map.of(
                "total_nodes", items.size(),
                "total_edges", dagMetrics.totalEdges,
                "max_parallelism", dagMetrics.maxParallelism,
                "graph_depth", dagMetrics.graphDepth,
                "started_at", dagStartInstant.toString()
        ));
        
        // 记录 DAG 结构到观测
        recordDagStructure(runId, graph, items);

        // 线程安全的全局上下文
        SharedExecutionContext sharedContext = new SharedExecutionContext(
                request.getUserGoal(),
                request.getToolSpecifications().stream()
                        .map(dev.langchain4j.agent.tool.ToolSpecification::name)
                        .collect(Collectors.toSet())
        );

        boolean success = false;
        String failureReason = null;
        List<ReactTodoExecutor.TodoExecutionRecord> results = null;

        try {
            // 并行执行 DAG
            results = executeDagParallel(
                    graph, sharedContext, request, dagMetrics
            );

            // 检查是否有失败
            Optional<ReactTodoExecutor.TodoExecutionRecord> failed = results.stream()
                    .filter(r -> !r.isSuccess())
                    .findFirst();

            if (failed.isPresent()) {
                success = false;
                failureReason = failed.get().getSummary();
            } else {
                success = true;
            }

        } catch (Exception e) {
            log.error("DAG execution failed", e);
            success = false;
            failureReason = "DAG execution failed: " + e.getMessage();
        } finally {
            // 无论成功失败都记录 DAG_EXECUTION_COMPLETED
            long dagDurationMs = System.currentTimeMillis() - dagStartTime;
            recordDagCompletion(runId, userId, success, dagMetrics, dagDurationMs, failureReason);
        }
        
        if (!success) {
            return WorkflowExecutionResult.builder()
                    .success(false)
                    .finalAnswer("")
                    .failureReason(failureReason)
                    .build();
        }

        // 生成最终回答
        String finalAnswer = generateFinalAnswer(sharedContext, request);

        return WorkflowExecutionResult.builder()
                .success(true)
                .finalAnswer(finalAnswer)
                .build();
    }
    
    private void recordDagStructure(String runId, ExecutionGraph graph, List<TodoItem> items) {
        // DAG 结构信息已通过 DAG_EXECUTION_STARTED 事件记录
        // 详细的节点和边信息可通过 Plan 获取
    }
    
    private void recordDagCompletion(String runId, String userId, boolean success, 
                                      DagMetrics metrics, long durationMs, String failureReason) {
        // 发送事件（包含所有 DAG 特有指标）
        Map<String, Object> completionPayload = new HashMap<>();
        completionPayload.put("success", success);
        completionPayload.put("completed_nodes", metrics.completedNodes.get());
        completionPayload.put("failed_nodes", metrics.failedNodes.get());
        completionPayload.put("skipped_nodes", metrics.skippedNodes.get());
        completionPayload.put("duration_ms", durationMs);
        completionPayload.put("max_actual_parallelism", metrics.maxActualParallelism.get());
        completionPayload.put("total_wait_time_ms", metrics.totalWaitTimeMs.get());
        completionPayload.put("avg_node_wait_time_ms", metrics.completedNodes.get() > 0 
                ? metrics.totalWaitTimeMs.get() / metrics.completedNodes.get() 
                : 0);
        completionPayload.put("total_nodes", metrics.totalNodes);
        completionPayload.put("total_edges", metrics.totalEdges);
        completionPayload.put("graph_depth", metrics.graphDepth);
        completionPayload.put("max_parallelism", metrics.maxParallelism);
        if (failureReason != null) {
            completionPayload.put("failure_reason", failureReason);
        }
        
        eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", completionPayload);
        
        log.info("DAG execution completed: runId={}, success={}, completed={}/{}, failed={}, skipped={}, duration={}ms",
                runId, success, metrics.completedNodes.get(), metrics.totalNodes, 
                metrics.failedNodes.get(), metrics.skippedNodes.get(), durationMs);
    }

    /**
     * 并行执行 DAG。
     * 核心逻辑：使用 CountDownLatch 等待依赖完成，完成后提交到线程池执行。
     */
    private List<ReactTodoExecutor.TodoExecutionRecord> executeDagParallel(
            ExecutionGraph graph,
            SharedExecutionContext sharedContext,
            WorkflowRequest request,
            DagMetrics dagMetrics) throws InterruptedException {
        
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        List<TodoItem> items = request.getPlan().getItems();
        
        // 节点完成状态跟踪
        Map<String, CountDownLatch> nodeLatches = new ConcurrentHashMap<>();
        Map<String, ReactTodoExecutor.TodoExecutionRecord> results = new ConcurrentHashMap<>();
        Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
        // 跟踪节点成功/失败状态（用于依赖检查）
        Map<String, Boolean> nodeSuccessStatus = new ConcurrentHashMap<>();
        // 节点开始执行时间（用于计算等待时间）
        Map<String, Long> nodeSubmitTime = new ConcurrentHashMap<>();
        Map<String, Long> nodeStartTime = new ConcurrentHashMap<>();
        // 当前并行执行计数
        AtomicInteger currentParallelism = new AtomicInteger(0);
        
        // 为每个节点创建 latch（1 = 需要等待一个完成）
        for (TodoItem item : items) {
            int depCount = graph.getDependencies(item.getId()).size();
            nodeLatches.put(item.getId(), new CountDownLatch(depCount));
        }
        
        // 完成信号
        CompletableFuture<Void> allDone = new CompletableFuture<>();
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalCount = items.size();
        
        // 提交所有节点执行任务
        for (TodoItem item : items) {
            nodeSubmitTime.put(item.getId(), System.currentTimeMillis());
            
            Future<?> future = executor.submit(() -> {
                long nodeWaitStart = System.currentTimeMillis();
                String nodePhase = PHASE_DAG_EXECUTION + "_" + item.getId();
                boolean parallelismIncremented = false;
                
                try {
                    // 等待依赖完成
                    CountDownLatch latch = nodeLatches.get(item.getId());
                    latch.await();
                    
                    // 记录实际开始执行时间
                    long nodeExecuteStart = System.currentTimeMillis();
                    nodeStartTime.put(item.getId(), nodeExecuteStart);
                    
                    // 计算等待时间
                    long waitTimeMs = nodeExecuteStart - nodeWaitStart;
                    dagMetrics.totalWaitTimeMs.addAndGet(waitTimeMs);
                    
                    // 更新并行度指标
                    int current = currentParallelism.incrementAndGet();
                    parallelismIncremented = true;
                    dagMetrics.maxActualParallelism.updateAndGet(max -> Math.max(max, current));
                    
                    // 检查依赖是否成功
                    Set<String> dependencies = graph.getDependencies(item.getId());
                    boolean allDepsSuccess = true;
                    String failedDep = null;
                    for (String depId : dependencies) {
                        Boolean depSuccess = nodeSuccessStatus.get(depId);
                        if (depSuccess == null || !depSuccess) {
                            allDepsSuccess = false;
                            failedDep = depId;
                            break;
                        }
                    }
                    
                    ReactTodoExecutor.TodoExecutionRecord record;
                    
                    if (!allDepsSuccess) {
                        // 依赖失败，跳过执行
                        log.warn("Skipping node {} due to failed dependency: {}", item.getId(), failedDep);
                        record = ReactTodoExecutor.TodoExecutionRecord.builder()
                                .success(false)
                                .output("")
                                .summary("Skipped: dependency " + failedDep + " failed")
                                .startedAt(Instant.ofEpochMilli(nodeExecuteStart))
                                .completedAt(Instant.now())
                                .build();
                        results.put(item.getId(), record);
                        nodeSuccessStatus.put(item.getId(), false);
                        dagMetrics.skippedNodes.incrementAndGet();
                        
                        eventService.append(runId, userId, "DAG_NODE_SKIPPED", Map.of(
                                "todo_id", item.getId(),
                                "reason", "dependency_failed",
                                "failed_dependency", failedDep,
                                "wait_time_ms", waitTimeMs
                        ));
                    } else {
                        // 依赖成功，执行当前节点
                        log.info("Executing DAG node {}: {}", item.getId(), item.getDescription());
                        
                        // 设置 AgentContext 的 Todo 上下文，让 LlmTrace/ToolTrace 能关联到此节点
                        AgentContext.setTodoContext(item.getId(), item.getSequence());
                        
                        // 从全局上下文构建当前节点的执行上下文
                        ReactTodoExecutor.TodoExecutionContext todoContext = 
                                buildTodoContext(item, sharedContext, request);
                        
                        try {
                            // 执行节点（带完整观测）
                            record = reactTodoExecutor.executeWithObservability(
                                    item.getDescription(),
                                    todoContext,
                                    request.getModel(),
                                    runId,
                                    nodePhase
                            );
                        } finally {
                            // 清理 Todo 上下文，避免跨节点污染
                            AgentContext.clearTodoContext();
                        }
                        
                        // 补充节点执行时间信息
                        record.setStartedAt(Instant.ofEpochMilli(nodeExecuteStart));
                        record.setCompletedAt(Instant.now());
                        
                        results.put(item.getId(), record);
                        nodeSuccessStatus.put(item.getId(), record.isSuccess());
                        
                        if (record.isSuccess()) {
                            dagMetrics.completedNodes.incrementAndGet();
                            
                            // 将结果同步到全局上下文
                            sharedContext.addCompletedTodo(item, record);
                            
                            // 提取 dataset_id
                            extractDatasetId(record, sharedContext);
                            
                            eventService.append(runId, userId, "DAG_NODE_COMPLETED", Map.of(
                                    "todo_id", item.getId(),
                                    "success", true,
                                    "wait_time_ms", waitTimeMs,
                                    "tool_name", record.getToolName() != null ? record.getToolName() : "none",
                                    "tool_duration_ms", record.getToolDurationMs() != null ? record.getToolDurationMs() : 0
                            ));
                        } else {
                            dagMetrics.failedNodes.incrementAndGet();
                            
                            eventService.append(runId, userId, "DAG_NODE_FAILED", Map.of(
                                    "todo_id", item.getId(),
                                    "error", record.getSummary(),
                                    "wait_time_ms", waitTimeMs,
                                    "tool_name", record.getToolName() != null ? record.getToolName() : "none"
                            ));
                        }
                    }
                    
                    // 更新并行度
                    if (parallelismIncremented) {
                        currentParallelism.decrementAndGet();
                    }
                    
                    // 通知后续节点
                    for (String nextId : graph.getDependents(item.getId())) {
                        nodeLatches.get(nextId).countDown();
                    }
                    
                    // 检查是否全部完成
                    if (completedCount.incrementAndGet() == totalCount) {
                        allDone.complete(null);
                    }
                    
                } catch (Throwable t) {
                    log.error("Failed to execute node {}", item.getId(), t);
                    dagMetrics.failedNodes.incrementAndGet();
                    if (parallelismIncremented) {
                        currentParallelism.decrementAndGet();
                    }
                    
                    results.put(item.getId(), ReactTodoExecutor.TodoExecutionRecord.builder()
                            .success(false)
                            .summary("Execution error: " + t.getMessage())
                            .startedAt(Instant.ofEpochMilli(nodeWaitStart))
                            .completedAt(Instant.now())
                            .build());
                    nodeSuccessStatus.put(item.getId(), false);
                    allDone.completeExceptionally(t);
                }
            });
            
            runningTasks.put(item.getId(), future);
        }
        
        // 等待所有节点完成
        try {
            allDone.get(30, TimeUnit.MINUTES);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("DAG execution failed", e);
        }
        
        // 按原始顺序返回结果
        List<ReactTodoExecutor.TodoExecutionRecord> orderedResults = new ArrayList<>();
        for (TodoItem item : items) {
            orderedResults.add(results.getOrDefault(item.getId(), 
                    ReactTodoExecutor.TodoExecutionRecord.builder()
                            .success(false)
                            .summary("No result")
                            .build()));
        }
        
        return orderedResults;
    }

    /**
     * 从全局上下文构建单个 Todo 的执行上下文。
     */
    private ReactTodoExecutor.TodoExecutionContext buildTodoContext(
            TodoItem item,
            SharedExecutionContext sharedContext,
            WorkflowRequest request) {
        
        return ReactTodoExecutor.TodoExecutionContext.builder()
                .userGoal(sharedContext.getUserGoal())
                .availableTools(sharedContext.getAvailableTools())
                .completedTodos(new ArrayList<>(sharedContext.getCompletedTodos()))
                .datasetRefs(new HashMap<>(sharedContext.getDatasetRefs()))
                .build();
    }

    private void extractDatasetId(ReactTodoExecutor.TodoExecutionRecord record, SharedExecutionContext context) {
        try {
            String output = record.getOutput();
            if (output != null && output.contains("dataset_id")) {
                // 简单提取，实际应用 JSON 解析
                int start = output.indexOf("\"dataset_id\":\"");
                if (start > 0) {
                    start += "\"dataset_id\":\"".length();
                    int end = output.indexOf("\"", start);
                    if (end > start) {
                        String datasetId = output.substring(start, end);
                        context.registerDatasetRef(datasetId, "/sandbox/input/" + datasetId);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract dataset_id", e);
        }
    }

    private String generateFinalAnswer(SharedExecutionContext context, WorkflowRequest request) {
        // 使用最后一个成功节点的输出或调用 LLM 生成
        // 简化版本：直接返回最后一个节点的输出
        List<CompletedTodoInfo> completed = context.getCompletedTodos();
        if (completed.isEmpty()) {
            return "无执行结果";
        }
        return completed.get(completed.size() - 1).getOutput();
    }

    /**
     * 构建执行图（节点与依赖关系）。
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
                if (itemMap.containsKey(depId)) {
                    dependencies.get(item.getId()).add(depId);
                    dependents.get(depId).add(item.getId());
                }
            }
        }
        
        return new ExecutionGraph(itemMap, dependencies, dependents);
    }

    // 内部类

    /**
     * DAG 执行指标收集器。
     */
    private static class DagMetrics {
        final int totalNodes;
        final int totalEdges;
        final int maxParallelism;
        final int graphDepth;
        
        final AtomicInteger completedNodes = new AtomicInteger(0);
        final AtomicInteger failedNodes = new AtomicInteger(0);
        final AtomicInteger skippedNodes = new AtomicInteger(0);
        final AtomicInteger maxActualParallelism = new AtomicInteger(0);
        final AtomicLong totalWaitTimeMs = new AtomicLong(0);
        
        DagMetrics(int totalNodes, int totalEdges, int maxParallelism, int graphDepth) {
            this.totalNodes = totalNodes;
            this.totalEdges = totalEdges;
            this.maxParallelism = maxParallelism;
            this.graphDepth = graphDepth;
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
        
        public int getMaxParallelism() {
            // 计算最大并行度：统计同一层级的最大节点数
            Map<Integer, Integer> levelCounts = new HashMap<>();
            for (TodoItem item : itemMap.values()) {
                int level = getNodeLevel(item.getId());
                levelCounts.merge(level, 1, Integer::sum);
            }
            return levelCounts.values().stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(1);
        }
        
        public int getEdgeCount() {
            return dependencies.values().stream()
                    .mapToInt(Set::size)
                    .sum();
        }
        
        /**
         * 计算图的深度（最长依赖链）。
         */
        public int calculateDepth() {
            Map<String, Integer> depthCache = new HashMap<>();
            int maxDepth = 0;
            for (String nodeId : itemMap.keySet()) {
                int depth = calculateNodeDepth(nodeId, depthCache, new HashSet<>());
                maxDepth = Math.max(maxDepth, depth);
            }
            return maxDepth;
        }
        
        private int calculateNodeDepth(String nodeId, Map<String, Integer> cache, Set<String> visited) {
            if (cache.containsKey(nodeId)) {
                return cache.get(nodeId);
            }
            if (visited.contains(nodeId)) {
                return 0; // 环路检测
            }
            visited.add(nodeId);
            
            Set<String> deps = dependencies.getOrDefault(nodeId, Set.of());
            if (deps.isEmpty()) {
                cache.put(nodeId, 1);
                return 1;
            }
            
            int maxDepDepth = 0;
            for (String depId : deps) {
                int depDepth = calculateNodeDepth(depId, cache, visited);
                maxDepDepth = Math.max(maxDepDepth, depDepth);
            }
            
            int depth = maxDepDepth + 1;
            cache.put(nodeId, depth);
            return depth;
        }
        
        private int getNodeLevel(String nodeId) {
            // 节点的层级 = 其依赖的深度
            Set<String> deps = dependencies.getOrDefault(nodeId, Set.of());
            if (deps.isEmpty()) {
                return 0;
            }
            int maxDepLevel = 0;
            for (String depId : deps) {
                maxDepLevel = Math.max(maxDepLevel, getNodeLevel(depId));
            }
            return maxDepLevel + 1;
        }
        
        /**
         * 获取所有边（用于观测）。
         */
        public List<Map<String, String>> getEdges() {
            List<Map<String, String>> edges = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                String target = entry.getKey();
                for (String source : entry.getValue()) {
                    edges.add(Map.of(
                            "from", source,
                            "to", target
                    ));
                }
            }
            return edges;
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

    /**
     * 线程安全的全局执行上下文。
     */
    private static class SharedExecutionContext {
        private final String userGoal;
        private final Set<String> availableTools;
        private final List<world.willfrog.agent.workflow.CompletedTodoInfo> completedTodos = new CopyOnWriteArrayList<>();
        private final Map<String, String> datasetRefs = new ConcurrentHashMap<>();
        
        public SharedExecutionContext(String userGoal, Set<String> availableTools) {
            this.userGoal = userGoal;
            this.availableTools = availableTools;
        }
        
        public void addCompletedTodo(TodoItem item, ReactTodoExecutor.TodoExecutionRecord record) {
            completedTodos.add(world.willfrog.agent.workflow.CompletedTodoInfo.builder()
                    .todoId(item.getId())
                    .description(item.getDescription())
                    .output(record.getOutput())
                    .summary(record.getSummary())
                    .build());
        }
        
        public void registerDatasetRef(String datasetId, String path) {
            datasetRefs.put(datasetId, path);
        }
        
        public String getUserGoal() { return userGoal; }
        public Set<String> getAvailableTools() { return availableTools; }
        public List<world.willfrog.agent.workflow.CompletedTodoInfo> getCompletedTodos() { return new ArrayList<>(completedTodos); }
        public Map<String, String> getDatasetRefs() { return new HashMap<>(datasetRefs); }
    }

}
