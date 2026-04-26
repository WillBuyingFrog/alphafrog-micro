package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.graph.SubAgentRunner;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.tool.ToolRouter;

import java.time.Instant;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * ReAct 模式的单个 Todo 执行器。
 *
 * <p>每个 Todo 内部运行一个多轮 ReAct 循环：
 * LLM 决策 → 工具调用 → 结果注入上下文 → 再次 LLM 决策 → ...
 * 直到 LLM 输出最终回答，或达到最大调用次数。</p>
 *
 * <p>外层还有重试机制（MAX_RETRIES = 2）：如果整个 ReAct 循环失败，
 * 会构建带错误提示的新上下文并重新执行整个循环。</p>
 *
 * <p>Sub-Agent 机制（#36 §4.3）：LLM 可调用 spawnSubAgent 在后台启动子代理，
 * 主线程继续运行其他工具，待所有子任务完成后通过 waitForSubAgent 汇总结果。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReactTodoExecutor {

    private final AgentPromptService promptService;
    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper;
    private final AgentObservabilityService observabilityService;

    /**
     * SubAgentRunner — 可选注入，避免在没有完整 Spring 上下文的单元测试中出错。
     * 若为 null，则 spawnSubAgent 调用返回不可用提示而非抛异常。
     */
    @Autowired(required = false)
    @Setter
    private SubAgentRunner subAgentRunner;

    /** Sub-Agent 后台执行线程池（守护线程，随 JVM 退出自动关闭）。 */
    private final ExecutorService subAgentExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sub-agent-worker");
        t.setDaemon(true);
        return t;
    });

    /** 单个 Sub-Agent 等待超时（秒）。 */
    @Value("${agent.flow.react.sub-agent-timeout-seconds:120}")
    private int subAgentTimeoutSeconds;

    @Value("${agent.flow.react.max-calls-per-todo:10}")
    private int maxCallsPerTodo;

    private final AgentRunStateStore stateStore;

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down sub-agent executor...");
        subAgentExecutor.shutdown();
        try {
            if (!subAgentExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                subAgentExecutor.shutdownNow();
                log.warn("Sub-agent executor did not terminate within 30s, forced shutdown");
            }
        } catch (InterruptedException e) {
            subAgentExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public TodoExecutionRecord execute(String description, TodoExecutionContext context, ChatModel model) {
        return executeWithObservability(description, context, model, null, null);
    }
    
    /**
     * 执行 Todo 并记录完整的可观测性数据。
     * 
     * @param description 任务描述
     * @param context 执行上下文
     * @param model LLM 模型
     * @param runId Run ID（用于观测）
     * @param phase 执行阶段（用于观测）
     * @return 执行记录
     */
    public TodoExecutionRecord executeWithObservability(String description, 
                                                         TodoExecutionContext context, 
                                                         ChatModel model,
                                                         String runId,
                                                         String phase) {
        return executeWithRetry(description, context, model, runId, phase, 0);
    }
    
    /**
     * 执行 Todo 带重试机制。
     * 重试的粒度是"整个 Todo 的 ReAct 循环"。
     * 
     * @param retryCount 当前重试次数
     */
    private TodoExecutionRecord executeWithRetry(String description, 
                                                  TodoExecutionContext context, 
                                                  ChatModel model,
                                                  String runId,
                                                  String phase,
                                                  int retryCount) {
        final int MAX_RETRIES = 2;
        
        try {
            TodoExecutionRecord record = executeReActLoop(description, context, model, runId, phase, retryCount);
            
            // 如果失败且未达到最大重试次数，进行重试
            if (!record.isSuccess() && retryCount < MAX_RETRIES) {
                String errorHint = extractErrorHint(record.getOutput());
                log.warn("Todo execution failed, will retry {}/{}: {}, error: {}", 
                        retryCount + 1, MAX_RETRIES, description, errorHint);
                
                // 构建带错误提示的新上下文
                TodoExecutionContext retryContext = buildRetryContext(context, errorHint);
                
                return executeWithRetry(description, retryContext, model, runId, phase, retryCount + 1);
            }
            
            return record;
            
        } catch (Exception e) {
            log.error("Failed to execute todo: {}", description, e);
            
            // 异常时也尝试重试
            if (retryCount < MAX_RETRIES) {
                log.warn("Todo execution exception, will retry {}/{}: {}", 
                        retryCount + 1, MAX_RETRIES, e.getMessage());
                return executeWithRetry(description, context, model, runId, phase, retryCount + 1);
            }
            
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary("Error after " + (MAX_RETRIES + 1) + " attempts: " + e.getMessage())
                    .retryCount(retryCount)
                    .build();
        }
    }

    /**
     * 多轮 ReAct 循环：在单个 Todo 内持续调用 LLM 和工具，直到 LLM 输出 answer 或达到上限。
     *
     * <p>上下文链条示例：
     * <pre>
     * [System: dagReactSystemPrompt + 上下文]
     * [User: 当前任务描述]
     * ^[CoT: LLM 决定调用工具 A]         ← Round 1
     * [User: 工具 A 的结果]
     * ^[CoT: LLM 分析结果，决定调用工具 B] ← Round 2
     * [User: 工具 B 的结果]
     * ^[CoT: 任务完成，输出 answer]        ← Round 3
     * </pre>
     *
     * <p>Sub-Agent 模式（#36 §4.3）：若 LLM 决定调用 spawnSubAgent，则在后台线程中启动
     * SubAgentRunner；主线程继续处理其他 ReAct 轮次。调用 waitForSubAgent 时主线程阻塞
     * 直到对应 Sub-Agent 完成并返回结果。</p>
     */
    private TodoExecutionRecord executeReActLoop(String description,
                                                  TodoExecutionContext context,
                                                  ChatModel model,
                                                  String runId,
                                                  String phase,
                                                  int retryCount) {
        // 构建初始 ReAct 消息（包含重试上下文）
        List<ChatMessage> messages = buildMessagesWithRetryContext(description, context, retryCount);
        
        int callCount = 0;
        int toolCallsUsed = 0;
        String lastLlmTraceId = null;
        String lastOutput = "";

        // Sub-Agent 追踪：Map<sub_agent_id, Future<SubAgentResult>>
        Map<String, Future<SubAgentRunner.SubAgentResult>> pendingSubAgents = new HashMap<>();
        AtomicInteger subAgentIdCounter = new AtomicInteger(0);

        while (callCount < maxCallsPerTodo) {
            // 检查是否被取消或正在取消
            if (runId != null && !runId.isBlank()) {
                Optional<String> currentStatus = stateStore.loadRunStatus(runId);
                if (currentStatus.isPresent() && 
                    (currentStatus.get().equals(AgentRunStatus.CANCELING.name()) ||
                     currentStatus.get().equals(AgentRunStatus.CANCELED.name()) || 
                     currentStatus.get().equals(AgentRunStatus.FAILED.name()))) {
                    log.info("Run {} has been {} during execution, stopping ReAct loop", runId, currentStatus.get());
                    return TodoExecutionRecord.builder()
                            .success(false)
                            .output(lastOutput)
                            .summary("Run was " + currentStatus.get() + " during execution")
                            .llmTraceId(lastLlmTraceId)
                            .retryCount(retryCount)
                            .toolCallsUsed(toolCallsUsed)
                            .messageHistory(convertMessagesToSnapshots(messages))
                            .build();
                }
            }

            long llmStartTime = System.currentTimeMillis();
            String llmTraceId = null;
            List<ChatMessage> requestMessages = new ArrayList<>(messages);

            // 调用 LLM 决策（优先走原生 tool calling 协议）
            ChatResponse response = chatWithTools(model, requestMessages, context);
            AiMessage aiMessage = response.aiMessage();
            String llmOutput = aiMessage != null && aiMessage.text() != null ? aiMessage.text() : "";
            long llmDurationMs = System.currentTimeMillis() - llmStartTime;

            // 将 Assistant 消息加入上下文（保留原生 tool call 结构）
            if (aiMessage != null) {
                messages.add(aiMessage);
            } else if (!llmOutput.isBlank()) {
                messages.add(AiMessage.from(llmOutput));
            }

            // 记录 LLM 调用（每次单独记录）
            if (runId != null && !runId.isBlank()) {
                TokenUsage tokenUsage = response.tokenUsage();
                llmTraceId = observabilityService.recordLlmCall(
                        runId,
                        phase != null ? phase : "dag_execution",
                        tokenUsage,
                        llmDurationMs,
                        null,
                        null,
                        null,
                        buildRequestSnapshot(requestMessages, description),
                        llmOutput
                );
            }
            lastLlmTraceId = llmTraceId;
            callCount++;

            // 原生 tool calling：优先读取 toolExecutionRequests
            List<ToolExecutionRequest> toolRequests = aiMessage == null || aiMessage.toolExecutionRequests() == null
                    ? List.of()
                    : aiMessage.toolExecutionRequests();

            if (!toolRequests.isEmpty()) {
                for (ToolExecutionRequest toolRequest : toolRequests) {
                    if (llmTraceId != null) {
                        String excerpt = toolRequest.name() + " " + trimToolArguments(toolRequest.arguments());
                        AgentContext.setDecisionContext(
                                llmTraceId,
                                phase != null ? phase : "dag_execution",
                                excerpt
                        );
                    }

                    ToolExecutionOutcome outcome;
                    try {
                        outcome = executeToolRequest(toolRequest, context, runId, phase, model,
                                pendingSubAgents, subAgentIdCounter);
                    } finally {
                        AgentContext.clearDecisionContext();
                    }

                    toolCallsUsed++;
                    lastOutput = outcome.result();
                    extractAndRegisterDatasetRef(outcome.result(), context);
                    messages.add(ToolExecutionResultMessage.builder()
                            .id(toolRequest.id())
                            .toolName(toolRequest.name())
                            .text(outcome.result())
                            .isError(!outcome.success())
                            .build());

                    if (!outcome.success()) {
                        log.warn("Tool {} failed in ReAct round {}, LLM will decide next step",
                                toolRequest.name(), callCount);
                    }
                }
                continue;
            }

            // breaking change: 工具调用只接受原生 tool_calls。正文 JSON 不再被解析或执行。
            return TodoExecutionRecord.builder()
                    .success(true)
                    .output(nvl(llmOutput))
                    .summary("Completed in " + callCount + " round(s), " + toolCallsUsed + " tool call(s)")
                    .llmTraceId(lastLlmTraceId)
                    .retryCount(retryCount)
                    .toolCallsUsed(toolCallsUsed)
                    .messageHistory(convertMessagesToSnapshots(messages))
                    .build();
        }
        
        // 达到最大调用次数限制
        log.warn("ReAct loop reached max calls ({}) for todo: {}", maxCallsPerTodo, description);
        return TodoExecutionRecord.builder()
                .success(false)
                .output(lastOutput)
                .summary("Reached max call limit (" + maxCallsPerTodo + "), " + toolCallsUsed + " tool call(s)")
                .llmTraceId(lastLlmTraceId)
                .retryCount(retryCount)
                .toolCallsUsed(toolCallsUsed)
                .messageHistory(convertMessagesToSnapshots(messages))
                .build();
    }
    
    /**
     * 构建带重试上下文的 ReAct 消息。
     */
    private List<ChatMessage> buildMessagesWithRetryContext(String description, 
                                                             TodoExecutionContext context,
                                                             int retryCount) {
        List<ChatMessage> messages = buildMessages(description, context);
        
        // 如果是重试，在最后添加重试提示
        if (retryCount > 0) {
            StringBuilder retryHint = new StringBuilder();
            retryHint.append("\n\n");
            retryHint.append("╔══════════════════════════════════════════════════════════════╗\n");
            retryHint.append(String.format("║ ⚠️  这是第 %d 次重试                                          ║\n", retryCount));
            retryHint.append("╚══════════════════════════════════════════════════════════════╝\n\n");
            retryHint.append("之前的尝试失败了。请仔细检查：\n");
            retryHint.append("1. 工具参数名是否与 System Prompt 中的规范完全一致\n");
            retryHint.append("2. 是否遗漏了必需参数（如 executePython 的 dataset_ids）\n");
            retryHint.append("3. 数据集ID是否来自'已有数据集'列表\n\n");
            retryHint.append("如果再次失败，请参考 '_retry_hint_' 中的详细修正建议。");
            
            // 修改最后一条 UserMessage，添加重试提示
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                if (msg instanceof UserMessage) {
                    String text = ((UserMessage) msg).singleText();
                    messages.set(i, new UserMessage(text + retryHint.toString()));
                    break;
                }
            }
        }
        
        return messages;
    }
    
    /**
     * 构建带重试提示的新上下文。
     */
    private TodoExecutionContext buildRetryContext(TodoExecutionContext original, String errorHint) {
        // 复制原上下文，添加错误提示到 completedTodos
        List<CompletedTodoInfo> updatedTodos = new ArrayList<>(original.getCompletedTodos());
        
        // 构建详细的错误提示，包含正确的参数规范
        StringBuilder detailedHint = new StringBuilder();
        detailedHint.append("错误信息：").append(errorHint).append("\n\n");
        
        // 针对特定错误提供具体的修正建议
        if (errorHint.contains("dataset_ids") || errorHint.contains("MISSING_DATASET_IDS")) {
            detailedHint.append("修正建议：\n");
            detailedHint.append("1. executePython 工具需要 dataset_ids 参数，该参数是必需的\n");
            detailedHint.append("2. dataset_ids 必须使用上述'已有数据集'中的ID\n");
            detailedHint.append("3. 单数据集：dataset_ids: \"dataset_xxx\"\n");
            detailedHint.append("4. 多数据集：dataset_ids: \"dataset_xxx,dataset_yyy\"（逗号分隔）\n");
            detailedHint.append("5. 代码中需要遍历 /sandbox/input/*/ 读取数据\n\n");
            detailedHint.append("正确示例：\n");
            detailedHint.append("通过原生工具调用 executePython，并传入 dataset_ids=\"xxx\"、code=\"import pandas as pd; ...\"");
        } else if (errorHint.contains("keyword")) {
            detailedHint.append("修正建议：\n");
            detailedHint.append("搜索类工具必须使用 'keyword' 参数（不是 'keywords' 或 'query'）\n");
            detailedHint.append("正确示例：通过原生工具调用 searchIndex，并传入 keyword=\"沪深300\"");
        } else {
            detailedHint.append("修正建议：\n");
            detailedHint.append("请确保使用正确的参数名，参考 System Prompt 中的工具规范。\n");
            detailedHint.append("特别注意 executePython 的 dataset_ids 参数是必需的！");
        }
        
        updatedTodos.add(CompletedTodoInfo.builder()
                .todoId("_retry_hint_")
                .description("⚠️ 前一次尝试失败，需要修正")
                .output(detailedHint.toString())
                .summary("请根据错误信息修正参数后重试。特别注意参数名必须完全匹配规范。")
                .build());
        
        return TodoExecutionContext.builder()
                .userGoal(original.getUserGoal())
                .availableTools(original.getAvailableTools())
                .toolSpecifications(original.getToolSpecifications())
                .completedTodos(updatedTodos)
                .datasetRefs(original.getDatasetRefs())
                .build();
    }
    
    /**
     * 从工具结果中提取 dataset_id 并注册到上下文。
     */
    private void extractAndRegisterDatasetRef(String toolResult, TodoExecutionContext context) {
        try {
            Map<String, Object> result = objectMapper.readValue(toolResult, Map.class);
            if (result.containsKey("data")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null && data.containsKey("dataset_id")) {
                    String datasetId = data.get("dataset_id").toString();
                    String path = "/sandbox/input/" + datasetId;
                    context.getDatasetRefs().put(datasetId, path);
                    log.info("Registered dataset ref: {} -> {}", datasetId, path);
                }
            }
        } catch (Exception e) {
            log.debug("No dataset_id found in tool result");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Sub-Agent 处理（#36 §4.3）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 处理 spawnSubAgent 调用：在后台线程启动 SubAgentRunner，立即返回 sub_agent_id。
     * 主 ReAct 循环可继续处理其他工具调用，不会阻塞。
     *
     * <p>参数（来自原生 tool call arguments）：
     * <ul>
     *   <li>goal（必填）：子代理目标描述</li>
     *   <li>context（可选）：补充上下文信息</li>
     * </ul>
     */
    private String handleSpawnSubAgent(Map<String, Object> params,
                                       TodoExecutionContext context,
                                       String runId,
                                       ChatModel model,
                                       Map<String, Future<SubAgentRunner.SubAgentResult>> pendingSubAgents,
                                       AtomicInteger subAgentIdCounter) {
        if (subAgentRunner == null) {
            log.warn("spawnSubAgent called but SubAgentRunner is not available");
            return "{\"ok\":false,\"error\":\"sub_agent_not_available\"}";
        }
        // 检查并发上限
        int maxCount = promptService.maxSubAgentCount();
        if (pendingSubAgents.size() >= maxCount) {
            log.warn("spawnSubAgent rejected: reached max concurrent sub-agents ({})", maxCount);
            return "{\"ok\":false,\"error\":\"max_sub_agents_exceeded\",\"max\":" + maxCount + "}";
        }
        String goal = params != null ? String.valueOf(params.getOrDefault("goal", "")) : "";
        if (goal.isBlank()) {
            return "{\"ok\":false,\"error\":\"goal parameter is required for spawnSubAgent\"}";
        }
        String subCtx = params != null ? String.valueOf(params.getOrDefault("context", "")) : "";
        String subAgentId = "sa_" + subAgentIdCounter.getAndIncrement();

        // Sub-Agent 只能使用业务工具，不能递归启动 Sub-Agent
        Set<String> subAgentTools = new HashSet<>(context.getAvailableTools());
        subAgentTools.remove("spawnSubAgent");
        subAgentTools.remove("waitForSubAgent");
        String subAgentEndpoint = promptService.subAgentEndpointName();
        String subAgentModel = promptService.selectSubAgentModelName(goal, subCtx);

        SubAgentRunner.SubAgentRequest req = SubAgentRunner.SubAgentRequest.builder()
                .runId(runId != null ? runId : "")
                .taskId(subAgentId)
                .goal(goal)
                .context(subCtx.isBlank() ? null : subCtx)
                .toolWhitelist(subAgentTools)
                .maxSteps(10)
                .endpointName(subAgentEndpoint)
                .endpointBaseUrl("")
                .modelName(subAgentModel)
                .build();

        Future<SubAgentRunner.SubAgentResult> future = subAgentExecutor.submit(
                () -> subAgentRunner.run(req, model));
        pendingSubAgents.put(subAgentId, future);

        log.info("Sub-agent spawned: id={}, goal={}", subAgentId, goal);
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "ok", true,
                    "sub_agent_id", subAgentId,
                    "status", "spawned",
                    "goal", goal
            ));
        } catch (Exception e) {
            return "{\"ok\":true,\"sub_agent_id\":\"" + subAgentId + "\",\"status\":\"spawned\"}";
        }
    }

    /**
     * 处理 waitForSubAgent 调用：阻塞等待一个或多个 Sub-Agent 完成，返回聚合结果。
     *
     * <p>支持两种参数格式（可选地同时等待多个）：
     * <ul>
     *   <li>{@code sub_agent_ids}（优先）：逗号分隔的 ID 列表，如 "sa_0,sa_1"</li>
     *   <li>{@code sub_agent_id}（向后兼容）：单个 ID</li>
     * </ul>
     * LLM 可通过思维能力判断何时等待，支持同时汇总多个子代理的结果。
     */
    private String handleWaitForSubAgent(Map<String, Object> params,
                                          Map<String, Future<SubAgentRunner.SubAgentResult>> pendingSubAgents) {
        // 解析 ID 列表：优先 sub_agent_ids（多个），fallback sub_agent_id（单个）
        List<String> ids = new ArrayList<>();
        Object idsParam = params != null ? params.get("sub_agent_ids") : null;
        if (idsParam != null) {
            if (idsParam instanceof List) {
                ((List<?>) idsParam).forEach(id -> {
                    String s = id != null ? id.toString().trim() : "";
                    if (!s.isEmpty()) ids.add(s);
                });
            } else {
                for (String part : idsParam.toString().split(",")) {
                    String s = part.trim();
                    if (!s.isEmpty()) ids.add(s);
                }
            }
        }
        if (ids.isEmpty()) {
            String singleId = params != null ? String.valueOf(params.getOrDefault("sub_agent_id", "")) : "";
            if (!singleId.isBlank()) ids.add(singleId.trim());
        }
        if (ids.isEmpty()) {
            return "{\"ok\":false,\"error\":\"sub_agent_id or sub_agent_ids parameter is required for waitForSubAgent\"}";
        }

        // 并发等待每个 sub-agent 完成，最后按输入顺序聚合结果
        Map<String, Object> agentResults = new LinkedHashMap<>();
        Map<String, CompletableFuture<Map<String, Object>>> waitFutures = new LinkedHashMap<>();
        boolean interrupted = false;

        for (String id : ids) {
            Future<SubAgentRunner.SubAgentResult> future = pendingSubAgents.get(id);
            if (future == null) {
                agentResults.put(id, Map.of("ok", false, "error", "unknown sub_agent_id: " + id));
                continue;
            }
            CompletableFuture<Map<String, Object>> waitFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    SubAgentRunner.SubAgentResult result = future.get(subAgentTimeoutSeconds, TimeUnit.SECONDS);
                    log.info("Sub-agent completed: id={}, success={}", id, result.isSuccess());
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ok", result.isSuccess());
                    r.put("answer", result.getAnswer() != null ? result.getAnswer() : "");
                    if (!result.isSuccess() && result.getError() != null) {
                        r.put("error", result.getError());
                    }
                    return r;
                } catch (TimeoutException e) {
                    log.warn("Sub-agent {} timed out after {}s", id, subAgentTimeoutSeconds);
                    return Map.of("ok", false, "error", "sub_agent_timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Map.of("ok", false, "error", "interrupted");
                } catch (Exception e) {
                    log.error("Failed to get sub-agent result: {}", id, e);
                    return Map.of("ok", false, "error", e.getMessage() != null ? e.getMessage() : "unknown");
                }
            }, subAgentExecutor);
            waitFutures.put(id, waitFuture);
        }

        boolean allSuccess = true;
        for (String id : ids) {
            if (agentResults.containsKey(id)) {
                Object existing = agentResults.get(id);
                if (existing instanceof Map<?, ?> map && !Boolean.TRUE.equals(map.get("ok"))) {
                    allSuccess = false;
                }
                continue;
            }
            CompletableFuture<Map<String, Object>> waitFuture = waitFutures.get(id);
            if (waitFuture == null) {
                agentResults.put(id, Map.of("ok", false, "error", "unknown sub_agent_id: " + id));
                allSuccess = false;
                continue;
            }
            try {
                Map<String, Object> resultMap = waitFuture.join();
                agentResults.put(id, resultMap);
                if (!Boolean.TRUE.equals(resultMap.get("ok"))) {
                    allSuccess = false;
                }
                if ("interrupted".equals(resultMap.get("error"))) {
                    interrupted = true;
                    break;
                }
            } catch (Exception e) {
                agentResults.put(id, Map.of("ok", false, "error", e.getMessage() != null ? e.getMessage() : "unknown"));
                allSuccess = false;
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", allSuccess);
        response.put("results", agentResults);
        // 向后兼容：单 ID 时在顶层也输出 sub_agent_id + answer
        if (ids.size() == 1 && !interrupted) {
            String singleId = ids.get(0);
            response.put("sub_agent_id", singleId);
            @SuppressWarnings("unchecked")
            Map<String, Object> sr = (Map<String, Object>) agentResults.get(singleId);
            if (sr != null) {
                response.put("answer", sr.getOrDefault("answer", ""));
            }
        }
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"ok\":" + allSuccess + ",\"results\":{}}";
        }
    }

    /**
     * 从工具输出中提取错误提示。
     */
    private String extractErrorHint(String output) {
        try {
            Map<String, Object> result = objectMapper.readValue(output, Map.class);
            Map<String, Object> error = (Map<String, Object>) result.get("error");
            if (error != null) {
                String message = (String) error.get("message");
                String code = (String) error.get("code");
                if (code != null && code.equals("NO_DATA") && message != null 
                        && message.contains("keyword")) {
                    return "Invalid keyword parameter. Use 'keyword' not 'keywords' or 'query'.";
                }
                return message != null ? message : code;
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return output;
    }
    
    private Map<String, Object> buildRequestSnapshot(List<ChatMessage> messages, String description) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("stage", "dag_node_decision");
        snapshot.put("description", description);
        snapshot.put("messageCount", messages.size());
        
        // 序列化完整的 messages 数组（包含 role 和 content）
        List<Map<String, Object>> messageList = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, Object> msgMap = new HashMap<>();
            if (msg instanceof SystemMessage) {
                msgMap.put("role", "system");
                msgMap.put("content", ((SystemMessage) msg).text());
            } else if (msg instanceof UserMessage) {
                msgMap.put("role", "user");
                msgMap.put("content", ((UserMessage) msg).singleText());
            } else if (msg instanceof AiMessage) {
                msgMap.put("role", "assistant");
                msgMap.put("content", ((AiMessage) msg).text());
                if (((AiMessage) msg).toolExecutionRequests() != null && !((AiMessage) msg).toolExecutionRequests().isEmpty()) {
                    msgMap.put("tool_calls", ((AiMessage) msg).toolExecutionRequests().stream()
                            .map(call -> Map.of(
                                    "id", nvl(call.id()),
                                    "name", nvl(call.name()),
                                    "arguments", nvl(call.arguments())
                            ))
                            .toList());
                }
            } else if (msg instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage toolMessage = (ToolExecutionResultMessage) msg;
                msgMap.put("role", "tool");
                msgMap.put("content", toolMessage.text());
                msgMap.put("tool_name", nvl(toolMessage.toolName()));
                msgMap.put("tool_call_id", nvl(toolMessage.id()));
            } else {
                msgMap.put("role", "unknown");
                msgMap.put("content", msg.toString());
            }
            messageList.add(msgMap);
        }
        snapshot.put("messages", messageList);
        
        return snapshot;
    }

    private List<ChatMessage> buildMessages(String description, TodoExecutionContext context) {
        List<ChatMessage> messages = new ArrayList<>();
        
        // System Prompt - 必须完全静态，以最大化 KV 前缀缓存命中率。
        // 动态内容（用户目标、可用工具等）统一放到第一条 UserMessage，不放 SystemMessage。
        messages.add(new SystemMessage(promptService.dagReactSystemPrompt()));
        
        // 第一条 UserMessage：动态上下文前缀 + 用户目标 + 可用工具（每次 run 会变化的内容）
        StringBuilder dynamicCtx = new StringBuilder();
        dynamicCtx.append(promptService.dynamicContextPrefix()).append("\n\n");
        dynamicCtx.append("用户目标：").append(context.getUserGoal()).append("\n\n");
        Set<String> availableToolNames = resolveAvailableToolNames(context);
        if (!availableToolNames.isEmpty()) {
            dynamicCtx.append("当前可用工具：").append(String.join(", ", availableToolNames)).append("\n");
        }
        messages.add(new UserMessage(dynamicCtx.toString()));
        
        // 历史完成的 Todo：优先使用完整消息历史（CoT）
        for (CompletedTodoInfo todo : context.getCompletedTodos()) {
            if (todo.getMessageHistory() != null && !todo.getMessageHistory().isEmpty()) {
                // 使用完整的消息历史还原对话上下文
                log.debug("Restoring message history for todo {}: {} messages", todo.getTodoId(), todo.getMessageHistory().size());
                messages.addAll(restoreMessagesFromSnapshots(todo.getMessageHistory()));
            } else {
                // 回退到 summary 模式（向后兼容）
                messages.add(new UserMessage(String.format(
                        "已完成: %s\n结果: %s",
                        todo.getDescription(),
                        todo.getSummary()
                )));
            }
        }
        
        // 当前任务
        StringBuilder userMsg = new StringBuilder();
        userMsg.append("当前任务: ").append(description).append("\n\n");
        
        if (!context.getDatasetRefs().isEmpty()) {
            userMsg.append("已有数据集 (可用于 dataset_ids 参数):\n");
            context.getDatasetRefs().forEach((id, path) -> 
                    userMsg.append(String.format("  - %s\n", id)));
            userMsg.append("\n");
            userMsg.append("⚠️ 注意：如果调用 executePython，必须将上述 dataset ID 通过 dataset_ids 参数传入！\n\n");
        }
        
        userMsg.append("请决定如何完成。\n");
        userMsg.append("需要调用工具时请直接使用系统提供的工具调用能力，不要手写 JSON。\n");
        userMsg.append("如果当前任务要求调用工具，下一条消息必须是实际工具调用；不要只说明计划或展示参数。\n");
        userMsg.append("无需工具时，请直接输出最终回答内容。\n");
        userMsg.append("⚠️ 警告：工具参数名必须与工具规范完全一致。");
        
        messages.add(new UserMessage(userMsg.toString()));
        
        return messages;
    }
    
    /**
     * 将 ChatMessage 列表转换为 ChatMessageSnapshot 列表，用于保存和传递。
     * 
     * @param messages 消息列表
     * @return 消息快照列表
     */
    private List<CompletedTodoInfo.ChatMessageSnapshot> convertMessagesToSnapshots(List<ChatMessage> messages) {
        List<CompletedTodoInfo.ChatMessageSnapshot> snapshots = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return snapshots;
        }
        
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                snapshots.add(CompletedTodoInfo.ChatMessageSnapshot.builder()
                        .role("system")
                        .content(((SystemMessage) message).text())
                        .build());
            } else if (message instanceof UserMessage) {
                snapshots.add(CompletedTodoInfo.ChatMessageSnapshot.builder()
                        .role("user")
                        .content(((UserMessage) message).singleText())
                        .build());
            } else if (message instanceof AiMessage) {
                AiMessage aiMsg = (AiMessage) message;
                List<CompletedTodoInfo.ToolCallSnapshot> toolCalls = null;
                
                // 保存工具调用请求
                if (aiMsg.toolExecutionRequests() != null && !aiMsg.toolExecutionRequests().isEmpty()) {
                    toolCalls = aiMsg.toolExecutionRequests().stream()
                            .map(req -> CompletedTodoInfo.ToolCallSnapshot.builder()
                                    .id(req.id())
                                    .name(req.name())
                                    .arguments(req.arguments())
                                    .build())
                            .toList();
                }
                
                snapshots.add(CompletedTodoInfo.ChatMessageSnapshot.builder()
                        .role("assistant")
                        .content(aiMsg.text())
                        .toolCalls(toolCalls)
                        .build());
            } else if (message instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage toolMsg = (ToolExecutionResultMessage) message;
                snapshots.add(CompletedTodoInfo.ChatMessageSnapshot.builder()
                        .role("tool")
                        .content(toolMsg.text())
                        .toolName(toolMsg.toolName())
                        .toolCallId(toolMsg.id())
                        .build());
            }
        }
        
        return snapshots;
    }
    
    /**
     * 从 ChatMessageSnapshot 列表还原 ChatMessage 列表，用于重建对话上下文。
     * 
     * @param snapshots 消息快照列表
     * @return 消息列表
     */
    private List<ChatMessage> restoreMessagesFromSnapshots(List<CompletedTodoInfo.ChatMessageSnapshot> snapshots) {
        List<ChatMessage> messages = new ArrayList<>();
        if (snapshots == null || snapshots.isEmpty()) {
            return messages;
        }
        
        // 跳过开头的 SystemMessage，因为会在 buildMessages 中重新添加
        boolean skippedSystem = false;
        
        for (CompletedTodoInfo.ChatMessageSnapshot snapshot : snapshots) {
            String role = snapshot.getRole();
            String content = snapshot.getContent();
            
            if ("system".equals(role)) {
                // 跳过 SystemMessage，避免重复
                if (!skippedSystem) {
                    skippedSystem = true;
                    continue;
                }
                messages.add(new SystemMessage(content));
            } else if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                // 还原工具调用请求
                if (snapshot.getToolCalls() != null && !snapshot.getToolCalls().isEmpty()) {
                    List<ToolExecutionRequest> toolRequests = snapshot.getToolCalls().stream()
                            .map(tc -> ToolExecutionRequest.builder()
                                    .id(tc.getId())
                                    .name(tc.getName())
                                    .arguments(tc.getArguments())
                                    .build())
                            .toList();
                    messages.add(AiMessage.builder()
                            .text(content)
                            .toolExecutionRequests(toolRequests)
                            .build());
                } else {
                    messages.add(AiMessage.from(content));
                }
            } else if ("tool".equals(role)) {
                messages.add(ToolExecutionResultMessage.builder()
                        .id(snapshot.getToolCallId())
                        .toolName(snapshot.getToolName())
                        .text(content)
                        .build());
            }
        }
        
        return messages;
    }
    
    /**
     * 获取工具的参数规范说明（用于错误提示和日志）。
     * 注意：System Prompt 中的规范来自 promptService.dagReactSystemPrompt() 配置文件。
     */
    private String getToolParamSpec(String toolName) {
        return switch (toolName) {
            case "searchIndex" -> "{\"keyword\": \"<搜索关键词>\"}";
            case "searchStock" -> "{\"keyword\": \"<搜索关键词>\"}";
            case "searchFund" -> "{\"keyword\": \"<搜索关键词>\"}";
            case "getIndexDaily" -> "{\"ts_code\": \"<指数代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}";
            case "getStockDaily" -> "{\"ts_code\": \"<股票代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}";
            case "getFundDaily" -> "{\"ts_code\": \"<基金代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}";
            case "getIndexInfo" -> "{\"ts_code\": \"<指数代码>\"}";
            case "getStockInfo" -> "{\"ts_code\": \"<股票代码>\"}";
            case "executePython" -> "{\"code\": \"<Python代码>\", \"dataset_ids\": \"<必需：数据集ID，逗号分隔>\", \"libraries\": \"<可选：库名逗号分隔>\"}";
            case "searchWeb" -> "{\"query\": \"<搜索查询文本>\", \"scene\": \"general|finance|news\", \"backend\": \"perplexity|tavily|exa|\", \"strength\": \"<可选>\", \"skipHotCache\": true, \"skipRagPrefetch\": true, \"timeRangeStart\": \"\", \"timeRangeEnd\": \"\", \"maxResults\": 5}";
            default -> "{...}";
        };
    }

    private Set<String> resolveAvailableToolNames(TodoExecutionContext context) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (context.getAvailableTools() != null && !context.getAvailableTools().isEmpty()) {
            names.addAll(context.getAvailableTools());
        }
        if (context.getToolSpecifications() != null && !context.getToolSpecifications().isEmpty()) {
            names.addAll(context.getToolSpecifications().stream()
                    .map(ToolSpecification::name)
                    .filter(name -> name != null && !name.isBlank())
                    .toList());
        }
        return names;
    }

    private ChatResponse chatWithTools(ChatModel model, List<ChatMessage> messages, TodoExecutionContext context) {
        List<ToolSpecification> specs = resolveToolSpecifications(context);
        if (specs.isEmpty()) {
            return model.chat(messages);
        }
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(specs)
                .build();
        return model.chat(request);
    }

    private List<ToolSpecification> resolveToolSpecifications(TodoExecutionContext context) {
        LinkedHashMap<String, ToolSpecification> specMap = new LinkedHashMap<>();
        if (context.getToolSpecifications() != null) {
            for (ToolSpecification specification : context.getToolSpecifications()) {
                if (specification == null || specification.name() == null || specification.name().isBlank()) {
                    continue;
                }
                specMap.put(specification.name(), specification);
            }
        }

        // Sub-Agent 两个工具在执行器内部实现，需要手动补充 schema。
        buildSubAgentToolSpecifications().forEach(spec -> specMap.putIfAbsent(spec.name(), spec));

        Set<String> allowList = resolveAvailableToolNames(context);
        if (allowList.isEmpty()) {
            return new ArrayList<>(specMap.values());
        }
        return specMap.values().stream()
                .filter(spec -> allowList.contains(spec.name()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<ToolSpecification> buildSubAgentToolSpecifications() {
        ToolSpecification spawnSpec = ToolSpecification.builder()
                .name("spawnSubAgent")
                .description("后台启动一个子代理执行目标，立即返回 sub_agent_id。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("goal", "子代理目标描述")
                        .addStringProperty("context", "可选补充上下文")
                        .required("goal")
                        .additionalProperties(false)
                        .build())
                .build();

        ToolSpecification waitSpec = ToolSpecification.builder()
                .name("waitForSubAgent")
                .description("等待一个或多个子代理完成并返回结果。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("sub_agent_ids", "逗号分隔的子代理ID列表，优先使用该字段")
                        .addStringProperty("sub_agent_id", "单个子代理ID（向后兼容）")
                        .additionalProperties(false)
                        .build())
                .build();

        return List.of(spawnSpec, waitSpec);
    }

    private ToolExecutionOutcome executeToolRequest(ToolExecutionRequest toolRequest,
                                                    TodoExecutionContext context,
                                                    String runId,
                                                    String phase,
                                                    ChatModel model,
                                                    Map<String, Future<SubAgentRunner.SubAgentResult>> pendingSubAgents,
                                                    AtomicInteger subAgentIdCounter) {
        long toolStartTime = System.currentTimeMillis();
        String toolName = nvl(toolRequest.name());
        Map<String, Object> params = parseToolArguments(toolRequest.arguments());
        if (!context.getDatasetRefs().isEmpty()) {
            params.put("_dataset_refs", context.getDatasetRefs());
        }

        try {
            String result;
            boolean success;
            if ("spawnSubAgent".equals(toolName)) {
                result = handleSpawnSubAgent(params, context, runId, model, pendingSubAgents, subAgentIdCounter);
                success = !result.contains("\"ok\":false");
                recordToolCallObservability(runId, phase, toolName, params, result,
                        System.currentTimeMillis() - toolStartTime, success, success ? null : result);
            } else if ("waitForSubAgent".equals(toolName)) {
                result = handleWaitForSubAgent(params, pendingSubAgents);
                success = !result.contains("\"ok\":false");
                recordToolCallObservability(runId, phase, toolName, params, result,
                        System.currentTimeMillis() - toolStartTime, success, success ? null : result);
            } else {
                ToolRouter.ToolInvocationResult invokeResult = toolRouter.invokeWithMeta(toolName, params);
                result = nvl(invokeResult.getOutput());
                success = invokeResult.isSuccess();
            }
            return new ToolExecutionOutcome(result, success);
        } catch (Exception e) {
            long toolDurationMs = System.currentTimeMillis() - toolStartTime;
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String result = "{\"ok\":false,\"error\":{\"code\":\"TOOL_EXECUTION_EXCEPTION\",\"message\":\"" + err + "\"}}";
            recordToolCallObservability(runId, phase, toolName, params, result, toolDurationMs, false, err);
            return new ToolExecutionOutcome(result, false);
        }
    }

    private Map<String, Object> parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(arguments, Map.class);
            return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments as JSON, fallback empty map: {}", trimToolArguments(arguments));
            return new HashMap<>();
        }
    }

    private String trimToolArguments(String arguments) {
        if (arguments == null) {
            return "";
        }
        String normalized = arguments.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private void recordToolCallObservability(String runId, String phase, String toolName,
                                              Map<String, Object> params, String output,
                                              long durationMs, boolean success, String errorMessage) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        try {
            observabilityService.recordToolCall(
                    runId,
                    phase != null ? phase : "dag_execution",
                    toolName,
                    params,
                    output,
                    durationMs,
                    success,
                    false, // cacheEligible
                    false, // cacheHit
                    null,  // cacheKey
                    null,  // cacheSource
                    0L,    // cacheTtlRemainingMs
                    0L,    // estimatedSavedDurationMs
                    errorMessage
            );
        } catch (Exception e) {
            log.warn("Failed to record tool call observability: {}", e.getMessage());
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    @Builder
    @Data
    public static class TodoExecutionContext {
        private String userGoal;
        private Set<String> availableTools;
        private List<ToolSpecification> toolSpecifications;
        private List<CompletedTodoInfo> completedTodos;
        private Map<String, String> datasetRefs;
    }

    private record ToolExecutionOutcome(String result, boolean success) {
    }

    @Builder
    @Data
    public static class TodoExecutionRecord {
        private boolean success;
        private String output;
        private String summary;
        
        // 观测数据字段
        private String llmTraceId;
        private String toolName;
        private Map<String, Object> toolParams;
        private Long toolDurationMs;
        private Instant startedAt;
        private Instant completedAt;
        
        // ReAct 循环统计
        @Builder.Default
        private int toolCallsUsed = 0;
        
        // 重试相关
        @Builder.Default
        private int retryCount = 0;
        
        // CoT：完整的消息历史，用于传递给后续 Todo
        @Builder.Default
        private List<CompletedTodoInfo.ChatMessageSnapshot> messageHistory = new ArrayList<>();
    }
}
