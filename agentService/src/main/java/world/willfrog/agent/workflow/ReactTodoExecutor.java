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
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.graph.SubAgentRunner;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.tools.router.ToolRouter;

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
 * ReAct 模式的单个 Todo 执行器，是 Agent 执行流程中最核心的"推理-行动"循环实现。
 *
 * <h3>位置与角色</h3>
 * 本执行器是 {@link LinearWorkflowExecutor} 和 {@link DagWorkflowExecutor} 的共同底层依赖。
 * 两个上层执行器负责"如何调度 Todo 的先后顺序和并行关系"，本执行器负责"单个 Todo 内部怎么执行"。
 *
 * <h3>ReAct 循环（{@link #executeReActLoop}）</h3>
 * 每个 Todo 内部运行一个多轮 ReAct（Reasoning + Acting）循环：
 * <pre>
 * [SystemMessage: dagReactSystemPrompt + 上下文]
 * [UserMessage: 当前任务描述 + 已有数据集列表]
 * ^[LLM 决策 → 返回 tool_calls]         ← Round 1
 * [ToolExecutionResultMessage: 工具 A 的结果]
 * ^[LLM 分析结果 → 返回 tool_calls]     ← Round 2
 * [ToolExecutionResultMessage: 工具 B 的结果]
 * ^[LLM 最终决策 → 返回纯文本 answer]   ← Round 3
 * </pre>
 * 循环终止条件：LLM 返回不含 tool_calls 的纯文本消息，或达到 {@code maxCallsPerTodo} 上限。
 *
 * <h3>重试机制（{@link #executeWithRetry}）</h3>
 * 重试的粒度是<strong>整个 Todo 的 ReAct 循环</strong>（最多 2 次额外重试，即总共最多 3 次）。
 * 第一次失败后，会构建带错误提示的新上下文让 LLM 修正参数后重新执行。
 *
 * <h3>Sub-Agent 机制</h3>
 * LLM 可调用 {@code spawnSubAgent} 在后台线程启动子代理执行独立任务；
 * 主线程继续处理其他 ReAct 轮次，不阻塞。之后通过 {@code waitForSubAgent} 等待并汇总结果。
 * 子代理只能使用业务工具（搜索、行情、Python 等），不能递归启动新的子代理。
 *
 * <h3>工具调用方式</h3>
 * 只接受 LLM 原生 tool_calls（LangChain4j {@link ToolExecutionRequest} 协议）。
 * 不再解析正文中的 JSON 代码块作为工具调用指令。
 *
 * @see LinearWorkflowExecutor
 * @see DagWorkflowExecutor
 * @see ToolRouter
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReactTodoExecutor {

    /** 提示词服务，提供各阶段的 System Prompt 和辅助提示文本 */
    private final AgentPromptService promptService;
    /** 工具路由器，根据 toolName 路由到具体工具实现并执行 */
    private final ToolRouter toolRouter;
    /** JSON 序列化/反序列化，用于解析工具参数和构建响应 */
    private final ObjectMapper objectMapper;
    /** 观测数据服务，记录每次 LLM 调用和工具调用的 trace */
    private final AgentObservabilityService observabilityService;

    @Autowired(required = false)
    @Setter
    private AgentEventService eventService;

    /**
     * SubAgentRunner — 可选注入（@Autowired(required = false)），
     * 避免在没有完整 Spring 上下文的单元测试中出错。
     * 若为 null，则 spawnSubAgent 调用返回不可用提示而非抛异常。
     */
    @Autowired(required = false)
    @Setter
    private SubAgentRunner subAgentRunner;

    /**
     * Sub-Agent 后台执行线程池。
     * 使用守护线程（daemon），JVM 退出时自动关闭，不阻止进程终止。
     * CachedThreadPool：按需创建线程，空闲 60 秒后回收。
     */
    private final ExecutorService subAgentExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sub-agent-worker");
        t.setDaemon(true);
        return t;
    });

    /** 单个 Sub-Agent 等待超时秒数（默认 120 秒） */
    @Value("${agent.flow.react.sub-agent-timeout-seconds:120}")
    private int subAgentTimeoutSeconds;

    /** 单个 Todo 内 ReAct 循环的最大 LLM 调用次数（默认 10 次） */
    @Value("${agent.flow.react.max-calls-per-todo:10}")
    private int maxCallsPerTodo;

    /** 同一 Todo 内同一失败工具调用允许重复出现的最大次数。 */
    @Value("${agent.flow.react.max-same-failed-tool-call-per-todo:1}")
    private int maxSameFailedToolCallPerTodo;

    /** 已完成 Todo 注入到后续 Todo 的上下文模式：summary / full。 */
    @Value("${agent.flow.react.completed-todo-context-mode:summary}")
    private String completedTodoContextMode;

    /** Run 级 Redis 状态缓存，用于在执行循环中检查 run 是否被用户取消 */
    private final AgentRunStateStore stateStore;

    /**
     * Spring Bean 销毁回调：优雅关闭 Sub-Agent 线程池。
     *
     * <p>首先尝试正常关闭并等待 30 秒，超时后强制 shutdownNow。
     * 若等待期间被中断，同样强制关闭并恢复中断标志。</p>
     */
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

    /**
     * 执行单个 Todo（不含观测记录和重试包装，向后兼容的简化入口）。
     *
     * @param description 任务描述
     * @param context     执行上下文（用户目标、可用工具、已完成 Todo 列表等）
     * @param model       Execution 阶段的 ChatModel
     * @return 执行结果记录
     * @deprecated 新调用方应使用 {@link #executeWithObservability}
     */
    @Deprecated
    public TodoExecutionRecord execute(String description, TodoExecutionContext context, ChatModel model) {
        return executeWithObservability(description, context, model, null, null);
    }

    /**
     * 执行单个 Todo，并记录完整的可观测性数据（LLM 调用、工具调用 trace）。
     *
     * <p>这是外部执行器（LinearWorkflowExecutor、DagWorkflowExecutor）调用的主入口。
     * 内部通过 {@link #executeWithRetry} 包裹重试逻辑。</p>
     *
     * @param description 任务描述
     * @param context     执行上下文
     * @param model       LLM ChatModel
     * @param runId       Run ID，用于关联观测记录（可为 null，此时不记录观测数据）
     * @param phase       执行阶段名（如 "linear_execution"、"dag_execution_todo_1"），用于观测归类
     * @return 执行结果记录
     */
    public TodoExecutionRecord executeWithObservability(String description,
                                                         TodoExecutionContext context,
                                                         ChatModel model,
                                                         String runId,
                                                         String phase) {
        return executeWithRetry(description, context, model, runId, phase, 0);
    }

    /**
     * 带重试机制的 Todo 执行。
     *
     * <p>重试策略：</p>
     * <ul>
     *   <li>最多额外重试 2 次（即总共最多执行 3 次 ReAct 循环）。</li>
     *   <li>每次重试前，从失败记录的 output 中提取错误原因，构建带修正建议的新上下文。</li>
     *   <li>重试的粒度是整个 ReAct 循环，不是单个工具调用。</li>
     *   <li>未预期异常也会触发重试（与 ReAct 执行失败同等对待）。</li>
     * </ul>
     *
     * @param description 任务描述
     * @param context     执行上下文
     * @param model       LLM ChatModel
     * @param runId       Run ID
     * @param phase       执行阶段名
     * @param retryCount  当前重试次数（首次调用传入 0）
     * @return 执行结果记录
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

            // 如果 ReAct 循环失败且仍有重试配额，进行重试
            if (!record.isSuccess() && retryCount < MAX_RETRIES && !isConvergenceStop(record)) {
                String errorHint = extractErrorHint(record.getOutput());
                log.warn("Todo execution failed, will retry {}/{}: {}, error: {}",
                        retryCount + 1, MAX_RETRIES, description, errorHint);

                // 构建带针对性错误修正建议的新上下文
                TodoExecutionContext retryContext = buildRetryContext(context, errorHint);

                return executeWithRetry(description, retryContext, model, runId, phase, retryCount + 1);
            }

            return record;

        } catch (Exception e) {
            log.error("Failed to execute todo: {}", description, e);

            // 异常时同样尝试重试（如网络超时等瞬时错误）
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
     * 多轮 ReAct 循环的核心实现。
     *
     * <p>在单个 Todo 内持续调用 LLM 和工具，直到 LLM 输出纯文本 answer 或达到上限。
     * 每一轮中：</p>
     * <ol>
     *   <li>检查 run 是否已被取消。</li>
     *   <li>调用 LLM（携带当前完整的消息历史和工具定义）。</li>
     *   <li>记录 LLM 调用到观测数据。</li>
     *   <li>若 LLM 返回 tool_calls：依次执行每个工具，将结果以
     *       {@link ToolExecutionResultMessage} 形式追加到消息历史，
     *       然后回到步骤 1 继续下一轮。</li>
     *   <li>若 LLM 返回纯文本（无 tool_calls）：视为任务完成，返回成功结果。</li>
     * </ol>
     *
     * @param description 任务描述
     * @param context     执行上下文
     * @param model       LLM ChatModel
     * @param runId       Run ID（用于 run 取消检查和观测记录）
     * @param phase       执行阶段名
     * @param retryCount  当前重试次数（用于在消息中标记重试提示）
     * @return 执行结果记录
     */
    private TodoExecutionRecord executeReActLoop(String description,
                                                  TodoExecutionContext context,
                                                  ChatModel model,
                                                  String runId,
                                                  String phase,
                                                  int retryCount) {
        // 构建初始消息列表：System Prompt + 动态上下文 + 用户目标 + 当前任务
        // 如果是重试（retryCount > 0），会在第一条 UserMessage 末尾附加重试提示
        List<ChatMessage> messages = buildMessagesWithRetryContext(description, context, retryCount);

        int callCount = 0;
        int toolCallsUsed = 0;
        boolean truncatedRetryInjected = false;
        String lastLlmTraceId = null;
        String lastOutput = "";
        Map<String, Integer> failedToolCallCounts = new HashMap<>();

        // Sub-Agent 追踪：key=sub_agent_id, value=后台执行的 Future
        Map<String, Future<SubAgentRunner.SubAgentResult>> pendingSubAgents = new HashMap<>();
        AtomicInteger subAgentIdCounter = new AtomicInteger(0);

        while (callCount < maxCallsPerTodo) {
            // ── 每轮开始前检查 run 是否被用户取消 ──
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

            // 调用 LLM 决策（携带工具定义，LLM 可选择调用工具或输出纯文本）
            ChatResponse response = chatWithTools(model, requestMessages, context);
            AiMessage aiMessage = response.aiMessage();
            String llmOutput = aiMessage != null && aiMessage.text() != null ? aiMessage.text() : "";
            long llmDurationMs = System.currentTimeMillis() - llmStartTime;

            // 将 LLM 的响应消息加入对话历史（后续轮次 LLM 会看到此消息）
            // 优先保留原生 AiMessage（含 tool_calls 结构），否则从文本构造
            if (aiMessage != null) {
                messages.add(aiMessage);
            } else if (!llmOutput.isBlank()) {
                messages.add(AiMessage.from(llmOutput));
            }

            // 记录 LLM 调用 trace（用于观测分析和调试）
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
            emitOutputIntegrityEvent(runId, response, phase);

            // 读取 LLM 返回的 tool_calls（LangChain4j 原生协议）
            List<ToolExecutionRequest> toolRequests = aiMessage == null || aiMessage.toolExecutionRequests() == null
                    ? List.of()
                    : aiMessage.toolExecutionRequests();

            // ── LLM 决定调用工具 ──
            if (!toolRequests.isEmpty()) {
                for (ToolExecutionRequest toolRequest : toolRequests) {
                    // 将本次工具调用的上下文绑定到 AgentContext，以便观测层在 tool 链路中关联 LLM 决策信息
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
                    DatasetRefExtractor.registerFromJson(outcome.result(), context.getDatasetRefs());
                    // 工具结果以 ToolExecutionResultMessage 形式追加到消息历史
                    messages.add(ToolExecutionResultMessage.builder()
                            .id(toolRequest.id())
                            .toolName(toolRequest.name())
                            .text(outcome.result())
                            .isError(!outcome.success())
                            .build());

                    if (!outcome.success()) {
                        log.warn("Tool {} failed in ReAct round {}, LLM will decide next step",
                                toolRequest.name(), callCount);
                        String fingerprint = toolCallFingerprint(toolRequest.name(), toolRequest.arguments());
                        int repeatedFailures = failedToolCallCounts.merge(fingerprint, 1, Integer::sum);
                        if (repeatedFailures > Math.max(1, maxSameFailedToolCallPerTodo)) {
                            String argsHash = Integer.toHexString(fingerprint.hashCode());
                            String summary = "repeated_tool_call:" + toolRequest.name() + ":" + argsHash;
                            log.warn("Stopping ReAct loop because failed tool call repeated: tool={}, count={}, hash={}",
                                    toolRequest.name(), repeatedFailures, argsHash);
                            return TodoExecutionRecord.builder()
                                    .success(false)
                                    .output(outcome.result())
                                    .summary(summary)
                                    .llmTraceId(lastLlmTraceId)
                                    .retryCount(retryCount)
                                    .toolCallsUsed(toolCallsUsed)
                                    .messageHistory(convertMessagesToSnapshots(messages))
                                    .build();
                        }
                    }
                }
                // 工具执行完毕后继续下一轮 ReAct 循环（LLM 看到工具结果后继续决策）
                continue;
            }

            // ── LLM 输出纯文本（无 tool_calls）──
            LlmResponseIntegrity.OutputIntegrityLevel integrityLevel =
                    LlmResponseIntegrity.classify(response);
            if (integrityLevel == LlmResponseIntegrity.OutputIntegrityLevel.TRUNCATED) {
                if (!truncatedRetryInjected) {
                    truncatedRetryInjected = true;
                    clearReasoningForRetry();
                    messages.add(new UserMessage(LlmResponseIntegrity.TRUNCATED_RETRY_HINT));
                    log.warn("Todo LLM response truncated with empty content; retrying once without reasoning");
                    continue;
                }
                return TodoExecutionRecord.builder()
                        .success(false)
                        .output(nvl(llmOutput))
                        .summary("output_truncated:empty_content")
                        .llmTraceId(lastLlmTraceId)
                        .retryCount(retryCount)
                        .toolCallsUsed(toolCallsUsed)
                        .messageHistory(convertMessagesToSnapshots(messages))
                        .build();
            }

            // 视为任务完成
            return TodoExecutionRecord.builder()
                    .success(true)
                    .output(nvl(llmOutput))
                    .summary("Completed in " + callCount + " round(s), " + toolCallsUsed + " tool call(s)")
                    .llmTraceId(lastLlmTraceId)
                    .retryCount(retryCount)
                    .toolCallsUsed(toolCallsUsed)
                    // 保存完整的消息历史（CoT），供后续 todo 恢复对话上下文
                    .messageHistory(convertMessagesToSnapshots(messages))
                    .build();
        }

        // 达到最大调用次数上限：ReAct 循环耗尽但未完成
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
     * 构建带重试上下文的 ReAct 消息列表，在首次执行和重试时使用不同的构建策略。
     *
     * <p>首次执行（retryCount=0）：直接调用 {@link #buildMessages} 构建标准消息。</p>
     * <p>重试时（retryCount&gt;0）：在标准消息的最后一条 UserMessage 末尾附加重试提示，
     * 包含以下引导内容：</p>
     * <ul>
     *   <li>提示这是第 N 次重试。</li>
     *   <li>提醒 LLM 检查工具参数名是否与规范完全一致。</li>
     *   <li>提醒 LLM 检查是否遗漏必需参数（如 executePython 的 dataset_ids）。</li>
     *   <li>提醒 LLM 数据集 ID 必须来自已有数据集列表。</li>
     *   <li>建议 LLM 参考上下文中的 _retry_hint_ 获取详细修正建议。</li>
     * </ul>
     *
     * @param description 任务描述
     * @param context     执行上下文
     * @param retryCount  当前重试次数
     * @return 构建好的消息列表
     */
    private List<ChatMessage> buildMessagesWithRetryContext(String description,
                                                             TodoExecutionContext context,
                                                             int retryCount) {
        List<ChatMessage> messages = buildMessages(description, context);

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

            // 找到最后一条 UserMessage，在其文本末尾附加重试提示
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
     * 基于前次失败信息构建重试上下文。
     *
     * <p>在原始执行上下文的基础上，追加一条标记为 {@code _retry_hint_} 的 CompletedTodoInfo，
     * 其内容为针对特定错误类型的修正建议：</p>
     * <ul>
     *   <li>{@code dataset_ids / MISSING_DATASET_IDS} — 提示 executePython 必须传入 dataset_ids，
     *       并给出正确示例。</li>
     *   <li>{@code keyword} — 提示搜索工具参数名应为 keyword。</li>
     *   <li>其他错误 — 给出通用建议，强调参数名必须匹配。</li>
     * </ul>
     *
     * @param original  原始执行上下文
     * @param errorHint 从前次失败中提取的错误提示文本
     * @return 带修正建议的新执行上下文
     */
    private TodoExecutionContext buildRetryContext(TodoExecutionContext original, String errorHint) {
        List<CompletedTodoInfo> updatedTodos = new ArrayList<>(original.getCompletedTodos());

        StringBuilder detailedHint = new StringBuilder();
        detailedHint.append("错误信息：").append(errorHint).append("\n\n");

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

    private boolean isConvergenceStop(TodoExecutionRecord record) {
        return record != null
                && record.getSummary() != null
                && record.getSummary().startsWith("repeated_tool_call:");
    }

    private void clearReasoningForRetry() {
        AgentContext.clearReasoningEffort();
    }

    private void emitOutputIntegrityEvent(String runId, ChatResponse response, String phase) {
        if (eventService == null || runId == null || runId.isBlank()) {
            return;
        }
        LlmResponseIntegrity.OutputIntegrityLevel level = LlmResponseIntegrity.classify(response);
        String eventType = LlmResponseIntegrity.eventType(level);
        if (eventType == null) {
            return;
        }
        String userId = AgentContext.getUserId();
        if (userId == null || userId.isBlank()) {
            return;
        }
        eventService.append(runId, userId, eventType, Map.of(
                "phase", phase != null ? phase : "dag_execution",
                "finish_reason", response.metadata() != null && response.metadata().finishReason() != null
                        ? response.metadata().finishReason().name()
                        : "unknown"
        ));
    }

    /**
     * 从工具调用结果的 JSON 中提取 dataset_id 并注册到执行上下文。
     *
     * <p>解析 JSON 路径 {@code result.data.dataset_id}，将其与沙箱路径
     * {@code /sandbox/input/<dataset_id>} 关联后存入 {@code context.datasetRefs}。
     * 后续 todo 可通过这个映射引用已生成的数据集。</p>
     *
     * @param toolResult 工具返回的 JSON 字符串
     * @param context    执行上下文
     */
    private void extractAndRegisterDatasetRef(String toolResult, TodoExecutionContext context) {
        DatasetRefExtractor.registerFromJson(toolResult, context.getDatasetRefs());
    }

    // ──────────────────────────────────────────────────────────────────
    // Sub-Agent 处理
    // ──────────────────────────────────────────────────────────────────

    /**
     * 处理 {@code spawnSubAgent} 工具调用：在后台线程启动一个 SubAgentRunner。
     *
     * <p>主 ReAct 循环可继续处理其他工具调用，不会阻塞。Sub-Agent 的生命周期管理：</p>
     * <ul>
     *   <li><b>并发上限</b>：通过 {@code promptService.maxSubAgentCount()} 控制，
     *       超过上限的 spawn 请求会被拒绝。</li>
     *   <li><b>工具白名单</b>：Sub-Agent 只能使用业务工具（搜索、行情、Python 等），
     *       {@code spawnSubAgent} 和 {@code waitForSubAgent} 被从白名单中移除。</li>
     *   <li><b>模型选择</b>：通过 {@code promptService.selectSubAgentModelName} 选择
     *       Sub-Agent 使用的模型。</li>
     *   <li><b>步数限制</b>：Sub-Agent 最多执行 10 步。</li>
     * </ul>
     *
     * @param params             工具参数（goal: 子代理目标, context: 可选补充上下文）
     * @param context            父 Todo 的执行上下文
     * @param runId              Run ID
     * @param model              父 Todo 使用的 ChatModel（传递给 SubAgentRunner）
     * @param pendingSubAgents   当前待处理的 Sub-Agent Future 映射表
     * @param subAgentIdCounter Sub-Agent ID 自增计数器
     * @return JSON 格式的结果（含 sub_agent_id 和 status="spawned"），或错误信息
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

        // 构建 Sub-Agent 工具白名单：排除 spawnSubAgent 和 waitForSubAgent
        Set<String> subAgentTools = new HashSet<>(context.getAvailableTools());
        subAgentTools.remove("spawnSubAgent");
        subAgentTools.remove("waitForSubAgent");
        // Sub-Agent 的 endpoint 和 model 由 promptService 根据目标语义智能选择
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
     * 处理 {@code waitForSubAgent} 工具调用：阻塞等待一个或多个 Sub-Agent 完成。
     *
     * <p>支持两种参数格式：</p>
     * <ul>
     *   <li>{@code sub_agent_ids}（优先）：多个 ID 的列表或逗号分隔字符串。</li>
     *   <li>{@code sub_agent_id}（向后兼容）：单个 ID 字符串。</li>
     * </ul>
     *
     * <p>等待策略：</p>
     * <ul>
     *   <li>使用 {@link CompletableFuture} 并发等待所有指定的 Sub-Agent。</li>
     *   <li>单个 Sub-Agent 有超时限制（{@code subAgentTimeoutSeconds}），
     *       超时返回 {@code sub_agent_timeout} 错误。</li>
     *   <li>按输入顺序聚合结果到 {@code results} 映射中。</li>
     *   <li>单 ID 时，顶层还会多输出 {@code sub_agent_id} 和 {@code answer} 字段
     *       （向后兼容）。</li>
     * </ul>
     *
     * @param params            工具参数
     * @param pendingSubAgents  当前待处理的 Sub-Agent Future 映射表
     * @return JSON 格式的聚合结果
     */
    private String handleWaitForSubAgent(Map<String, Object> params,
                                          Map<String, Future<SubAgentRunner.SubAgentResult>> pendingSubAgents) {
        // 解析 ID 列表：优先 sub_agent_ids（多 ID），fallback sub_agent_id（单 ID）
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

        // 并发等待每个 Sub-Agent，按输入顺序聚合结果
        Map<String, Object> agentResults = new LinkedHashMap<>();
        Map<String, CompletableFuture<Map<String, Object>>> waitFutures = new LinkedHashMap<>();
        boolean interrupted = false;

        for (String id : ids) {
            Future<SubAgentRunner.SubAgentResult> future = pendingSubAgents.get(id);
            if (future == null) {
                agentResults.put(id, Map.of("ok", false, "error", "unknown sub_agent_id: " + id));
                continue;
            }
            // 对每个 Sub-Agent 创建一个 CompletableFuture 等待其完成
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

        // 按输入顺序收集每个 Sub-Agent 的结果
        boolean allSuccess = true;
        for (String id : ids) {
            if (agentResults.containsKey(id)) {
                // 未知 ID 已在上面预处理
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
        // 向后兼容：仅一个 ID 时，在顶层额外输出 sub_agent_id 和 answer
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
     * 从工具调用输出的 JSON 中提取错误提示文本。
     *
     * <p>尝试解析 JSON 路径 {@code error.message} 或 {@code error.code}：
     * <ul>
     *   <li>若 code 为 NO_DATA 且 message 包含 "keyword"，返回参数名修正提示。</li>
     *   <li>否则返回 message 或 code 的原始文本。</li>
     * </ul>
     * 解析失败时返回原始 output 文本作为 fallback。</p>
     *
     * @param output 工具输出的 JSON 字符串
     * @return 提取的错误提示文本
     */
    private String extractErrorHint(String output) {
        try {
            Map<String, Object> result = objectMapper.readValue(output, Map.class);
            @SuppressWarnings("unchecked")
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
            // 非 JSON 输出，无法解析错误结构
        }
        return output;
    }

    /**
     * 构建 LLM 请求的快照，用于观测记录。
     *
     * <p>快照包含：阶段标签、任务描述、消息数量和完整的消息数组。
     * 每种消息类型（system/user/assistant/tool）会被序列化为
     * {@code {role, content, ...}} 格式的 Map。Assistant 消息中的
     * tool_calls 也会被展开记录。</p>
     *
     * @param messages    发送给 LLM 的完整消息列表
     * @param description 当前 Todo 描述
     * @return 可用于观测存储的快照 Map
     */
    private Map<String, Object> buildRequestSnapshot(List<ChatMessage> messages, String description) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("stage", "dag_node_decision");
        snapshot.put("description", description);
        snapshot.put("messageCount", messages.size());

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

    /**
     * 构建单个 Todo 的初始 ReAct 消息列表。
     *
     * <p>消息结构（按顺序）：</p>
     * <ol>
     *   <li><b>SystemMessage</b>：来自 {@code promptService.dagReactSystemPrompt()}，
     *       包含工具规范、ReAct 行为指南等。此消息完全静态，以最大化 KV 前缀缓存命中率。</li>
     *   <li><b>UserMessage（动态上下文）</b>：包含用户目标文本和当前可用工具名称。
     *       动态内容从 SystemMessage 中分离到此处，确保 System Prompt 可被缓存。</li>
     *   <li><b>已完成 Todo 的历史消息</b>：按顺序插入已完成 todo 的完整消息历史（CoT），
     *       以便当前 todo 的 LLM 了解之前发生了什么。若无完整历史则回退到 summary 模式。</li>
     *   <li><b>UserMessage（当前任务）</b>：当前 todo 描述 + 已有数据集列表 + 执行指引。</li>
     * </ol>
     *
     * @param description 当前任务描述
     * @param context     执行上下文
     * @return 构建好的消息列表
     */
    private List<ChatMessage> buildMessages(String description, TodoExecutionContext context) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. System Prompt — 完全静态，最大化 KV Cache 命中
        messages.add(new SystemMessage(promptService.dagReactSystemPrompt()));

        // 2. 第一条 UserMessage：动态上下文（每次 run 都不同，不放 SystemMessage）
        StringBuilder dynamicCtx = new StringBuilder();
        dynamicCtx.append(promptService.dynamicContextPrefix()).append("\n\n");
        dynamicCtx.append("用户目标：").append(context.getUserGoal()).append("\n\n");
        Set<String> availableToolNames = resolveAvailableToolNames(context);
        if (!availableToolNames.isEmpty()) {
            dynamicCtx.append("当前可用工具：").append(String.join(", ", availableToolNames)).append("\n");
        }
        messages.add(new UserMessage(dynamicCtx.toString()));

        // 3. 历史完成的 Todo：默认只传 summary/output，避免跨 todo 上下文膨胀。
        for (CompletedTodoInfo todo : context.getCompletedTodos()) {
            if (shouldRestoreFullHistory(description, todo)) {
                // 还原对话上下文（含 LLM 的 CoT 推理和工具调用历史）
                log.debug("Restoring message history for todo {}: {} messages", todo.getTodoId(), todo.getMessageHistory().size());
                messages.addAll(restoreMessagesFromSnapshots(todo.getMessageHistory()));
            } else {
                messages.add(new UserMessage(String.format(
                        "已完成: %s\n摘要: %s\n输出: %s",
                        todo.getDescription(),
                        nvl(todo.getSummary()),
                        nvl(todo.getOutput())
                )));
            }
        }

        // 4. 当前任务提示
        StringBuilder userMsg = new StringBuilder();
        userMsg.append("当前任务: ").append(description).append("\n\n");

        // 列出已有数据集，提醒 LLM 引用这些 ID
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
     * 将 ChatMessage 列表转换为轻量的 ChatMessageSnapshot 列表。
     *
     * <p>用于跨 Todo 传递对话历史（CoT 上下文）。每种消息类型只保留关键字段：
     * <ul>
     *   <li>SystemMessage → role="system", content</li>
     *   <li>UserMessage → role="user", content</li>
     *   <li>AiMessage → role="assistant", content, toolCalls(可选)</li>
     *   <li>ToolExecutionResultMessage → role="tool", content, toolName, toolCallId</li>
     * </ul>
     *
     * @param messages 原始 ChatMessage 列表
     * @return ChatMessageSnapshot 列表
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

                // 保存 LLM 返回的工具调用请求（含 id、name、arguments）
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
     * 判断是否恢复已完成 Todo 的完整消息历史。
     *
     * <p>默认 summary 模式可以降低 prompt 体积；full 模式作为兼容回滚开关保留。</p>
     */
    private boolean shouldRestoreFullHistory(String currentDescription, CompletedTodoInfo todo) {
        if (todo == null || todo.getMessageHistory() == null || todo.getMessageHistory().isEmpty()) {
            return false;
        }
        if ("full".equalsIgnoreCase(nvl(completedTodoContextMode))) {
            return true;
        }
        String text = nvl(currentDescription);
        return text.contains("参考前面的推理过程")
                || text.contains("复盘完整过程")
                || text.contains("继续上一步对话");
    }

    /**
     * 从 ChatMessageSnapshot 列表还原 ChatMessage 列表。
     *
     * <p>与 {@link #convertMessagesToSnapshots} 互为逆操作。
     * 还原逻辑：</p>
     * <ul>
     *   <li>跳过第一条 SystemMessage（会在 buildMessages 中重新添加新的 System Prompt）。</li>
     *   <li>Assistant 消息中若含 toolCalls，还原为带 ToolExecutionRequest 的 AiMessage。
     *       这确保后续轮次 LLM 能看到之前的 tool_calls 结构。</li>
     * </ul>
     *
     * @param snapshots 消息快照列表
     * @return 还原的 ChatMessage 列表
     */
    private List<ChatMessage> restoreMessagesFromSnapshots(List<CompletedTodoInfo.ChatMessageSnapshot> snapshots) {
        List<ChatMessage> messages = new ArrayList<>();
        if (snapshots == null || snapshots.isEmpty()) {
            return messages;
        }

        // 跳过开头的 SystemMessage（新的 System Prompt 会在 buildMessages 中重新添加）
        boolean skippedSystem = false;

        for (CompletedTodoInfo.ChatMessageSnapshot snapshot : snapshots) {
            String role = snapshot.getRole();
            String content = snapshot.getContent();

            if ("system".equals(role)) {
                if (!skippedSystem) {
                    skippedSystem = true;
                    continue;
                }
                messages.add(new SystemMessage(content));
            } else if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
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

    // ======================== LLM 调用与工具路由 ========================

    /**
     * 获取工具的规范化参数说明（用于日志和错误提示）。
     *
     * <p>注意：这个映射仅用于调试输出。System Prompt 中的正式工具定义
     * 来自 {@code promptService.dagReactSystemPrompt()} 配置文件。</p>
     *
     * @param toolName 工具名称
     * @return 参数规范的字符串描述
     */
    private String getToolParamSpec(String toolName) {
        return switch (toolName) {
            case "searchIndex" -> "{\"keyword\": \"<搜索关键词>\"}";
            case "searchStock" -> "{\"keyword\": \"<搜索关键词>\"}";
            case "searchFund" -> "{\"keyword\": \"<场外基金关键词>\"}";
            case "getIndexDaily" -> "{\"ts_code\": \"<指数代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}";
            case "getStockDaily" -> "{\"ts_code\": \"<股票代码>\", \"start_date\": \"YYYYMMDD\", \"end_date\": \"YYYYMMDD\"}";
            case "searchAssetInfo" -> "{\"query\": \"<搜索关键词>\", \"assetTypes\": \"stock,etf,index\", \"marketScope\": \"domestic\"}";
            case "getExchangeAssetDaily" -> "{\"tsCode\": \"<代码>\", \"assetType\": \"stock|etf\", \"startDate\": \"YYYYMMDD\", \"endDate\": \"YYYYMMDD\"}";
            case "getOffExchangeAssetDaily" -> "{\"tsCode\": \"<基金代码>\", \"startDate\": \"YYYYMMDD\", \"endDate\": \"YYYYMMDD\"}";
            case "getListedAssetShareSize" -> "{\"tsCode\": \"<ETF代码>\", \"startDate\": \"YYYYMMDD\", \"endDate\": \"YYYYMMDD\", \"exchange\": \"SSE\"}";
            case "getEtfAdj" -> "{\"tsCode\": \"<ETF代码>\", \"startDate\": \"YYYYMMDD\", \"endDate\": \"YYYYMMDD\"}";
            case "getIndexInfo" -> "{\"ts_code\": \"<指数代码>\"}";
            case "getStockInfo" -> "{\"ts_code\": \"<股票代码>\"}";
            case "executePython" -> "{\"code\": \"<Python代码>\", \"dataset_ids\": \"<必需：数据集ID，逗号分隔>\", \"libraries\": \"<可选：库名逗号分隔>\"}";
            case "searchWeb" -> "{\"query\": \"<搜索查询文本>\", \"scene\": \"general|finance|news\", \"backend\": \"perplexity|tavily|exa|\", \"strength\": \"<可选>\", \"skipHotCache\": true, \"skipRagPrefetch\": true, \"timeRangeStart\": \"\", \"timeRangeEnd\": \"\", \"maxResults\": 5}";
            default -> "{...}";
        };
    }

    /**
     * 从执行上下文中解析可用工具名称集合。
     *
     * <p>优先使用 {@code availableTools}（已解析的工具名集合），
     * 若为空则从 {@code toolSpecifications} 中提取名称。</p>
     */
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

    /**
     * 生成工具调用指纹，用于识别同一 Todo 内重复失败的同一工具同一参数调用。
     */
    private String toolCallFingerprint(String toolName, String arguments) {
        return nvl(toolName) + ":" + canonicalArguments(arguments);
    }

    /**
     * 将工具参数规范化为稳定 JSON。解析失败时使用去空白后的原始字符串。
     */
    private String canonicalArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            Object parsed = objectMapper.readValue(arguments, Object.class);
            return objectMapper.writeValueAsString(sortJsonLike(parsed));
        } catch (Exception e) {
            return arguments.trim();
        }
    }

    @SuppressWarnings("unchecked")
    private Object sortJsonLike(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortJsonLike(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sortJsonLike).toList();
        }
        return value;
    }

    /**
     * 调用 LLM 进行决策，携带工具定义。
     *
     * <p>如果有可用工具，构建带 toolSpecifications 的 ChatRequest；
     * 否则使用最简单的 chat(messages) 调用。</p>
     *
     * @param model    ChatModel
     * @param messages 当前消息历史
     * @param context  执行上下文
     * @return LLM 响应
     */
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

    /**
     * 解析本次调用的有效工具规范列表。
     *
     * <p>合并策略：</p>
     * <ol>
     *   <li>从上下文的 toolSpecifications 中收集所有已定义的 ToolSpecification，
     *       按 name 去重（后者覆盖前者）。</li>
     *   <li>补充 Sub-Agent 的两个虚拟工具：spawnSubAgent 和 waitForSubAgent。
     *       这两个工具在 {@link #buildSubAgentToolSpecifications} 中手动构建 schema。</li>
     *   <li>按可用工具名称白名单过滤，仅返回当前上下文中允许使用的工具。</li>
     * </ol>
     *
     * @param context 执行上下文
     * @return 有效的 ToolSpecification 列表
     */
    private List<ToolSpecification> resolveToolSpecifications(TodoExecutionContext context) {
        // 收集所有 ToolSpecification，按 name 去重
        LinkedHashMap<String, ToolSpecification> specMap = new LinkedHashMap<>();
        if (context.getToolSpecifications() != null) {
            for (ToolSpecification specification : context.getToolSpecifications()) {
                if (specification == null || specification.name() == null || specification.name().isBlank()) {
                    continue;
                }
                specMap.put(specification.name(), specification);
            }
        }

        // 补充 Sub-Agent 工具
        buildSubAgentToolSpecifications().forEach(spec -> specMap.putIfAbsent(spec.name(), spec));

        // 按白名单过滤
        Set<String> allowList = resolveAvailableToolNames(context);
        if (allowList.isEmpty()) {
            return new ArrayList<>(specMap.values());
        }
        return specMap.values().stream()
                .filter(spec -> allowList.contains(spec.name()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 手动构建 Sub-Agent 相关工具的 ToolSpecification。
     *
     * <p>这两个工具不在业务工具的 ToolRouter 中注册，而是在 ReAct 执行器内部实现，
     * 因此需要手动提供 JSON Schema：</p>
     * <ul>
     *   <li>{@code spawnSubAgent(goal, [context])} — 后台启动子代理。</li>
     *   <li>{@code waitForSubAgent(sub_agent_ids | sub_agent_id)} — 等待子代理结果。</li>
     * </ul>
     *
     * @return Sub-Agent 工具规范列表
     */
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

    /**
     * 执行单个工具调用请求。
     *
     * <p>根据 toolName 分为三条执行路径：</p>
     * <ul>
     *   <li>{@code spawnSubAgent} — 委托给 {@link #handleSpawnSubAgent}，后台启动。</li>
     *   <li>{@code waitForSubAgent} — 委托给 {@link #handleWaitForSubAgent}，阻塞等待。</li>
     *   <li><b>其他业务工具</b> — 委托给 {@link ToolRouter#invokeWithMeta}，走通用路由。</li>
     * </ul>
     *
     * <p>所有路径都记录工具调用观测数据（耗时、成功/失败、错误信息）。</p>
     *
     * @param toolRequest          LLM 返回的 ToolExecutionRequest
     * @param context              执行上下文
     * @param runId                Run ID
     * @param phase                执行阶段名
     * @param model                ChatModel（传递给 Sub-Agent 方法）
     * @param pendingSubAgents     当前待处理的 Sub-Agent Future 表
     * @param subAgentIdCounter   Sub-Agent ID 计数器
     * @return 执行结果（含 result 文本和 success 标志）
     */
    private ToolExecutionOutcome executeToolRequest(ToolExecutionRequest toolRequest,
                                                    TodoExecutionContext context,
                                                    String runId,
                                                    String phase,
                                                    ChatModel model,
                                                    Map<String, Future<SubAgentRunner.SubAgentResult>> pendingSubAgents,
                                                    AtomicInteger subAgentIdCounter) {
        long toolStartTime = System.currentTimeMillis();
        String toolName = nvl(toolRequest.name());
        // 解析工具参数 JSON 为 Map，并注入已有数据集引用映射
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
                // 业务工具：走 ToolRouter 统一路由
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

    /**
     * 将工具调用参数 JSON 字符串解析为 Map。
     *
     * @param arguments LLM 传入的 arguments JSON 字符串
     * @return 解析后的参数 Map，解析失败时返回空 Map
     */
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

    /**
     * 压缩工具参数文本用于日志展示：标准化空白字符，截断至 200 字符。
     */
    private String trimToolArguments(String arguments) {
        if (arguments == null) {
            return "";
        }
        String normalized = arguments.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    /**
     * 记录工具调用的观测数据。
     *
     * <p>当 runId 为空时跳过记录（例如在单元测试中不关心观测数据时）。</p>
     */
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

    // ======================== 辅助方法 ========================

    /** 空安全：null 转为空字符串。 */
    private String nvl(String value) {
        return value == null ? "" : value;
    }

    // ======================== 内部类型 ========================

    /**
     * 单个 Todo 的执行上下文，包含 LLM 决策所需的所有环境信息。
     *
     * <p>被调用方（线性/DAG 执行器）负责构造此上下文，传入
     * {@link #executeWithObservability}。</p>
     */
    @Builder
    @Data
    public static class TodoExecutionContext {
        /** 用户原始目标文本（整个 Run 的顶层问题） */
        private String userGoal;
        /** 当前上下文中允许 LLM 调用的工具名称白名单 */
        private Set<String> availableTools;
        /** 可用工具的 ToolSpecification 列表（含参数 schema） */
        private List<ToolSpecification> toolSpecifications;
        /** 已经完成的 Todo 信息列表（含输出、summary、完整消息历史） */
        private List<CompletedTodoInfo> completedTodos;
        /** 已有数据集 ID → 沙箱路径 的映射（如 "dataset_xxx" → "/sandbox/input/dataset_xxx"） */
        private Map<String, String> datasetRefs;
    }

    /** 工具执行结果的不可变记录。 */
    private record ToolExecutionOutcome(String result, boolean success) {
    }

    /**
     * 单个 Todo 执行完毕后的结果记录。
     *
     * <p>同时包含业务结果和观测元数据，供上层执行器：
     * <ul>
     *   <li>判断执行成功/失败（{@code success}）</li>
     *   <li>获取最终输出（{@code output}）和摘要（{@code summary}）</li>
     *   <li>追溯 LLM 调用（{@code llmTraceId}）</li>
     *   <li>统计工具调用次数（{@code toolCallsUsed}）</li>
     *   <li>传递完整消息历史到后续 Todo（{@code messageHistory}）</li>
     * </ul>
     */
    @Builder
    @Data
    public static class TodoExecutionRecord {
        /** 本次 Todo 执行是否成功 */
        private boolean success;
        /** 工具调用或 LLM 决策的最终文本输出 */
        private String output;
        /** 人类可读的执行摘要 */
        private String summary;

        // ── 观测数据字段 ──

        /** 本次执行中最后一次 LLM 调用的 trace ID */
        private String llmTraceId;
        /** 最后一个被调用的工具名称 */
        private String toolName;
        /** 最后一个工具调用的参数 */
        private Map<String, Object> toolParams;
        /** 最后一个工具调用的耗时（毫秒） */
        private Long toolDurationMs;
        private Instant startedAt;
        private Instant completedAt;

        // ── ReAct 循环统计 ──

        /** 本次 Todo 执行累计的工具调用次数 */
        @Builder.Default
        private int toolCallsUsed = 0;

        // ── 重试相关 ──

        /** 本次 Todo 执行的重试次数（首次为 0） */
        @Builder.Default
        private int retryCount = 0;

        // ── CoT 上下文 ──

        /**
         * 完整的消息历史（含 CoT 推理过程和工具调用链），
         * 用于传递给后续 Todo，使后续 LLM 能看到之前发生的完整上下文。
         */
        @Builder.Default
        private List<CompletedTodoInfo.ChatMessageSnapshot> messageHistory = new ArrayList<>();
    }
}
