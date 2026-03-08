package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.service.ReactConversationContext;
import world.willfrog.agent.tool.ToolRouter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DAG 并行执行器。
 *
 * <p>基于 ReAct 架构实现 Todo 的 DAG（有向无环图）并行调度执行。
 * 使用有界线程池避免资源耗尽，通过 CountDownLatch 管理节点就绪状态。</p>
 *
 * <h3>线程安全设计</h3>
 * <ul>
 *   <li>Global ReactConversationContext 的修改通过 synchronized 保证线程安全</li>
 *   <li>并行执行时，每个分支使用独立的分支上下文（从 Global 复制）</li>
 *   <li>分支完成后，将结果同步合并回 Global Context</li>
 *   <li>节点完成状态使用 ConcurrentHashMap.newKeySet()</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DagWorkflowExecutor implements WorkflowExecutor {

    private static final int DEFAULT_DAG_THREAD_POOL_SIZE = 4;
    private static final int DAG_TIMEOUT_MINUTES = 30;

    private final AgentEventService eventService;
    private final AgentPromptService promptService;
    private final ToolRouter toolRouter;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final DagBuilder dagBuilder;
    private final ToolCallCounter toolCallCounter;
    private final TodoParamResolver paramResolver;
    private final ObjectMapper objectMapper;

    @Override
    public WorkflowExecutionResult execute(LinearWorkflowExecutor.WorkflowRequest request) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();

        // 1. 构建 DAG
        ExecutionGraph graph;
        try {
            graph = dagBuilder.build(request.getTodoPlan());
        } catch (IllegalArgumentException e) {
            log.error("DAG construction failed (circular dependency): {}", e.getMessage());
            return WorkflowExecutionResult.builder()
                    .success(false)
                    .failureReason("dag_circular_dependency:" + e.getMessage())
                    .finalAnswer("执行计划中存在循环依赖，无法执行。")
                    .build();
        }

        if (graph.getTotalNodes() == 0) {
            return WorkflowExecutionResult.builder()
                    .success(true)
                    .finalAnswer("")
                    .build();
        }

        eventService.append(runId, userId, "DAG_EXECUTION_STARTED", Map.of(
                "total_nodes", graph.getTotalNodes(),
                "max_depth", graph.getMaxDepth(),
                "max_parallelism", graph.getMaxParallelism()
        ));

        // 2. 初始化 Global ReAct Context
        ReactConversationContext globalReactCtx = new ReactConversationContext();
        globalReactCtx.setSystemMessage(promptService.reactSystemPrompt());

        // 3. 初始化状态
        Map<String, TodoExecutionRecord> context = new ConcurrentHashMap<>();
        Set<String> completedNodes = ConcurrentHashMap.newKeySet();
        Set<String> failedNodes = ConcurrentHashMap.newKeySet();
        List<TodoItem> completedItems = new ArrayList<>();
        AtomicInteger totalToolCalls = new AtomicInteger(0);

        // 可变入度表（并发安全）
        Map<String, AtomicInteger> mutableInDegree = new ConcurrentHashMap<>();
        for (Map.Entry<String, Integer> entry : graph.getInDegree().entrySet()) {
            mutableInDegree.put(entry.getKey(), new AtomicInteger(entry.getValue()));
        }

        // 4. 使用有界线程池
        int poolSize = Math.min(graph.getMaxParallelism(), DEFAULT_DAG_THREAD_POOL_SIZE);
        ExecutorService executorService = Executors.newFixedThreadPool(poolSize);
        CountDownLatch allDone = new CountDownLatch(graph.getTotalNodes());

        // 捕获父线程的 AgentContext
        String parentRunId = AgentContext.getRunId();
        String parentUserId = AgentContext.getUserId();
        boolean parentDebugMode = AgentContext.isDebugMode();

        try {
            // 5. 提交初始就绪节点
            for (String readyNodeId : graph.getReadyNodes()) {
                submitNode(readyNodeId, graph, request, globalReactCtx, context,
                        completedNodes, failedNodes, completedItems, totalToolCalls,
                        mutableInDegree, executorService, allDone,
                        parentRunId, parentUserId, parentDebugMode);
            }

            // 6. 等待所有节点完成
            boolean finished = allDone.await(DAG_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                log.warn("DAG execution timed out after {} minutes", DAG_TIMEOUT_MINUTES);
                return WorkflowExecutionResult.builder()
                        .success(false)
                        .failureReason("dag_timeout")
                        .finalAnswer("执行计划超时，部分任务未完成。")
                        .completedItems(new ArrayList<>(completedItems))
                        .context(new LinkedHashMap<>(context))
                        .toolCallsUsed(totalToolCalls.get())
                        .build();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("DAG execution interrupted");
            return WorkflowExecutionResult.builder()
                    .success(false)
                    .failureReason("dag_interrupted")
                    .finalAnswer("执行计划被中断。")
                    .completedItems(new ArrayList<>(completedItems))
                    .context(new LinkedHashMap<>(context))
                    .toolCallsUsed(totalToolCalls.get())
                    .build();
        } finally {
            executorService.shutdown();
        }

        // 7. 生成最终回答
        boolean hasFailure = !failedNodes.isEmpty();
        String finalAnswer = generateDagFinalAnswer(request, completedItems, context, globalReactCtx);

        eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", Map.of(
                "completed_nodes", completedNodes.size(),
                "failed_nodes", failedNodes.size(),
                "total_tool_calls", totalToolCalls.get()
        ));

        return WorkflowExecutionResult.builder()
                .success(!hasFailure)
                .failureReason(hasFailure ? "dag_partial_failed" : "")
                .finalAnswer(finalAnswer)
                .completedItems(new ArrayList<>(completedItems))
                .context(new LinkedHashMap<>(context))
                .toolCallsUsed(totalToolCalls.get())
                .build();
    }

    private void submitNode(String nodeId,
                            ExecutionGraph graph,
                            LinearWorkflowExecutor.WorkflowRequest request,
                            ReactConversationContext globalReactCtx,
                            Map<String, TodoExecutionRecord> context,
                            Set<String> completedNodes,
                            Set<String> failedNodes,
                            List<TodoItem> completedItems,
                            AtomicInteger totalToolCalls,
                            Map<String, AtomicInteger> mutableInDegree,
                            ExecutorService executorService,
                            CountDownLatch allDone,
                            String parentRunId,
                            String parentUserId,
                            boolean parentDebugMode) {
        executorService.submit(() -> {
            // 设置 ThreadLocal 上下文
            AgentContext.setRunId(parentRunId);
            AgentContext.setUserId(parentUserId);
            AgentContext.setDebugMode(parentDebugMode);
            try {
                TodoItem item = graph.getNode(nodeId);
                if (item == null) {
                    log.warn("DAG node not found: {}", nodeId);
                    allDone.countDown();
                    return;
                }

                String runId = request.getRun().getId();
                String userId = request.getUserId();

                eventService.append(runId, userId, "DAG_NODE_STARTED", Map.of(
                        "node_id", nodeId,
                        "tool", nvl(item.getToolName()),
                        "type", item.getType() == null ? "TOOL_CALL" : item.getType().name()
                ));

                // 执行节点
                TodoExecutionRecord record = executeDagNode(request, item, context);
                item.setCompletedAt(Instant.now());
                item.setResultSummary(nvl(record.getSummary()));
                item.setOutput(nvl(record.getOutput()));
                totalToolCalls.incrementAndGet();

                if (record.isSuccess()) {
                    item.setStatus(TodoStatus.COMPLETED);
                    completedNodes.add(nodeId);
                    context.put(nodeId, record);
                    synchronized (completedItems) {
                        completedItems.add(item);
                    }

                    // 同步合并 Observation 到 Global Context
                    synchronized (globalReactCtx) {
                        globalReactCtx.addUserMessage("[Observation] " + nvl(item.getId())
                                + " 完成: " + preview(record.getSummary()));
                    }

                    eventService.append(runId, userId, "DAG_NODE_COMPLETED", Map.of(
                            "node_id", nodeId,
                            "success", true,
                            "summary", nvl(record.getSummary())
                    ));

                    // 减少后继节点入度，提交新就绪节点
                    for (String successor : graph.getSuccessors(nodeId)) {
                        AtomicInteger degree = mutableInDegree.get(successor);
                        if (degree != null && degree.decrementAndGet() == 0) {
                            submitNode(successor, graph, request, globalReactCtx, context,
                                    completedNodes, failedNodes, completedItems, totalToolCalls,
                                    mutableInDegree, executorService, allDone,
                                    parentRunId, parentUserId, parentDebugMode);
                        }
                    }
                } else {
                    item.setStatus(TodoStatus.FAILED);
                    failedNodes.add(nodeId);
                    context.put(nodeId, record);

                    synchronized (globalReactCtx) {
                        globalReactCtx.addUserMessage("[Observation] " + nvl(item.getId())
                                + " 失败: " + preview(record.getSummary()));
                    }

                    eventService.append(runId, userId, "DAG_NODE_FAILED", Map.of(
                            "node_id", nodeId,
                            "success", false,
                            "summary", nvl(record.getSummary())
                    ));

                    // 失败时，将所有依赖此节点的后继节点也标记为跳过
                    skipDependentNodes(nodeId, graph, failedNodes, allDone, mutableInDegree);
                }
            } catch (Exception e) {
                log.error("DAG node execution error: nodeId={}", nodeId, e);
                failedNodes.add(nodeId);
                skipDependentNodes(nodeId, graph, failedNodes, allDone, mutableInDegree);
            } finally {
                allDone.countDown();
                AgentContext.clear();
            }
        });
    }

    /**
     * 递归跳过依赖失败节点的所有后继节点。
     */
    private void skipDependentNodes(String failedNodeId,
                                    ExecutionGraph graph,
                                    Set<String> failedNodes,
                                    CountDownLatch allDone,
                                    Map<String, AtomicInteger> mutableInDegree) {
        for (String successor : graph.getSuccessors(failedNodeId)) {
            if (failedNodes.add(successor)) {
                log.debug("Skipping dependent node '{}' due to failed node '{}'", successor, failedNodeId);
                allDone.countDown();
                skipDependentNodes(successor, graph, failedNodes, allDone, mutableInDegree);
            }
        }
    }

    private TodoExecutionRecord executeDagNode(LinearWorkflowExecutor.WorkflowRequest request,
                                               TodoItem item,
                                               Map<String, TodoExecutionRecord> context) {
        TodoType type = item.getType() == null ? TodoType.TOOL_CALL : item.getType();

        if (type == TodoType.THOUGHT) {
            return TodoExecutionRecord.builder()
                    .success(true)
                    .output(nvl(item.getReasoning()))
                    .summary(nvl(item.getReasoning()))
                    .toolCallsUsed(0)
                    .build();
        }

        if (type != TodoType.TOOL_CALL) {
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("DAG executor only supports TOOL_CALL and THOUGHT types")
                    .summary("Unsupported type: " + type)
                    .toolCallsUsed(0)
                    .build();
        }

        // 解析参数
        Map<String, Object> resolvedParams = paramResolver.resolve(
                item.getParams() == null ? Map.of() : item.getParams(),
                context
        );

        String toolName = nvl(item.getToolName());
        try {
            ToolRouter.ToolInvocationResult toolResult = toolRouter.invokeWithMeta(toolName, resolvedParams);
            return TodoExecutionRecord.builder()
                    .success(toolResult.isSuccess())
                    .output(nvl(toolResult.getOutput()))
                    .summary(toolResult.isSuccess()
                            ? preview(toolResult.getOutput())
                            : "Tool call failed: " + preview(toolResult.getOutput()))
                    .toolCallsUsed(1)
                    .failureCategory(toolResult.isSuccess() ? null : TodoFailureCategory.RUNTIME.name())
                    .build();
        } catch (Exception e) {
            log.warn("DAG node tool call failed: nodeId={}, tool={}, error={}", item.getId(), toolName, e.getMessage());
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("Exception: " + e.getMessage())
                    .summary("Tool call exception: " + e.getMessage())
                    .toolCallsUsed(1)
                    .failureCategory(TodoFailureCategory.RUNTIME.name())
                    .build();
        }
    }

    private String generateDagFinalAnswer(LinearWorkflowExecutor.WorkflowRequest request,
                                          List<TodoItem> completedItems,
                                          Map<String, TodoExecutionRecord> context,
                                          ReactConversationContext reactCtx) {
        try {
            String stageInstruction = promptService.finalAnswerStageInstruction();
            StringBuilder sb = new StringBuilder();
            sb.append(promptService.dynamicContextPrefix()).append("\n");
            sb.append(stageInstruction).append("\n");
            sb.append("用户目标: ").append(nvl(request.getUserGoal())).append("\n");
            sb.append("已完成步骤 (").append(completedItems.size()).append("):\n");

            synchronized (completedItems) {
                for (TodoItem item : completedItems) {
                    sb.append("- ").append(nvl(item.getId()))
                            .append(" (").append(nvl(item.getToolName())).append(")")
                            .append(": ").append(preview(item.getResultSummary())).append("\n");
                }
            }

            synchronized (reactCtx) {
                reactCtx.addUserMessage(sb.toString());
                ChatResponse response = request.getModel().chat(reactCtx.getMessages());
                String text = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());
                reactCtx.addAssistantMessage(text);
                return text;
            }
        } catch (Exception e) {
            log.warn("Failed to generate DAG final answer: {}", e.getMessage());
            return "执行完成，但生成最终回答时出错。";
        }
    }

    private String preview(String text) {
        if (text == null) return "";
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }
}
