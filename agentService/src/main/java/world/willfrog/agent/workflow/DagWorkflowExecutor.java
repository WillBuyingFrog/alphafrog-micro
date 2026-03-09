package world.willfrog.agent.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.service.AgentEventService;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static final int THREAD_POOL_SIZE = 4;
    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    @Override
    public WorkflowExecutionResult execute(WorkflowRequest request) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        TodoPlan plan = request.getPlan();

        List<TodoItem> items = plan.getItems();
        if (items.isEmpty()) {
            return WorkflowExecutionResult.builder()
                    .success(true)
                    .finalAnswer("")
                    .build();
        }

        // 构建执行图
        ExecutionGraph graph = buildExecutionGraph(items);
        
        eventService.append(runId, userId, "DAG_EXECUTION_STARTED", Map.of(
                "total_nodes", items.size(),
                "max_parallelism", graph.getMaxParallelism()
        ));

        // 线程安全的全局上下文
        SharedExecutionContext sharedContext = new SharedExecutionContext(
                request.getUserGoal(),
                request.getToolSpecifications().stream()
                        .map(dev.langchain4j.agent.tool.ToolSpecification::name)
                        .collect(Collectors.toSet())
        );

        try {
            // 并行执行 DAG
            List<ReactTodoExecutor.TodoExecutionRecord> results = executeDagParallel(
                    graph, sharedContext, request
            );

            // 检查是否有失败
            Optional<ReactTodoExecutor.TodoExecutionRecord> failed = results.stream()
                    .filter(r -> !r.isSuccess())
                    .findFirst();

            if (failed.isPresent()) {
                return WorkflowExecutionResult.builder()
                        .success(false)
                        .finalAnswer("")
                        .failureReason(failed.get().getSummary())
                        .build();
            }

            // 生成最终回答
            String finalAnswer = generateFinalAnswer(sharedContext, request);

            eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", Map.of(
                    "success", true,
                    "completed_nodes", results.size()
            ));

            return WorkflowExecutionResult.builder()
                    .success(true)
                    .finalAnswer(finalAnswer)
                    .build();

        } catch (Exception e) {
            log.error("DAG execution failed", e);
            return WorkflowExecutionResult.builder()
                    .success(false)
                    .finalAnswer("")
                    .failureReason("DAG execution failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 并行执行 DAG。
     * 核心逻辑：使用 CountDownLatch 等待依赖完成，完成后提交到线程池执行。
     */
    private List<ReactTodoExecutor.TodoExecutionRecord> executeDagParallel(
            ExecutionGraph graph,
            SharedExecutionContext sharedContext,
            WorkflowRequest request) throws InterruptedException {
        
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        List<TodoItem> items = request.getPlan().getItems();
        
        // 节点完成状态跟踪
        Map<String, CountDownLatch> nodeLatches = new ConcurrentHashMap<>();
        Map<String, ReactTodoExecutor.TodoExecutionRecord> results = new ConcurrentHashMap<>();
        Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
        
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
            Future<?> future = executor.submit(() -> {
                try {
                    // 等待依赖完成
                    CountDownLatch latch = nodeLatches.get(item.getId());
                    latch.await();
                    
                    log.info("Executing DAG node {}: {}", item.getId(), item.getDescription());
                    
                    // 从全局上下文构建当前节点的执行上下文
                    ReactTodoExecutor.TodoExecutionContext todoContext = 
                            buildTodoContext(item, sharedContext, request);
                    
                    // 执行节点
                    ReactTodoExecutor.TodoExecutionRecord record = reactTodoExecutor.execute(
                            item.getDescription(),
                            todoContext,
                            request.getModel()
                    );
                    
                    results.put(item.getId(), record);
                    
                    if (record.isSuccess()) {
                        // 将结果同步到全局上下文
                        sharedContext.addCompletedTodo(item, record);
                        
                        // 提取 dataset_id
                        extractDatasetId(record, sharedContext);
                        
                        eventService.append(runId, userId, "DAG_NODE_COMPLETED", Map.of(
                                "todo_id", item.getId(),
                                "success", true
                        ));
                    } else {
                        eventService.append(runId, userId, "DAG_NODE_FAILED", Map.of(
                                "todo_id", item.getId(),
                                "error", record.getSummary()
                        ));
                    }
                    
                    // 通知后续节点
                    for (String nextId : graph.getDependents(item.getId())) {
                        nodeLatches.get(nextId).countDown();
                    }
                    
                    // 检查是否全部完成
                    if (completedCount.incrementAndGet() == totalCount) {
                        allDone.complete(null);
                    }
                    
                } catch (Exception e) {
                    log.error("Failed to execute node {}", item.getId(), e);
                    results.put(item.getId(), ReactTodoExecutor.TodoExecutionRecord.builder()
                            .success(false)
                            .summary("Execution error: " + e.getMessage())
                            .build());
                    allDone.completeExceptionally(e);
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
                if (itemMap.containsKey(depId)) {
                    dependencies.get(item.getId()).add(depId);
                    dependents.get(depId).add(item.getId());
                }
            }
        }
        
        return new ExecutionGraph(itemMap, dependencies, dependents);
    }

    // 内部类

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
            // 统计同一层级的最大节点数
            return dependencies.values().stream()
                    .mapToInt(Set::size)
                    .max()
                    .orElse(1);
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
