package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.TokenUsage;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Agent Run 观测数据（observability）中枢 —— 汇集、持久化、查询整次 run 的全部可观测信息。
 *
 * <h2>为什么重要</h2>
 * <p>每次 agent run 执行期间会产生大量的 LLM 调用、工具调用、阶段切换、失败重试等事件。
 * 这个类负责把所有非确定性行为（每次 LLM 推理结果不同、工具调用结果变化）记录下来，
 * 形成完整的时间线和追踪链。排障时 matrix 的 summary/events/traces/observability_full
 * 都依赖这里的数据。面试被问"你们怎么排查 agent 失败"时，所有答案都来自这个类。</p>
 *
 * <h2>核心职责</h2>
 * <ol>
 *   <li><b>LLM 调用 trace</b>：{@link #recordLlmCall} 系列方法记录每次 LLM 调用的 id、phase、
 *       token 用量、耗时、错误。{@link #recordLlmCallWithRawHttp} 支持记录完整原始 HTTP
 *       请求/响应（ALP-25），调试复杂问题时至关重要。</li>
 *   <li><b>工具调用 trace</b>：{@link #recordToolCall} 记录工具名、参数、输出、缓存命中/未命中、
 *       耗时，由 {@code ToolRouter} 调用。</li>
 *   <li><b>Run 初始化与失败</b>：{@link #initializeRun} 在 Pipeline 入口调用，设置启动时间、
 *       endpoint/model、captureLlmRequests 开关。{@link #recordFailure} 在 run 失败时写入
 *       diagnostics.lastErrorType / lastErrorMessage。</li>
 *   <li><b>阶段（phase）维度聚合</b>：planning / parallel_execution / sub_agent / tool_execution /
 *       summarizing 五个阶段各自聚合 llmCalls、toolCalls、durationMs、tokens、errors。
 *       面试可讲"我们的 observability 按阶段聚合，方便定位是 planning 还是 execution 出了问题"。</li>
 *   <li><b>观测视图组装</b>：{@link #attachObservabilityToSnapshot} 在 run 完成时将观测数据
 *       嵌入到 snapshot JSON（落 DB）。{@link #loadObservabilityJson} 优先从 Redis 加载完整观测，
 *       其次从 snapshot 回退。{@link #loadObservabilitySummaryJson} 返回不含 trace 的轻量摘要。</li>
 *   <li><b>缓存命中率统计</b>：自动汇总工具调用的 cache hit/miss 数与估算节省时间，
 *       用于衡量搜索类工具的缓存效果。</li>
 * </ol>
 *
 * <h2>数据流</h2>
 * <pre>
 * LLM 调用 → recordLlmCall() → mutate() → Redis（JSON）→ 定期 flush
 * 工具调用  → recordToolCall() → mutate() → Redis
 * Run 结束  → attachObservabilityToSnapshot() → snapshot JSON → DB
 * Matrix    → loadObservabilityJson() → Redis 优先，snapshot 兜底
 * </pre>
 *
 * <h2>容量保护（面试要点）</h2>
 * <p>生产环境长时间运行的 agent 可能产生数百次 LLM 调用，不加限制会撑爆 Redis 和 DB。
 * 因此设计了三层容量保护：</p>
 * <ul>
 *   <li>{@code llmTraceMaxCalls}：LlmTrace 列表最大长度（默认 100，超出移除最旧）</li>
 *   <li>{@code llmTraceMaxTextChars}：单条请求/响应文本最大字符数（默认 20K，超出截断）</li>
 *   <li>{@code toolTraceMaxOutputChars}：单条工具输出最大字符数（默认 100K，超出截断）</li>
 * </ul>
 *
 * <h2>并发安全</h2>
 * <p>使用 per-runId 的互斥锁保证同一 run 的 mutate 操作串行化——
 * 整个函数式更新在锁内完成，避免并发 write → read → write 导致旧值覆盖新值。</p>
 *
 * @see AgentRunStateStore Redis 状态存储
 * @see world.willfrog.agent.tool.router.ToolRouter 工具层调用入口
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentObservabilityService {

    /** 阶段常量：规划阶段（生成 Todo Plan） */
    public static final String PHASE_PLANNING = "planning";
    /** 阶段常量：并行执行阶段（DAG 模式下多个 todo 并行执行） */
    public static final String PHASE_PARALLEL_EXECUTION = "parallel_execution";
    /** 阶段常量：子代理执行阶段（spawnSubAgent / waitForSubAgent 路径） */
    public static final String PHASE_SUB_AGENT = "sub_agent";
    /** 阶段常量：工具执行阶段（线性 ReAct 默认归属） */
    public static final String PHASE_TOOL_EXECUTION = "tool_execution";
    /** 阶段常量：汇总阶段（生成最终答案） */
    public static final String PHASE_SUMMARIZING = "summarizing";

    /** Run 级状态存储（Redis），观测状态以 JSON 字符串形式持久化 */
    private final AgentRunStateStore stateStore;
    /** JSON 序列化器，承担状态序列化、HTTP body 解析、消息快照生成等多重职责 */
    private final ObjectMapper objectMapper;
    /** Debug 文件写入器，在 DEBUG 日志开启时把 trace 单独写一份到磁盘文件，方便事后排查 */
    private final AgentObservabilityDebugFileWriter debugFileWriter;
    /** 按 runId 的锁池，保证同一 run 的 mutate 操作串行化，避免并发覆盖 Redis 状态 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /** 全局 LLM trace 开关：true 时所有 run 都记录详细 LLM 调用 trace；false 时仅对显式开启的 run 记录 */
    @Value("${agent.observability.llm-trace.enabled:false}")
    private boolean llmTraceEnabled;

    /** LlmTrace 列表最大长度（超出时按 FIFO 移除最旧记录） */
    @Value("${agent.observability.llm-trace.max-calls:100}")
    private int llmTraceMaxCalls;

    /** LLM 请求/响应文本字段单条最大字符数（超出截断） */
    @Value("${agent.observability.llm-trace.max-text-chars:20000}")
    private int llmTraceMaxTextChars;

    /** 是否采集 OpenAI 兼容协议的 cached_tokens 字段（即 prompt cache 命中 token 数） */
    @Value("${agent.observability.llm-trace.capture-cached-tokens:true}")
    private boolean captureCachedTokens;

    /** reasoning（thinking）文本单条最大字符数（超出截断） */
    @Value("${agent.observability.llm-trace.reasoning-max-chars:20000}")
    private int llmTraceReasoningMaxChars;

    /** 工具调用 trace 输出文本最大字符数（超出截断） */
    @Value("${agent.observability.tool-trace.max-output-chars:100000}")
    private int toolTraceMaxOutputChars;

    /**
     * 初始化 run 的观测状态（不开启 LLM 请求捕获）。
     *
     * @param runId        Run ID
     * @param endpointName 初始 endpoint 名（写入 diagnostics.lastEndpoint）
     * @param modelName    初始模型名（写入 diagnostics.lastModel）
     */
    public void initializeRun(String runId, String endpointName, String modelName) {
        initializeRun(runId, endpointName, modelName, false);
    }

    /**
     * 初始化 run 的观测状态：设置启动时间、状态、初始 endpoint/model，以及是否启用 LLM 请求捕获。
     *
     * <p>幂等：若 summary.startedAtMillis 已被设置则不再覆盖（应对重入场景）。
     * 通常由 {@link AgentRunExecutor#doExecute} 在 run 开始执行时调用。</p>
     *
     * @param runId               Run ID
     * @param endpointName        初始 endpoint 名
     * @param modelName           初始模型名
     * @param captureLlmRequests  是否启用本 run 的完整 LLM 请求/响应抓取（ALP-25）
     */
    public void initializeRun(String runId, String endpointName, String modelName, boolean captureLlmRequests) {
        mutate(runId, state -> {
            if (state.getSummary().getStartedAtMillis() <= 0) {
                state.getSummary().setStartedAtMillis(System.currentTimeMillis());
            }
            state.getSummary().setStatus(AgentRunStatus.EXECUTING.name());
            state.getDiagnostics().setCaptureLlmRequests(captureLlmRequests);
            if (endpointName != null && !endpointName.isBlank()) {
                state.getDiagnostics().setLastEndpoint(endpointName);
            }
            if (modelName != null && !modelName.isBlank()) {
                state.getDiagnostics().setLastModel(modelName);
            }
        });
    }

    /**
     * 记录流式生成的实时进度快照（每个 chunk 接收时由流式 ChatModel 上报）。
     *
     * <p>用于前端展示"正在生成…"的实时字符数/速率等。
     * 流式完成时再次调用并将 {@code completed=true}，方便前端区分进行中和已完成。</p>
     *
     * @param runId        Run ID
     * @param phase        阶段标识
     * @param endpointName endpoint 名（同时更新 lastEndpoint）
     * @param modelName    模型名（同时更新 lastModel）
     * @param snapshot     来自 {@link StreamingProgressTracker} 的进度快照
     * @param completed    本次流式调用是否已完成
     */
    public void recordStreamingProgress(String runId,
                                        String phase,
                                        String endpointName,
                                        String modelName,
                                        StreamingProgressTracker.StreamingProgressSnapshot snapshot,
                                        boolean completed) {
        if (runId == null || runId.isBlank() || snapshot == null) {
            return;
        }
        mutate(runId, state -> {
            if (endpointName != null && !endpointName.isBlank()) {
                state.getDiagnostics().setLastEndpoint(endpointName);
            }
            if (modelName != null && !modelName.isBlank()) {
                state.getDiagnostics().setLastModel(modelName);
            }
            Diagnostics.StreamingProgressStatus status = new Diagnostics.StreamingProgressStatus();
            status.setPhase(normalizePhase(phase));
            status.setEndpoint(nvl(endpointName));
            status.setModel(nvl(modelName));
            status.setCompleted(completed);
            status.setUpdatedAt(OffsetDateTime.now().toString());
            status.setContentCharCount(snapshot.contentCharCount());
            status.setReasoningCharCount(snapshot.reasoningCharCount());
            status.setToolCallCharCount(snapshot.toolCallCharCount());
            status.setTotalCharCount(snapshot.totalCharCount());
            status.setChunkCount(snapshot.chunkCount());
            status.setDurationMs(snapshot.durationMs());
            status.setCharsPerSecond(snapshot.charsPerSecond());
            state.getDiagnostics().setStreamingProgress(status);
        });
    }
    
    /**
     * 检查指定 Run 是否启用了 LLM 请求捕获（ALP-25）
     * 
     * <p>用于 OpenRouterProviderRoutedChatModel 判断是否记录原始 HTTP。</p>
     * 
     * @param runId Run ID
     * @return true 表示该 Run 启用了捕获
     */
    public boolean isCaptureLlmRequestsEnabled(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        try {
            ObservabilityState state = loadState(runId);
            if (state == null || state.getDiagnostics() == null) {
                return false;
            }
            // 全局开关优先
            if (llmTraceEnabled) {
                return true;
            }
            return Boolean.TRUE.equals(state.getDiagnostics().getCaptureLlmRequests());
        } catch (Exception e) {
            log.warn("检查 captureLlmRequests 失败: runId={}", runId, e);
            return false;
        }
    }

    /**
     * 累加 run 的节点数（DAG 模式下节点数，用于前端展示）。
     *
     * <p>delta 允许为负数表示节点回退/移除，但累加结果不会小于 0。</p>
     *
     * @param runId Run ID
     * @param delta 增量（正数累加、负数减少）
     */
    public void addNodeCount(String runId, int delta) {
        if (delta == 0) {
            return;
        }
        mutate(runId, state -> {
            long current = state.getSummary().getNodeCount();
            state.getSummary().setNodeCount(Math.max(0, current + delta));
        });
    }

    /**
     * 简化入口：记录一次 LLM 调用（无请求快照、无响应文本）。
     *
     * <p>主要用于上层不关心请求/响应细节、仅希望统计调用次数和 token 用量的场景。</p>
     *
     * @param runId        Run ID
     * @param phase        阶段标识
     * @param tokenUsage   token 用量（来自 LLM 响应，可为 null）
     * @param durationMs   调用耗时
     * @param endpointName endpoint 名
     * @param modelName    模型名
     * @param errorMessage 错误信息（null 表示成功）
     * @return 本次 LLM 调用的 traceId
     */
    public String recordLlmCall(String runId,
                                String phase,
                                TokenUsage tokenUsage,
                                long durationMs,
                                String endpointName,
                                String modelName,
                                String errorMessage) {
        return recordLlmCall(
                runId,
                phase,
                tokenUsage,
                durationMs,
                endpointName,
                modelName,
                errorMessage,
                null,
                null,
                null
        );
    }

    /**
     * 记录一次 LLM 调用（含请求消息列表和元数据，会被加工成 requestSnapshot）。
     *
     * @param runId            Run ID
     * @param phase            阶段标识
     * @param tokenUsage       token 用量
     * @param durationMs       调用耗时
     * @param endpointName     endpoint 名
     * @param modelName        模型名
     * @param errorMessage     错误信息
     * @param requestMessages  请求消息列表（LangChain4j ChatMessage）
     * @param requestMeta      请求元数据（如 stage、reasoning 等）
     * @param responseText     响应文本（可能被截断后存入 trace）
     * @return 本次 LLM 调用的 traceId
     */
    public String recordLlmCall(String runId,
                                String phase,
                                TokenUsage tokenUsage,
                                long durationMs,
                                String endpointName,
                                String modelName,
                                String errorMessage,
                                List<ChatMessage> requestMessages,
                                Map<String, Object> requestMeta,
                                String responseText) {
        Map<String, Object> requestSnapshot = buildLlmRequestSnapshot(requestMessages, requestMeta);
        return recordLlmCall(
                runId,
                phase,
                tokenUsage,
                durationMs,
                endpointName,
                modelName,
                errorMessage,
                requestSnapshot,
                responseText
        );
    }

    /**
     * 记录一次 LLM 调用（已构建好 requestSnapshot 的版本，无 startedAt/completedAt 时间戳）。
     */
    public String recordLlmCall(String runId,
                                String phase,
                                TokenUsage tokenUsage,
                                long durationMs,
                                String endpointName,
                                String modelName,
                                String errorMessage,
                                Map<String, Object> requestSnapshot,
                                String responseText) {
        return recordLlmCall(runId, phase, tokenUsage, durationMs, 0, 0, endpointName, modelName,
                errorMessage, requestSnapshot, responseText);
    }

    /**
     * 记录一次 LLM 调用的完整核心实现，更新 summary、phaseMetrics、lastEndpoint/lastModel/
     * lastError，并 append 一条 LlmTrace。
     *
     * <h4>关键路径</h4>
     * <ol>
     *   <li>若 AgentContext 中已有 providerTraceId（来自更底层 provider 的 trace 上报），
     *       直接复用、不再生成新 traceId 也不重复记录 trace（避免双重写入）。</li>
     *   <li>对 requestSnapshot 做 sanitize（截断长字符串、去循环引用）。</li>
     *   <li>从 responseText 中尝试提取 reasoning/thinking 文本。</li>
     *   <li>在 DEBUG 日志开启时把 trace payload 单独写到 debug 文件。</li>
     *   <li>原子 mutate 状态：累加 llmCalls、token、durationMs；填充错误诊断；append 新 LlmTrace。</li>
     * </ol>
     *
     * @return 本次记录使用的 traceId（用于后续 enrichLlmCallSpending 等关联）
     */
    public String recordLlmCall(String runId,
                                String phase,
                                TokenUsage tokenUsage,
                                long durationMs,
                                long startedAtMillis,
                                long completedAtMillis,
                                String endpointName,
                                String modelName,
                                String errorMessage,
                                Map<String, Object> requestSnapshot,
                                String responseText) {
        // 若 provider（如 OpenRouter 自定义 ChatModel）已经在更底层上报了 trace，
        // 这里直接复用 traceId 跳过重复记录，避免一次 LLM 调用被算两次
        String providerTraceId = AgentContext.consumeProviderLlmTraceId();
        if (providerTraceId != null && !providerTraceId.isBlank()) {
            return providerTraceId;
        }
        Map<String, Object> sanitizedRequestSnapshot = sanitizeRequestSnapshot(requestSnapshot);
        String responsePreview = trim(responseText, llmTraceTextLimit());
        String traceId = newTraceId();
        String stage = resolveStage(sanitizedRequestSnapshot);
        ReasoningExtraction reasoning = extractReasoning(responseText);
        if (log.isDebugEnabled()) {
            log.debug("OBS_LLM runId={} phase={} durationMs={} endpoint={} model={} hasError={}",
                    runId, normalizePhase(phase), clampDuration(durationMs), nvl(endpointName), nvl(modelName),
                    errorMessage != null && !errorMessage.isBlank());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceId);
            payload.put("runId", runId);
            payload.put("phase", normalizePhase(phase));
            payload.put("stage", stage);
            payload.put("durationMs", clampDuration(durationMs));
            payload.put("startedAtMillis", startedAtMillis);
            payload.put("completedAtMillis", completedAtMillis);
            payload.put("endpoint", nvl(endpointName));
            payload.put("model", nvl(modelName));
            payload.put("hasError", errorMessage != null && !errorMessage.isBlank());
            payload.put("error", trim(errorMessage, 500));
            payload.put("tokenUsage", tokenUsage == null ? null : Map.of(
                    "input", tokenUsage.inputTokenCount(),
                    "output", tokenUsage.outputTokenCount(),
                    "total", tokenUsage.totalTokenCount()
            ));
            payload.put("reasoningText", trim(reasoning.text(), 500));
            payload.put("reasoningTruncated", reasoning.truncated());
            payload.put("request", sanitizedRequestSnapshot);
            payload.put("responsePreview", responsePreview);
            debugFileWriter.write("OBS_LLM", payload);
        }
        mutate(runId, state -> {
            // 累加 run 级 LLM 调用计数
            state.getSummary().setLlmCalls(state.getSummary().getLlmCalls() + 1);
            // 取出/创建该 phase 的指标桶，累加对应阶段的调用计数、耗时
            PhaseMetrics phaseMetrics = phaseMetrics(state, phase);
            phaseMetrics.setCount(phaseMetrics.getCount() + 1);
            phaseMetrics.setLlmCalls(phaseMetrics.getLlmCalls() + 1);
            phaseMetrics.setDurationMs(phaseMetrics.getDurationMs() + clampDuration(durationMs));
            // 同步累加 token 用量到 summary 和 phaseMetrics
            applyTokens(state.getSummary(), phaseMetrics, tokenUsage);
            if (endpointName != null && !endpointName.isBlank()) {
                state.getDiagnostics().setLastEndpoint(endpointName);
            }
            if (modelName != null && !modelName.isBlank()) {
                state.getDiagnostics().setLastModel(modelName);
            }
            // 错误诊断：记录最近一次错误类型与摘要，供前端故障定位
            if (errorMessage != null && !errorMessage.isBlank()) {
                phaseMetrics.setErrorCount(phaseMetrics.getErrorCount() + 1);
                state.getDiagnostics().setLastErrorType("LLM_ERROR");
                state.getDiagnostics().setLastErrorMessage(trim(errorMessage, 500));
            }
            // append LlmTrace 到 diagnostics.llmTraces 列表（受容量上限保护）
            appendLlmTrace(state.getDiagnostics(), traceId, runId, phase, stage, tokenUsage, durationMs, startedAtMillis, completedAtMillis,
                    endpointName, modelName, errorMessage, sanitizedRequestSnapshot, responsePreview, reasoning);
        });
        return traceId;
    }

    /**
     * 记录带有原始 HTTP 信息的 LLM 调用（ALP-25 核心方法）。
     * 
     * <p>本方法是 ALP-25 可观测性增强的核心入口，支持记录完整的 HTTP 请求/响应信息，
     * 用于后续的问题诊断、curl 复现、Provider 差异分析等。</p>
     * 
     * <p><b>上报内容：</b></p>
     * <ul>
     *   <li>Run 维度统计：LLM 调用次数、Token 消耗、耗时</li>
     *   <li>Phase 维度统计：各阶段的调用次数和错误数</li>
     *   <li>原始 HTTP 信息：完整请求/响应（URL、headers、body、statusCode）</li>
     *   <li>Curl 命令：可直接执行的复现命令</li>
     * </ul>
     * 
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>OpenRouterProviderRoutedChatModel 在完成 HTTP 调用后上报</li>
     *   <li>后续其他自定义 ChatModel 实现可复用此方法</li>
     * </ul>
     * 
     * @param runId Run ID，用于关联到具体的 AgentRun
     * @param phase 阶段标识，如 "planning"、"execution"、"summarizing"
     * @param tokenUsage Token 使用情况（从 LLM 响应中解析）
     * @param durationMs 请求耗时（毫秒）
     * @param endpointName Endpoint 名称，如 "fireworks"、"openrouter"
     * @param modelName 模型名称，如 "accounts/fireworks/models/kimi-k2p5"
     * @param errorMessage 错误信息，null 表示成功，非 null 表示失败原因
     * @param httpRequest 原始 HTTP 请求记录（由 RawHttpLogger 生成）
     * @param httpResponse 原始 HTTP 响应记录（由 RawHttpLogger 生成）
     * @param curlCommand 可直接执行的 curl 命令（用于快速复现）
     * 
     * @see RawHttpLogger
     * @see OpenRouterProviderRoutedChatModel
     * @since ALP-25
     */
    public String recordLlmCallWithRawHttp(
            String runId,
            String phase,
            TokenUsage tokenUsage,
            long durationMs,
            String endpointName,
            String modelName,
            String errorMessage,
            RawHttpLogger.HttpRequestRecord httpRequest,
            RawHttpLogger.HttpResponseRecord httpResponse,
            String curlCommand) {
        return recordLlmCallWithRawHttp(runId, phase, tokenUsage, null, durationMs, 0, 0, endpointName, modelName,
                errorMessage, null, null, httpRequest, httpResponse, curlCommand);
    }

    /** Overload: 同上但带 startedAt/completedAt 时间戳。 */
    public String recordLlmCallWithRawHttp(
            String runId,
            String phase,
            TokenUsage tokenUsage,
            long durationMs,
            long startedAtMillis,
            long completedAtMillis,
            String endpointName,
            String modelName,
            String errorMessage,
            RawHttpLogger.HttpRequestRecord httpRequest,
            RawHttpLogger.HttpResponseRecord httpResponse,
            String curlCommand) {
        return recordLlmCallWithRawHttp(runId, phase, tokenUsage, null, durationMs, startedAtMillis, completedAtMillis,
                endpointName, modelName, errorMessage, null, null, httpRequest, httpResponse, curlCommand);
    }

    /** Overload: 同上但带 cachedTokens（OpenAI 兼容协议 prompt cache 命中数）。 */
    public String recordLlmCallWithRawHttp(
            String runId,
            String phase,
            TokenUsage tokenUsage,
            Integer cachedTokens,
            long durationMs,
            long startedAtMillis,
            long completedAtMillis,
            String endpointName,
            String modelName,
            String errorMessage,
            RawHttpLogger.HttpRequestRecord httpRequest,
            RawHttpLogger.HttpResponseRecord httpResponse,
            String curlCommand) {
        return recordLlmCallWithRawHttp(runId, phase, tokenUsage, cachedTokens, durationMs, startedAtMillis, completedAtMillis,
                endpointName, modelName, errorMessage, null, null, httpRequest, httpResponse, curlCommand);
    }

    /** Overload: 同上但带显式 thinkingContent 和流式进度快照。 */
    public String recordLlmCallWithRawHttp(
            String runId,
            String phase,
            TokenUsage tokenUsage,
            Integer cachedTokens,
            long durationMs,
            long startedAtMillis,
            long completedAtMillis,
            String endpointName,
            String modelName,
            String errorMessage,
            String thinkingContent,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress,
            RawHttpLogger.HttpRequestRecord httpRequest,
            RawHttpLogger.HttpResponseRecord httpResponse,
            String curlCommand) {
        return recordLlmCallWithRawHttp(runId, phase, tokenUsage, cachedTokens, durationMs, startedAtMillis, completedAtMillis,
                endpointName, modelName, errorMessage, thinkingContent, streamingProgress, httpRequest, httpResponse, curlCommand, List.of());
    }

    /**
     * 记录带原始 HTTP 信息和 attempts（重试明细）的 LLM 调用的最完整重载，是其他重载的最终汇入点。
     *
     * <h4>额外职责（相对 {@link #recordLlmCall} 的核心实现）</h4>
     * <ul>
     *   <li>从 httpResponse.body 中提取 reasoning（若调用方未显式传入 thinkingContent）。</li>
     *   <li>将 httpRequest / httpResponse 转换为 {@link RawHttpTrace} 存入 trace。</li>
     *   <li>记录可直接执行的 curl 命令（Authorization 已脱敏）。</li>
     *   <li>记录 attempts 数组：单次 logical LLM 调用内部的多次 HTTP 重试明细。</li>
     *   <li>将请求 body JSON 反序列化后写入 inputMessages，response body 截断后写入 outputText。</li>
     * </ul>
     */
    public String recordLlmCallWithRawHttp(
            String runId,
            String phase,
            TokenUsage tokenUsage,
            Integer cachedTokens,
            long durationMs,
            long startedAtMillis,
            long completedAtMillis,
            String endpointName,
            String modelName,
            String errorMessage,
            String thinkingContent,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress,
            RawHttpLogger.HttpRequestRecord httpRequest,
            RawHttpLogger.HttpResponseRecord httpResponse,
            String curlCommand,
            List<Map<String, Object>> attempts) {
        // 与 recordLlmCall 一致：若 provider 层已上报 trace，直接复用 traceId 避免重复
        String providerTraceId = AgentContext.consumeProviderLlmTraceId();
        if (providerTraceId != null && !providerTraceId.isBlank()) {
            return providerTraceId;
        }
        String traceId = newTraceId();
        String stage = resolveStage(null);
        String rawResponseBody = httpResponse == null ? null : httpResponse.getBody();
        ReasoningExtraction reasoning = extractReasoning(rawResponseBody);

        // 若调用方显式传入了 thinkingContent，优先使用；否则从响应中提取
        String effectiveThinking = thinkingContent != null ? thinkingContent : reasoning.text();

        // 写入 debug 文件
        if (log.isDebugEnabled()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceId);
            payload.put("runId", runId);
            payload.put("phase", normalizePhase(phase));
            payload.put("stage", stage);
            payload.put("durationMs", clampDuration(durationMs));
            payload.put("startedAtMillis", startedAtMillis);
            payload.put("completedAtMillis", completedAtMillis);
            payload.put("endpoint", nvl(endpointName));
            payload.put("model", nvl(modelName));
            payload.put("hasError", errorMessage != null && !errorMessage.isBlank());
            payload.put("error", trim(errorMessage, 500));
            payload.put("tokenUsage", tokenUsage == null ? null : Map.of(
                    "input", tokenUsage.inputTokenCount(),
                    "output", tokenUsage.outputTokenCount(),
                    "total", tokenUsage.totalTokenCount()
            ));
            payload.put("cachedTokens", cachedTokens);
            payload.put("httpRequest", httpRequest != null ? Map.of(
                    "url", nvl(httpRequest.getUrl()),
                    "method", nvl(httpRequest.getMethod()),
                    "bodyPreview", preview(httpRequest.getBody(), 500)
            ) : null);
            payload.put("httpResponse", httpResponse != null ? Map.of(
                    "statusCode", httpResponse.getStatusCode(),
                    "bodyPreview", preview(httpResponse.getBody(), 500)
            ) : null);
            payload.put("reasoningText", trim(effectiveThinking, 500));
            payload.put("reasoningTruncated", reasoning.truncated());
            if (streamingProgress != null) {
                payload.put("streamingProgress", Map.of(
                        "contentCharCount", streamingProgress.contentCharCount(),
                        "reasoningCharCount", streamingProgress.reasoningCharCount(),
                        "toolCallCharCount", streamingProgress.toolCallCharCount(),
                        "totalCharCount", streamingProgress.totalCharCount(),
                        "chunkCount", streamingProgress.chunkCount(),
                        "durationMs", streamingProgress.durationMs(),
                        "charsPerSecond", streamingProgress.charsPerSecond()
                ));
            }
            debugFileWriter.write("OBS_LLM_RAW_HTTP", payload);
        }

        // 更新观测状态
        mutate(runId, state -> {
            // 累加 run 级 LLM 调用计数与 phase 维度指标
            state.getSummary().setLlmCalls(state.getSummary().getLlmCalls() + 1);
            PhaseMetrics phaseMetrics = phaseMetrics(state, phase);
            phaseMetrics.setCount(phaseMetrics.getCount() + 1);
            phaseMetrics.setLlmCalls(phaseMetrics.getLlmCalls() + 1);
            phaseMetrics.setDurationMs(phaseMetrics.getDurationMs() + clampDuration(durationMs));
            applyTokens(state.getSummary(), phaseMetrics, tokenUsage, cachedTokens);

            if (endpointName != null && !endpointName.isBlank()) {
                state.getDiagnostics().setLastEndpoint(endpointName);
            }
            if (modelName != null && !modelName.isBlank()) {
                state.getDiagnostics().setLastModel(modelName);
            }
            if (errorMessage != null && !errorMessage.isBlank()) {
                phaseMetrics.setErrorCount(phaseMetrics.getErrorCount() + 1);
                state.getDiagnostics().setLastErrorType("LLM_ERROR");
                state.getDiagnostics().setLastErrorMessage(trim(errorMessage, 500));
            }

            // 添加增强的 LLM Trace（包含原始 HTTP）
            appendLlmTraceWithRawHttp(
                    state.getDiagnostics(),
                    traceId,
                    runId,
                    phase,
                    stage,
                    tokenUsage,
                    cachedTokens,
                    durationMs,
                    startedAtMillis,
                    completedAtMillis,
                    endpointName,
                    modelName,
                    errorMessage,
                    effectiveThinking,
                    streamingProgress,
                    httpRequest,
                    httpResponse,
                    curlCommand,
                    reasoning,
                    attempts == null ? List.of() : attempts
            );
        });
        return traceId;
    }

    /**
     * 补充 Spending 信息（OpenRouter 异步回调）
     *
     * @param runId     Run ID
     * @param traceId   LLM Trace ID
     * @param actualCost    OpenRouter 总费用
     * @param upstreamCost  OpenRouter 上游成本
     * @param cacheDiscount OpenRouter 缓存折扣
     * @param isByok        OpenRouter 是否 BYOK
     */
    public void enrichLlmCallSpending(String runId,
                                      String traceId,
                                      Double actualCost,
                                      Double upstreamCost,
                                      Double cacheDiscount,
                                      Boolean isByok) {
        if (runId == null || runId.isBlank() || traceId == null || traceId.isBlank()) {
            return;
        }
        mutate(runId, state -> {
            if (state.getDiagnostics() == null || state.getDiagnostics().getLlmTraces() == null) {
                return;
            }
            for (LlmTrace trace : state.getDiagnostics().getLlmTraces()) {
                if (traceId.equals(trace.getTraceId())) {
                    trace.setActualCost(actualCost);
                    trace.setUpstreamCost(upstreamCost);
                    trace.setCacheDiscount(cacheDiscount);
                    trace.setIsByok(isByok);
                    break;
                }
            }
        });
    }

    private SpendingExtraction extractOpenRouterSpending(String rawResponseBody) {
        if (rawResponseBody == null || rawResponseBody.isBlank()) {
            return SpendingExtraction.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(rawResponseBody);
            JsonNode usage = root.path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return SpendingExtraction.empty();
            }

            Double actualCost = readDouble(usage.get("cost"));
            Boolean isByok = usage.has("is_byok") && !usage.get("is_byok").isNull()
                    ? usage.get("is_byok").asBoolean()
                    : null;

            JsonNode details = usage.path("cost_details");
            Double upstreamCost = readDouble(details.get("upstream_inference_cost"));
            if (upstreamCost == null && !details.isMissingNode() && !details.isNull()) {
                Double promptCost = readDouble(details.get("upstream_inference_prompt_cost"));
                Double completionCost = readDouble(details.get("upstream_inference_completions_cost"));
                if (promptCost != null || completionCost != null) {
                    upstreamCost = (promptCost == null ? 0D : promptCost)
                            + (completionCost == null ? 0D : completionCost);
                }
            }

            return new SpendingExtraction(actualCost, upstreamCost, null, isByok);
        } catch (Exception e) {
            log.debug("Failed to extract OpenRouter spending from response body: {}", e.getMessage());
            return SpendingExtraction.empty();
        }
    }

    private Double readDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 记录一次工具调用 trace 并更新 run 级与 phase 级指标。
     *
     * <p>由 {@link world.willfrog.agent.tool.ToolRouter#recordObservability} 调用。
     * 缓存相关字段（cacheEligible / cacheHit / cacheSource / ttlRemainingMs / estimatedSaved）
     * 会汇总到 summary 的 cacheHits / cacheMisses / cacheHitRate 中。</p>
     *
     * @param runId                    Run ID
     * @param phase                    阶段标识
     * @param toolName                 工具名
     * @param params                   工具参数（会被 sanitize 后存入 trace）
     * @param output                   工具输出（超出限制会截断）
     * @param durationMs               工具执行耗时
     * @param success                  是否成功
     * @param cacheEligible            是否可缓存（不可缓存的工具不计入命中率）
     * @param cacheHit                 是否命中缓存
     * @param cacheKey                 缓存键
     * @param cacheSource              缓存来源（如 "redis"、"local"）
     * @param cacheTtlRemainingMs      缓存剩余 TTL（毫秒）
     * @param estimatedSavedDurationMs 估算节省的耗时（缓存命中时累计到 summary）
     * @param errorMessage             错误信息（失败时填充 lastErrorMessage）
     */
    public void recordToolCall(String runId,
                               String phase,
                               String toolName,
                               Map<String, Object> params,
                               String output,
                               long durationMs,
                               boolean success,
                               boolean cacheEligible,
                               boolean cacheHit,
                               String cacheKey,
                               String cacheSource,
                               long cacheTtlRemainingMs,
                               long estimatedSavedDurationMs,
                               String errorMessage) {
        if (log.isDebugEnabled()) {
            log.debug("OBS_TOOL runId={} phase={} tool={} durationMs={} success={} cacheEligible={} cacheHit={} cacheSource={}",
                    runId, normalizePhase(phase), nvl(toolName), clampDuration(durationMs), success,
                    cacheEligible, cacheHit, nvl(cacheSource));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", newTraceId());
            payload.put("runId", runId);
            payload.put("phase", normalizePhase(phase));
            payload.put("stage", nvl(AgentContext.getStage()));
            payload.put("tool", nvl(toolName));
            payload.put("durationMs", clampDuration(durationMs));
            payload.put("success", success);
            payload.put("params", sanitizeRequestSnapshot(params));
            payload.put("outputPreview", preview(output, llmTraceTextLimit()));
            payload.put("todoId", nvl(AgentContext.getTodoId()));
            payload.put("todoSequence", AgentContext.getTodoSequence());
            payload.put("subAgentStepIndex", AgentContext.getSubAgentStepIndex());
            payload.put("pythonRefineAttempt", AgentContext.getPythonRefineAttempt());
            payload.put("decisionLlmTraceId", nvl(AgentContext.getDecisionTraceId()));
            payload.put("decisionStage", nvl(AgentContext.getDecisionStage()));
            payload.put("decisionExcerpt", trim(AgentContext.getDecisionExcerpt(), 500));
            payload.put("cache", Map.of(
                    "eligible", cacheEligible,
                    "hit", cacheHit,
                    "key", nvl(cacheKey),
                    "source", nvl(cacheSource),
                    "ttlRemainingMs", cacheTtlRemainingMs,
                    "estimatedSavedDurationMs", Math.max(0L, estimatedSavedDurationMs)
            ));
            payload.put("error", trim(errorMessage, 500));
            debugFileWriter.write("OBS_TOOL", payload);
        }
        mutate(runId, state -> {
            // 累加 run 级工具调用计数与缓存命中率
            state.getSummary().setToolCalls(state.getSummary().getToolCalls() + 1);
            updateCacheSummary(state.getSummary(), cacheEligible, cacheHit, estimatedSavedDurationMs);
            // 累加 phase 维度的工具调用计数和耗时
            PhaseMetrics phaseMetrics = phaseMetrics(state, phase);
            phaseMetrics.setCount(phaseMetrics.getCount() + 1);
            phaseMetrics.setToolCalls(phaseMetrics.getToolCalls() + 1);
            phaseMetrics.setDurationMs(phaseMetrics.getDurationMs() + clampDuration(durationMs));
            state.getDiagnostics().setLastTool(nvl(toolName));
            if (!success) {
                phaseMetrics.setErrorCount(phaseMetrics.getErrorCount() + 1);
                if (errorMessage != null && !errorMessage.isBlank()) {
                    state.getDiagnostics().setLastErrorType("TOOL_ERROR");
                    state.getDiagnostics().setLastErrorMessage(trim(errorMessage, 500));
                }
            }
            // append ToolTrace 到 diagnostics.toolTraces 列表（无容量上限，靠 trim 截断单条文本控制大小）
            appendToolTrace(
                    state.getDiagnostics(),
                    runId,
                    phase,
                    toolName,
                    params,
                    output,
                    durationMs,
                    success,
                    cacheEligible,
                    cacheHit,
                    cacheKey,
                    cacheSource,
                    cacheTtlRemainingMs,
                    estimatedSavedDurationMs,
                    errorMessage
            );
        });
    }

    /**
     * 单独累加某 phase 的耗时（不计调用次数）。
     *
     * <p>用于记录某个阶段独立度量的耗时（如 planning 整体耗时包含多个 LLM 调用之外的等待时间）。</p>
     */
    public void recordPhaseDuration(String runId, String phase, long durationMs) {
        mutate(runId, state -> {
            PhaseMetrics phaseMetrics = phaseMetrics(state, phase);
            phaseMetrics.setDurationMs(phaseMetrics.getDurationMs() + clampDuration(durationMs));
        });
    }

    /**
     * 记录 run 失败：将 summary.status 置为 FAILED，并写入最近一次错误类型与摘要。
     *
     * <p>由 {@link AgentRunExecutor#doExecute} 的 catch 分支调用，
     * 用于即使 run 出现未预期异常时也保留观测数据落盘。</p>
     */
    public void recordFailure(String runId, String errorType, String errorMessage) {
        mutate(runId, state -> {
            state.getSummary().setStatus(AgentRunStatus.FAILED.name());
            state.getDiagnostics().setLastErrorType(nvl(errorType));
            state.getDiagnostics().setLastErrorMessage(trim(errorMessage, 500));
        });
    }

    /**
     * 累加 plan 修复尝试次数（按 failureCategory 分类计入不同计数器）。
     *
     * <p>分类（大小写不敏感）：</p>
     * <ul>
     *   <li>STATIC — 静态错误（如 schema 校验失败）</li>
     *   <li>SEMANTIC — 语义错误（如 Judge 判定结果不合理）</li>
     *   <li>其他/默认 — 视为运行时错误</li>
     * </ul>
     */
    public void incrementRecoveryAttempt(String runId, String failureCategory) {
        mutate(runId, state -> {
            Summary summary = state.getSummary();
            String category = nvl(failureCategory).trim().toUpperCase();
            switch (category) {
                case "STATIC" -> summary.setStaticRecoveryAttempts(summary.getStaticRecoveryAttempts() + 1);
                case "SEMANTIC" -> summary.setSemanticRecoveryAttempts(summary.getSemanticRecoveryAttempts() + 1);
                default -> summary.setRuntimeRecoveryAttempts(summary.getRuntimeRecoveryAttempts() + 1);
            }
        });
    }

    /**
     * 记录一次 Semantic Judge 调用（用于评估 LLM 输出质量），可选标记 rejected。
     *
     * <p>仅累加计数器，不写入完整 trace。</p>
     */
    public void recordSemanticJudgeCall(String runId, boolean rejected) {
        mutate(runId, state -> {
            Summary summary = state.getSummary();
            summary.setSemanticJudgeCalls(summary.getSemanticJudgeCalls() + 1);
            if (rejected) {
                summary.setSemanticJudgeRejects(summary.getSemanticJudgeRejects() + 1);
            }
        });
    }

    /**
     * 标记本 run 的 planning 阶段是否走的是结构化输出（JSON schema 约束）路径。
     */
    public void markPlanningStructured(String runId, boolean enabled) {
        mutate(runId, state -> state.getDiagnostics().setPlanningStructured(enabled));
    }

    /**
     * 累加 planning 尝试次数。
     *
     * <p>主 planner 和子 agent planner 分别记录到 planningAttempts 和 subAgentPlanningAttempts，
     * 用于区分主链路重试与子代理链路重试。</p>
     *
     * @param subAgentPlanning true 表示这是子代理的 planning 调用，false 表示主 planner
     */
    public void incrementPlanningAttempts(String runId, boolean subAgentPlanning) {
        mutate(runId, state -> {
            Diagnostics diagnostics = state.getDiagnostics();
            if (subAgentPlanning) {
                long value = diagnostics.getSubAgentPlanningAttempts() == null ? 0L : diagnostics.getSubAgentPlanningAttempts();
                diagnostics.setSubAgentPlanningAttempts(value + 1L);
                return;
            }
            long value = diagnostics.getPlanningAttempts() == null ? 0L : diagnostics.getPlanningAttempts();
            diagnostics.setPlanningAttempts(value + 1L);
        });
    }

    /** 设置最近一次 planning 错误分类（供前端展示与告警归因）。 */
    public void setLastPlanningErrorCategory(String runId, String category) {
        mutate(runId, state -> state.getDiagnostics().setLastPlanningErrorCategory(nvl(category)));
    }

    /**
     * 加载完整观测 JSON：优先从 Redis 取，失败时从 snapshotJson 的 observability 字段兜底。
     *
     * <p>这是查询接口的统一入口，被 run 详情查询、credits 计算等链路调用。
     * 若 Redis 中存在缓存，会顺便统计 traces 数量写入日志便于排查"trace 凭空消失"。</p>
     *
     * @param runId        Run ID
     * @param snapshotJson DB 中的 snapshot JSON（作为 Redis 兜底来源）
     * @return 观测数据 JSON 字符串；都不存在时返回空字符串
     */
    public String loadObservabilityJson(String runId, String snapshotJson) {
        Optional<String> cached = stateStore.loadObservability(runId);
        if (cached.isPresent()) {
            int llmTraces = 0;
            int toolTraces = 0;
            try {
                ObservabilityState state = objectMapper.readValue(cached.get(), ObservabilityState.class);
                if (state.getDiagnostics() != null) {
                    llmTraces = state.getDiagnostics().getLlmTraces() != null ? state.getDiagnostics().getLlmTraces().size() : 0;
                    toolTraces = state.getDiagnostics().getToolTraces() != null ? state.getDiagnostics().getToolTraces().size() : 0;
                }
            } catch (Exception e) {
                log.debug("Failed to parse observability for metrics: runId={}", runId);
            }
            log.info("Observability loaded from Redis: runId={}, size={} bytes, llmTraces={}, toolTraces={}",
                    runId, cached.get().length(), llmTraces, toolTraces);
            return cached.get();
        }

        // Redis miss：尝试从 snapshot.observability 字段读取（终态写回的最后一份兜底）
        Map<String, Object> snapshot = parseJsonObject(snapshotJson);
        Object observability = snapshot.get("observability");
        if (observability != null) {
            String json = safeWrite(observability);
            log.warn("Observability fallback to snapshot: runId={}, size={} bytes", runId, json.length());
            return json;
        }

        log.error("Observability not found anywhere: runId={}, snapshotLength={}, redisKey={}",
                runId, snapshotJson == null ? 0 : snapshotJson.length(),
                "agent:run:" + runId + ":observability");
        return "";
    }

    /**
     * 加载观测摘要 JSON（不含完整 traces 列表，仅含 summary + phases + diagnostics 元数据 + trace 计数）。
     *
     * <p>用于 run 列表页等无需完整 trace 的场景，减少传输量。
     * 内部先调用 {@link #loadObservabilityJson} 取完整数据，再通过
     * {@link #buildSummaryMap} 裁剪为摘要视图。</p>
     */
    public String loadObservabilitySummaryJson(String runId, String snapshotJson) {
        String full = loadObservabilityJson(runId, snapshotJson);
        if (full == null || full.isBlank()) {
            return "";
        }
        try {
            ObservabilityState state = objectMapper.readValue(full, ObservabilityState.class);
            return safeWrite(buildSummaryMap(state));
        } catch (Exception e) {
            // 反序列化失败时（可能是历史 schema 字段不匹配），按通用 Map 解析做兜底裁剪
            Map<String, Object> parsed = parseJsonObject(full);
            return safeWrite(buildSummaryMap(parsed));
        }
    }

    /**
     * 判断指定 run 是否有完整观测数据可供详情页加载。
     *
     * <p>用途：前端可据此判断是否需要展示"完整观测"入口。</p>
     */
    public boolean isFullObservabilityAvailable(String runId, String snapshotJson) {
        Optional<String> cached = stateStore.loadObservability(runId);
        if (cached.isPresent() && !cached.get().isBlank()) {
            return true;
        }
        Map<String, Object> snapshot = parseJsonObject(snapshotJson);
        return snapshot.get("observability") != null;
    }

    /**
     * 将观测数据附加到 run 的 snapshot JSON 中，并同步保存到 Redis。
     *
     * <p>这是 run 终态（COMPLETED/FAILED/CANCELED）写回 DB 前的最后一步：</p>
     * <ol>
     *   <li>mutate：更新 summary.status 和 completedAtMillis（终态时）。</li>
     *   <li>把 observability 序列化后塞入 snapshot Map 的 {@code observability} 字段。</li>
     *   <li>同步保存观测 JSON 到 Redis，避免后续查询从 snapshot 字段慢解析。</li>
     *   <li>若 run 已进入终态，移除 per-runId 锁以释放内存。</li>
     * </ol>
     *
     * @param runId        Run ID
     * @param snapshotJson 当前 snapshot JSON（可为 null 或非法 JSON，会自动兜底）
     * @param status       目标状态
     * @return 附加完观测数据后的 snapshot JSON
     */
    public String attachObservabilityToSnapshot(String runId, String snapshotJson, AgentRunStatus status) {
        int llmTracesBefore = 0;
        int toolTracesBefore = 0;
        try {
            ObservabilityState currentState = loadState(runId);
            llmTracesBefore = currentState.getDiagnostics().getLlmTraces().size();
            toolTracesBefore = currentState.getDiagnostics().getToolTraces().size();
        } catch (Exception e) {
            log.debug("Could not load current state for metrics: runId={}", runId);
        }

        ObservabilityState state = mutate(runId, current -> {
            if (status != null) {
                current.getSummary().setStatus(status.name());
            }
            // 终态时锁定 completedAtMillis，后续 touch 不会再覆盖 totalDurationMs
            if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED) {
                current.getSummary().setCompletedAtMillis(System.currentTimeMillis());
            }
        });
        Map<String, Object> snapshot = parseJsonObject(snapshotJson);
        Map<String, Object> observabilityMap = objectMapper.convertValue(state, new TypeReference<Map<String, Object>>() {
        });
        snapshot.put("observability", observabilityMap);
        String output = safeWrite(snapshot);

        // 强制同步保存可观测数据到 Redis，确保后续可以立即加载
        String observabilityJson = safeWrite(observabilityMap);
        stateStore.saveObservability(runId, observabilityJson);

        int llmTracesAfter = state.getDiagnostics().getLlmTraces().size();
        int toolTracesAfter = state.getDiagnostics().getToolTraces().size();
        log.info("Observability attached to snapshot: runId={}, status={}, llmTraces {}→{}, toolTraces {}→{}, snapshotSize={} bytes, redisSize={} bytes",
                runId, status, llmTracesBefore, llmTracesAfter, toolTracesBefore, toolTracesAfter,
                output.length(), observabilityJson.length());

        // 终态后清理 per-runId 锁，避免长期占用内存
        if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED) {
            locks.remove(runId);
        }
        return output;
    }
    
    /**
     * 强制刷新可观测数据到 Redis 并返回当前状态。
     * 用于 cancel/pause 等需要确保数据立即落盘的场景。
     * 
     * @param runId Run ID
     * @return 当前 ObservabilityState，如果未找到则返回 null
     */
    public ObservabilityState forceFlush(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        Object lock = locks.computeIfAbsent(runId, key -> new Object());
        synchronized (lock) {
            ObservabilityState state = loadState(runId);
            String json = safeWrite(state);
            stateStore.saveObservability(runId, json);
            log.info("Observability force flushed: runId={}, llmTraces={}, toolTraces={}, size={} bytes", 
                    runId, 
                    state.getDiagnostics().getLlmTraces().size(),
                    state.getDiagnostics().getToolTraces().size(),
                    json.length());
            return state;
        }
    }

    /**
     * 从 snapshot JSON 中抽取 run 列表展示所需的轻量指标（不解析完整 trace）。
     *
     * <p>用于 run 列表分页接口，避免每个 run 都反序列化完整 observability 对象。
     * 仅提取 totalDurationMs、totalTokens、toolCalls 三个字段。</p>
     */
    public ListMetrics extractListMetrics(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return new ListMetrics(0L, 0, 0);
        }
        Map<String, Object> snapshot = parseJsonObject(snapshotJson);
        Object observability = snapshot.get("observability");
        if (!(observability instanceof Map<?, ?> obsMap)) {
            return new ListMetrics(0L, 0, 0);
        }
        Object summary = ((Map<?, ?>) obsMap).get("summary");
        if (!(summary instanceof Map<?, ?> summaryMap)) {
            return new ListMetrics(0L, 0, 0);
        }
        long durationMs = toLong(summaryMap.get("totalDurationMs"));
        int totalTokens = (int) toLong(summaryMap.get("totalTokens"));
        int toolCalls = (int) toLong(summaryMap.get("toolCalls"));
        return new ListMetrics(durationMs, totalTokens, toolCalls);
    }

    /**
     * 原子地读取-修改-写回观测状态，是所有写入路径的统一收口。
     *
     * <p>使用 per-runId 锁保证同一 run 的并发写入串行化；
     * 每次 mutate 后都强制把 state 序列化为 JSON 写回 Redis，
     * 这样后续 loadState 总能取到最新结果，但代价是每次 mutate 都有一次 Redis 往返。</p>
     *
     * @param runId   Run ID
     * @param updater 在加锁状态下对 state 的修改操作
     * @return 修改后的 state（同时已写回 Redis）
     */
    private ObservabilityState mutate(String runId, Consumer<ObservabilityState> updater) {
        Object lock = locks.computeIfAbsent(runId, key -> new Object());
        synchronized (lock) {
            ObservabilityState state = loadState(runId);
            int tracesBefore = state.getDiagnostics().getLlmTraces().size();
            int toolTracesBefore = state.getDiagnostics().getToolTraces().size();

            updater.accept(state);
            // touch：更新 startedAt（首次）、totalDurationMs、cacheHitRate、updatedAt
            touch(state);

            String json = safeWrite(state);
            stateStore.saveObservability(runId, json);

            int tracesAfter = state.getDiagnostics().getLlmTraces().size();
            int toolTracesAfter = state.getDiagnostics().getToolTraces().size();
            log.debug("Observability mutated: runId={}, llmTraces {}→{}, toolTraces {}→{}, size={} bytes",
                    runId, tracesBefore, tracesAfter, toolTracesBefore, toolTracesAfter, json.length());

            return state;
        }
    }

    /**
     * 从 Redis 加载观测状态，并对各种缺失字段做默认值兜底。
     *
     * <p>历史 run 可能产生时观测 schema 还没有某些字段，反序列化后需要把 null 字段
     * 设置为合理默认（空列表、0、false 等），以免后续 NPE。</p>
     *
     * @return 解析失败或不存在时返回新建的空状态
     */
    private ObservabilityState loadState(String runId) {
        Optional<String> existing = stateStore.loadObservability(runId);
        if (existing.isEmpty()) {
            log.debug("Observability state not found in Redis, creating new: runId={}", runId);
            return newState();
        }
        try {
            ObservabilityState parsed = objectMapper.readValue(existing.get(), ObservabilityState.class);
            log.debug("Observability state loaded from Redis: runId={}, size={} bytes, llmTraces={}, toolTraces={}", 
                    runId, existing.get().length(),
                    parsed.getDiagnostics().getLlmTraces() != null ? parsed.getDiagnostics().getLlmTraces().size() : 0,
                    parsed.getDiagnostics().getToolTraces() != null ? parsed.getDiagnostics().getToolTraces().size() : 0);
            
            if (parsed.getSummary() == null) {
                parsed.setSummary(new Summary());
            }
            if (parsed.getPhases() == null || parsed.getPhases().isEmpty()) {
                parsed.setPhases(defaultPhases());
            }
            if (parsed.getDiagnostics() == null) {
                parsed.setDiagnostics(new Diagnostics());
            }
            if (parsed.getDiagnostics().getLlmTraces() == null) {
                parsed.getDiagnostics().setLlmTraces(new ArrayList<>());
            }
            if (parsed.getDiagnostics().getToolTraces() == null) {
                parsed.getDiagnostics().setToolTraces(new ArrayList<>());
            }
            if (parsed.getDiagnostics().getPlanningStructured() == null) {
                parsed.getDiagnostics().setPlanningStructured(false);
            }
            if (parsed.getDiagnostics().getPlanningAttempts() == null) {
                parsed.getDiagnostics().setPlanningAttempts(0L);
            }
            if (parsed.getDiagnostics().getSubAgentPlanningAttempts() == null) {
                parsed.getDiagnostics().setSubAgentPlanningAttempts(0L);
            }
            if (parsed.getDiagnostics().getLastPlanningErrorCategory() == null) {
                parsed.getDiagnostics().setLastPlanningErrorCategory("");
            }
            ensurePhaseKeys(parsed);
            return parsed;
        } catch (Exception e) {
            log.warn("Parse observability state failed, fallback empty state: runId={}, error={}", runId, e.getMessage());
            return newState();
        }
    }

    /**
     * 更新 summary 的衍生字段：startedAt（首次填充）、totalDurationMs（结束时间或当前时间 - 起始时间）、
     * cacheHitRate（基于 cacheHits / cacheMisses 重算）、diagnostics.updatedAt。
     */
    private void touch(ObservabilityState state) {
        long now = System.currentTimeMillis();
        Summary summary = state.getSummary();
        if (summary.getStartedAtMillis() <= 0) {
            summary.setStartedAtMillis(now);
        }
        long end = summary.getCompletedAtMillis() > 0 ? summary.getCompletedAtMillis() : now;
        summary.setTotalDurationMs(Math.max(0, end - summary.getStartedAtMillis()));
        recomputeCacheHitRate(summary);
        recomputeEstimatedCost(summary, state.getDiagnostics());
        state.getDiagnostics().setUpdatedAt(OffsetDateTime.now().toString());
    }

    private void recomputeEstimatedCost(Summary summary, Diagnostics diagnostics) {
        if (summary == null || diagnostics == null || diagnostics.getLlmTraces() == null) {
            return;
        }
        if (diagnostics.getLlmTraces().isEmpty()) {
            summary.setEstimatedCost(null);
            return;
        }
        double total = 0D;
        boolean found = false;
        for (LlmTrace trace : diagnostics.getLlmTraces()) {
            if (trace.getActualCost() != null) {
                total += Math.max(0D, trace.getActualCost());
                found = true;
            }
        }
        summary.setEstimatedCost(found ? total : null);
    }

    /** applyTokens 的快捷重载，cachedTokens 默认为 null。 */
    private void applyTokens(Summary summary, PhaseMetrics phaseMetrics, TokenUsage usage) {
        applyTokens(summary, phaseMetrics, usage, null);
    }

    /**
     * 将本次 LLM 调用的 token 用量累加到 summary 与 phaseMetrics。
     *
     * <p>缺省字段（null）按 0 处理；total 缺省时回退到 input + output。
     * 所有累加值都做 Math.max(0, ...) 保护，避免上游传入负数或异常值导致累计倒退。
     * cachedTokens 仅在 captureCachedTokens 开关开启时累加。</p>
     */
    private void applyTokens(Summary summary, PhaseMetrics phaseMetrics, TokenUsage usage, Integer cachedTokens) {
        if (usage == null) {
            return;
        }
        long input = usage.inputTokenCount() == null ? 0L : usage.inputTokenCount();
        long output = usage.outputTokenCount() == null ? 0L : usage.outputTokenCount();
        long total = usage.totalTokenCount() == null ? input + output : usage.totalTokenCount();
        summary.setInputTokens(summary.getInputTokens() + Math.max(0L, input));
        summary.setOutputTokens(summary.getOutputTokens() + Math.max(0L, output));
        summary.setTotalTokens(summary.getTotalTokens() + Math.max(0L, total));
        phaseMetrics.setInputTokens(phaseMetrics.getInputTokens() + Math.max(0L, input));
        phaseMetrics.setOutputTokens(phaseMetrics.getOutputTokens() + Math.max(0L, output));
        phaseMetrics.setTotalTokens(phaseMetrics.getTotalTokens() + Math.max(0L, total));
        if (captureCachedTokens && cachedTokens != null && cachedTokens > 0) {
            summary.setCachedTokens(summary.getCachedTokens() + cachedTokens);
            phaseMetrics.setCachedTokens(phaseMetrics.getCachedTokens() + cachedTokens);
        }
    }

    /**
     * 更新工具调用缓存命中汇总。
     *
     * <p>仅在工具被标记为 cacheEligible 时计入命中率分母，
     * 否则该次调用既不计 hit 也不计 miss（避免不可缓存的工具拖低命中率）。</p>
     */
    private void updateCacheSummary(Summary summary, boolean cacheEligible, boolean cacheHit, long estimatedSavedDurationMs) {
        if (!cacheEligible) {
            return;
        }
        if (cacheHit) {
            summary.setCacheHits(summary.getCacheHits() + 1);
            summary.setEstimatedSavedDurationMs(summary.getEstimatedSavedDurationMs() + Math.max(0L, estimatedSavedDurationMs));
        } else {
            summary.setCacheMisses(summary.getCacheMisses() + 1);
        }
    }

    /** 基于 cacheHits / (cacheHits + cacheMisses) 重算命中率，分母为 0 时填 0。 */
    private void recomputeCacheHitRate(Summary summary) {
        long hits = Math.max(0L, summary.getCacheHits());
        long misses = Math.max(0L, summary.getCacheMisses());
        long total = hits + misses;
        if (total <= 0L) {
            summary.setCacheHitRate(0D);
            return;
        }
        summary.setCacheHitRate((double) hits / (double) total);
    }

    /** 取出或创建指定 phase 的指标桶（未知 phase 将归类到 tool_execution）。 */
    private PhaseMetrics phaseMetrics(ObservabilityState state, String phase) {
        String normalized = normalizePhase(phase);
        return state.getPhases().computeIfAbsent(normalized, key -> new PhaseMetrics());
    }

    /**
     * 规范化 phase 字符串：转小写、限制为预定义五种之一，未知值兜底为 tool_execution。
     *
     * <p>这层规范化保证 phaseMetrics 的 key 始终是固定枚举，便于前端按 key 取数。</p>
     */
    private String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return PHASE_TOOL_EXECUTION;
        }
        String normalized = phase.trim().toLowerCase();
        return switch (normalized) {
            case PHASE_PLANNING, PHASE_PARALLEL_EXECUTION, PHASE_SUB_AGENT, PHASE_TOOL_EXECUTION, PHASE_SUMMARIZING -> normalized;
            default -> PHASE_TOOL_EXECUTION;
        };
    }

    /** 构造一个全新的、字段都初始化为合理默认值的 ObservabilityState。 */
    private ObservabilityState newState() {
        ObservabilityState state = new ObservabilityState();
        state.setSummary(new Summary());
        Diagnostics diagnostics = new Diagnostics();
        diagnostics.setLlmTraces(new ArrayList<>());
        diagnostics.setToolTraces(new ArrayList<>());
        diagnostics.setPlanningStructured(false);
        diagnostics.setPlanningAttempts(0L);
        diagnostics.setSubAgentPlanningAttempts(0L);
        diagnostics.setLastPlanningErrorCategory("");
        state.setDiagnostics(diagnostics);
        state.setPhases(defaultPhases());
        return state;
    }

    /** 构造预定义五种 phase 的指标桶（按插入顺序），用于初始化和补齐缺失 key。 */
    private Map<String, PhaseMetrics> defaultPhases() {
        Map<String, PhaseMetrics> phases = new LinkedHashMap<>();
        phases.put(PHASE_PLANNING, new PhaseMetrics());
        phases.put(PHASE_PARALLEL_EXECUTION, new PhaseMetrics());
        phases.put(PHASE_SUB_AGENT, new PhaseMetrics());
        phases.put(PHASE_TOOL_EXECUTION, new PhaseMetrics());
        phases.put(PHASE_SUMMARIZING, new PhaseMetrics());
        return phases;
    }

    /** 历史状态可能缺少某些 phase key，加载后补齐为空指标桶以避免前端取数 null。 */
    private void ensurePhaseKeys(ObservabilityState state) {
        state.getPhases().putIfAbsent(PHASE_PLANNING, new PhaseMetrics());
        state.getPhases().putIfAbsent(PHASE_PARALLEL_EXECUTION, new PhaseMetrics());
        state.getPhases().putIfAbsent(PHASE_SUB_AGENT, new PhaseMetrics());
        state.getPhases().putIfAbsent(PHASE_TOOL_EXECUTION, new PhaseMetrics());
        state.getPhases().putIfAbsent(PHASE_SUMMARIZING, new PhaseMetrics());
    }

    /** 安全地把 JSON 字符串解析为 Map，失败时返回空 Map（不抛异常）。 */
    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** 安全 JSON 序列化：失败时返回 {@code "{}"}，永不抛出。 */
    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 基于 ObservabilityState 构建摘要 Map（不含 traces 列表，只含 trace 数量）。
     *
     * <p>用于 {@link #loadObservabilitySummaryJson} 的轻量视图。</p>
     */
    private Map<String, Object> buildSummaryMap(ObservabilityState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (state == null) {
            return out;
        }
        out.put("summary", state.getSummary() == null ? Map.of() : state.getSummary());
        out.put("phases", state.getPhases() == null ? Map.of() : state.getPhases());
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        Diagnostics d = state.getDiagnostics();
        if (d != null) {
            diagnostics.put("lastModel", nvl(d.getLastModel()));
            diagnostics.put("lastEndpoint", nvl(d.getLastEndpoint()));
            diagnostics.put("lastTool", nvl(d.getLastTool()));
            diagnostics.put("lastErrorType", nvl(d.getLastErrorType()));
            diagnostics.put("lastErrorMessage", nvl(d.getLastErrorMessage()));
            diagnostics.put("planningStructured", d.getPlanningStructured());
            diagnostics.put("planningAttempts", d.getPlanningAttempts());
            diagnostics.put("subAgentPlanningAttempts", d.getSubAgentPlanningAttempts());
            diagnostics.put("lastPlanningErrorCategory", nvl(d.getLastPlanningErrorCategory()));
            diagnostics.put("updatedAt", nvl(d.getUpdatedAt()));
            diagnostics.put("streamingProgress", d.getStreamingProgress());
            diagnostics.put("llmTraceCount", d.getLlmTraces() == null ? 0 : d.getLlmTraces().size());
            diagnostics.put("toolTraceCount", d.getToolTraces() == null ? 0 : d.getToolTraces().size());
        }
        out.put("diagnostics", diagnostics);
        return out;
    }

    /**
     * 同 {@link #buildSummaryMap(ObservabilityState)}，但接收已解析为 Map 的原始 JSON。
     * 作为反序列化失败时的兜底路径，保证至少能输出可读的摘要视图。
     */
    private Map<String, Object> buildSummaryMap(Map<String, Object> full) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object summary = full.get("summary");
        Object phases = full.get("phases");
        out.put("summary", summary instanceof Map<?, ?> ? summary : Map.of());
        out.put("phases", phases instanceof Map<?, ?> ? phases : Map.of());
        Map<String, Object> diagnosticsOut = new LinkedHashMap<>();
        Object diagnostics = full.get("diagnostics");
        if (diagnostics instanceof Map<?, ?> d) {
            for (String key : List.of(
                    "lastModel", "lastEndpoint", "lastTool", "lastErrorType", "lastErrorMessage",
                    "planningStructured", "planningAttempts", "subAgentPlanningAttempts",
                    "lastPlanningErrorCategory", "updatedAt", "streamingProgress")) {
                diagnosticsOut.put(key, d.get(key));
            }
            Object llmTraces = d.get("llmTraces");
            Object toolTraces = d.get("toolTraces");
            diagnosticsOut.put("llmTraceCount", llmTraces instanceof List<?> list ? list.size() : 0);
            diagnosticsOut.put("toolTraceCount", toolTraces instanceof List<?> list ? list.size() : 0);
        }
        out.put("diagnostics", diagnosticsOut);
        return out;
    }

    /** 将任意对象转 long，兼容 Number 与字符串，失败返回 0。 */
    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 钳制耗时值到非负数。 */
    private long clampDuration(long durationMs) {
        return Math.max(0L, durationMs);
    }

    /** 将字符串截断到 maxChars 长度（null 安全），不追加省略号。 */
    private String trim(String value, int maxChars) {
        String text = nvl(value);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    /** null -> 空字符串。 */
    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** LLM trace 文本字段长度上限（配置项异常时回退 20000）。 */
    private int llmTraceTextLimit() {
        return llmTraceMaxTextChars <= 0 ? 20000 : llmTraceMaxTextChars;
    }

    /** LLM reasoning 文本长度上限（配置项异常时回退 20000）。 */
    private int llmTraceReasoningLimit() {
        return llmTraceReasoningMaxChars <= 0 ? 20000 : llmTraceReasoningMaxChars;
    }

    /** 工具调用输出字段长度上限（配置项异常时回退 100000）。 */
    private int toolTraceOutputLimit() {
        return toolTraceMaxOutputChars <= 0 ? 100000 : toolTraceMaxOutputChars;
    }

    /** LlmTrace 列表容量上限（配置项异常时回退 100）。 */
    private int llmTraceCallLimit() {
        return llmTraceMaxCalls <= 0 ? 100 : llmTraceMaxCalls;
    }

    /** 生成新的 traceId（UUID 去掉短横线，32 位十六进制）。 */
    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 向 diagnostics.llmTraces 追加一条不含原始 HTTP 的 LlmTrace。
     *
     * <p>本方法不主动开启 trace 捕获：仅当 {@link #shouldCaptureLlmTrace} 判断当前 run
     * 启用了 LLM trace 时才追加。同时检查并裁剪超过 {@link #llmTraceCallLimit()} 的最旧记录。</p>
     */
    private void appendLlmTrace(Diagnostics diagnostics,
                                String traceId,
                                String runId,
                                String phase,
                                String stage,
                                TokenUsage tokenUsage,
                                long durationMs,
                                long startedAtMillis,
                                long completedAtMillis,
                                String endpointName,
                                String modelName,
                                String errorMessage,
                                Map<String, Object> requestSnapshot,
                                String responsePreview,
                                ReasoningExtraction reasoning) {
        if (!shouldCaptureLlmTrace(diagnostics)) {
            return;
        }
        if (diagnostics.getLlmTraces() == null) {
            diagnostics.setLlmTraces(new ArrayList<>());
        }
        List<LlmTrace> traces = diagnostics.getLlmTraces();
        LlmTrace trace = new LlmTrace();
        trace.setTraceId(nvl(traceId));
        trace.setTime(OffsetDateTime.now().toString());
        trace.setRunId(nvl(runId));
        trace.setPhase(normalizePhase(phase));
        trace.setStage(nvl(stage));
        trace.setDurationMs(clampDuration(durationMs));
        trace.setStartedAtMillis(startedAtMillis > 0 ? startedAtMillis : 0);
        trace.setCompletedAtMillis(completedAtMillis > 0 ? completedAtMillis : 0);
        trace.setEndpoint(nvl(endpointName));
        trace.setModel(nvl(modelName));
        trace.setHasError(errorMessage != null && !errorMessage.isBlank());
        trace.setError(trim(errorMessage, 1000));
        trace.setRequest(requestSnapshot);
        trace.setResponsePreview(responsePreview);
        trace.setInputMessages(requestSnapshot);
        trace.setOutputText(responsePreview);
        trace.setTodoId(nvl(AgentContext.getTodoId()));
        trace.setTodoSequence(AgentContext.getTodoSequence());
        trace.setReasoningText(reasoning == null ? "" : reasoning.text());
        trace.setReasoningDetails(reasoning == null ? null : reasoning.details());
        trace.setReasoningTruncated(reasoning != null && reasoning.truncated());
        // 设置 Token 统计
        if (tokenUsage != null) {
            trace.setInputTokens(tokenUsage.inputTokenCount() != null ? tokenUsage.inputTokenCount().longValue() : null);
            trace.setOutputTokens(tokenUsage.outputTokenCount() != null ? tokenUsage.outputTokenCount().longValue() : null);
            trace.setTotalTokens(tokenUsage.totalTokenCount() != null ? tokenUsage.totalTokenCount().longValue() : null);
        }
        traces.add(trace);
        int limit = llmTraceCallLimit();
        while (traces.size() > limit) {
            traces.remove(0);
        }
    }
    
    /**
     * 添加带有原始 HTTP 信息的 LLM Trace（ALP-25 内部方法）。
     * 
     * <p>将完整的 HTTP 观测数据添加到 Diagnostics.llmTraces 列表中。</p>
     * 
     * <p><b>数据结构说明：</b></p>
     * <ul>
     *   <li>httpRequest: 包含 URL、method、headers、body、timestamp</li>
     *   <li>httpResponse: 包含 statusCode、headers、body、durationMs、timestamp</li>
     *   <li>curlCommand: 可直接执行的 curl 命令字符串</li>
     *   <li>request/responsePreview: 向后兼容的字段（@Deprecated）</li>
     * </ul>
     * 
     * <p><b>存储限制：</b></p>
     * <p>llmTraces 列表受 {@link #llmTraceCallLimit()} 限制，
     * 超出限制时会移除最旧的记录。</p>
     * 
     * @param diagnostics 观测状态对象（会被修改）
     * @param runId Run ID
     * @param phase 阶段标识
     * @param durationMs 耗时
     * @param endpointName Endpoint 名称
     * @param modelName 模型名称
     * @param errorMessage 错误信息
     * @param httpRequest 原始 HTTP 请求记录
     * @param httpResponse 原始 HTTP 响应记录
     * @param curlCommand curl 命令
     */
    private void appendLlmTraceWithRawHttp(
            Diagnostics diagnostics,
            String traceId,
            String runId,
            String phase,
            String stage,
            TokenUsage tokenUsage,
            Integer cachedTokens,
            long durationMs,
            long startedAtMillis,
            long completedAtMillis,
            String endpointName,
            String modelName,
            String errorMessage,
            String thinkingContent,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress,
            RawHttpLogger.HttpRequestRecord httpRequest,
            RawHttpLogger.HttpResponseRecord httpResponse,
            String curlCommand,
            ReasoningExtraction reasoning,
            List<Map<String, Object>> attempts) {

        if (!shouldCaptureLlmTrace(diagnostics)) {
            return;
        }

        if (diagnostics.getLlmTraces() == null) {
            diagnostics.setLlmTraces(new ArrayList<>());
        }

        List<LlmTrace> traces = diagnostics.getLlmTraces();
        LlmTrace trace = new LlmTrace();
        trace.setTraceId(nvl(traceId));
        trace.setTime(OffsetDateTime.now().toString());
        trace.setRunId(nvl(runId));
        trace.setPhase(normalizePhase(phase));
        trace.setStage(nvl(stage));
        trace.setDurationMs(clampDuration(durationMs));
        trace.setStartedAtMillis(startedAtMillis > 0 ? startedAtMillis : 0);
        trace.setCompletedAtMillis(completedAtMillis > 0 ? completedAtMillis : 0);
        trace.setEndpoint(nvl(endpointName));
        trace.setModel(nvl(modelName));
        trace.setHasError(errorMessage != null && !errorMessage.isBlank());
        trace.setError(trim(errorMessage, 1000));
        trace.setReasoningText(thinkingContent != null ? thinkingContent : (reasoning == null ? "" : reasoning.text()));
        trace.setReasoningDetails(reasoning == null ? null : reasoning.details());
        trace.setReasoningTruncated(reasoning != null && reasoning.truncated());
        if (streamingProgress != null) {
            LlmTrace.StreamingProgress sp = new LlmTrace.StreamingProgress();
            sp.setContentCharCount(streamingProgress.contentCharCount());
            sp.setReasoningCharCount(streamingProgress.reasoningCharCount());
            sp.setToolCallCharCount(streamingProgress.toolCallCharCount());
            sp.setTotalCharCount(streamingProgress.totalCharCount());
            sp.setChunkCount(streamingProgress.chunkCount());
            sp.setDurationMs(streamingProgress.durationMs());
            sp.setCharsPerSecond(streamingProgress.charsPerSecond());
            trace.setStreamingProgress(sp);
        }
        // 设置 Token 统计
        if (tokenUsage != null) {
            trace.setInputTokens(tokenUsage.inputTokenCount() != null ? tokenUsage.inputTokenCount().longValue() : null);
            trace.setOutputTokens(tokenUsage.outputTokenCount() != null ? tokenUsage.outputTokenCount().longValue() : null);
            trace.setTotalTokens(tokenUsage.totalTokenCount() != null ? tokenUsage.totalTokenCount().longValue() : null);
        }
        trace.setCachedTokens(captureCachedTokens ? cachedTokens : null);

        SpendingExtraction spending = extractOpenRouterSpending(httpResponse == null ? null : httpResponse.getBody());
        trace.setActualCost(spending.actualCost());
        trace.setUpstreamCost(spending.upstreamCost());
        trace.setCacheDiscount(spending.cacheDiscount());
        trace.setIsByok(spending.isByok());
        trace.setGenerationId(extractOpenRouterGenerationId(httpResponse == null ? null : httpResponse.getBody()));
        
        // 设置原始 HTTP 请求信息
        if (httpRequest != null) {
            RawHttpTrace reqTrace = new RawHttpTrace();
            reqTrace.setUrl(httpRequest.getUrl());
            reqTrace.setMethod(httpRequest.getMethod());
            reqTrace.setStatusCode(0); // 请求没有状态码
            reqTrace.setHeaders(httpRequest.getHeaders());
            reqTrace.setBody(httpRequest.getBody());
            reqTrace.setDurationMs(0);
            reqTrace.setTimestamp(httpRequest.getTimestamp());
            trace.setHttpRequest(reqTrace);
        }
        
        // 设置原始 HTTP 响应信息
        if (httpResponse != null) {
            RawHttpTrace respTrace = new RawHttpTrace();
            respTrace.setUrl(null); // 响应没有 URL
            respTrace.setMethod(null); // 响应没有方法
            respTrace.setStatusCode(httpResponse.getStatusCode());
            respTrace.setHeaders(httpResponse.getHeaders());
            respTrace.setBody(httpResponse.getBody());
            respTrace.setDurationMs(httpResponse.getDurationMs());
            respTrace.setTimestamp(httpResponse.getTimestamp());
            trace.setHttpResponse(respTrace);
        }
        
        // 设置 curl 命令
        trace.setCurlCommand(curlCommand);
        trace.setAttempts(attempts == null ? List.of() : attempts);
        
        // 保留向后兼容的字段
        trace.setRequest(null);
        trace.setResponsePreview(httpResponse != null ? preview(httpResponse.getBody(), llmTraceTextLimit()) : null);
        
        // 设置新的 inputMessages / outputText 字段
        if (httpRequest != null && httpRequest.getBody() != null && !httpRequest.getBody().isBlank()) {
            try {
                Map<String, Object> body = objectMapper.readValue(httpRequest.getBody(), new TypeReference<Map<String, Object>>() {});
                trace.setInputMessages(sanitizeRequestSnapshot(body));
            } catch (Exception e) {
                // 解析失败时，将 body 原文作为 inputMessages 的一部分
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("raw", preview(httpRequest.getBody(), llmTraceTextLimit()));
                trace.setInputMessages(fallback);
            }
        }
        trace.setOutputText(httpResponse != null ? preview(httpResponse.getBody(), llmTraceTextLimit()) : null);
        trace.setTodoId(nvl(AgentContext.getTodoId()));
        trace.setTodoSequence(AgentContext.getTodoSequence());

        traces.add(trace);

        int limit = llmTraceCallLimit();
        while (traces.size() > limit) {
            traces.remove(0);
        }
    }

    private String extractOpenRouterGenerationId(String rawResponseBody) {
        if (rawResponseBody == null || rawResponseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(rawResponseBody);
            JsonNode id = root.get("id");
            return id != null && id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
        } catch (Exception e) {
            log.debug("Failed to extract OpenRouter generation id from response body: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 追加一条工具调用 trace 到 diagnostics.toolTraces。
     *
     * <p>从 {@link AgentContext} 取出 todo/stage/sub-agent 关联字段一并写入 trace，
     * 用于前端按 todo 维度聚合展示。输出文本受 {@link #toolTraceOutputLimit()} 截断。</p>
     */
    private void appendToolTrace(Diagnostics diagnostics,
                                 String runId,
                                 String phase,
                                 String toolName,
                                 Map<String, Object> params,
                                 String output,
                                 long durationMs,
                                 boolean success,
                                 boolean cacheEligible,
                                 boolean cacheHit,
                                 String cacheKey,
                                 String cacheSource,
                                 long cacheTtlRemainingMs,
                                 long estimatedSavedDurationMs,
                                 String errorMessage) {
        if (diagnostics == null) {
            return;
        }
        if (diagnostics.getToolTraces() == null) {
            diagnostics.setToolTraces(new ArrayList<>());
        }
        ToolTrace trace = new ToolTrace();
        trace.setTraceId(newTraceId());
        trace.setTime(OffsetDateTime.now().toString());
        trace.setRunId(nvl(runId));
        trace.setPhase(normalizePhase(phase));
        trace.setStage(nvl(AgentContext.getStage()));
        trace.setTodoId(nvl(AgentContext.getTodoId()));
        trace.setTodoSequence(AgentContext.getTodoSequence());
        trace.setSubAgentStepIndex(AgentContext.getSubAgentStepIndex());
        trace.setPythonRefineAttempt(AgentContext.getPythonRefineAttempt());
        trace.setToolName(nvl(toolName));
        trace.setParams(sanitizeRequestSnapshot(params));
        trace.setSuccess(success);
        trace.setDurationMs(clampDuration(durationMs));
        trace.setCacheEligible(cacheEligible);
        trace.setCacheHit(cacheHit);
        trace.setCacheKey(nvl(cacheKey));
        trace.setCacheSource(nvl(cacheSource));
        trace.setCacheTtlRemainingMs(cacheTtlRemainingMs);
        trace.setEstimatedSavedDurationMs(Math.max(0L, estimatedSavedDurationMs));
        trace.setOutput(preview(output, toolTraceOutputLimit()));
        trace.setError(trim(errorMessage, 1000));
        trace.setDecisionLlmTraceId(nvl(AgentContext.getDecisionTraceId()));
        trace.setDecisionStage(nvl(AgentContext.getDecisionStage()));
        trace.setDecisionExcerpt(trim(AgentContext.getDecisionExcerpt(), 1000));
        diagnostics.getToolTraces().add(trace);
    }
    
    /**
     * 文本截断预览：超过 maxChars 时截断并追加 {@code "...[truncated]"} 标记。
     *
     * <p>与 {@link #trim(String, int)} 的差别在于本方法会显式标注截断，
     * 用于面向用户/调试日志展示的预览字段，而 trim 用于存储字段（不希望污染原文）。</p>
     */
    private String preview(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...[truncated]";
    }

    /**
     * 解析当前调用归属的 stage。
     *
     * <p>优先级：AgentContext.stage &gt; requestSnapshot.meta.stage &gt; requestSnapshot.stage &gt; ""。
     * stage 通常表示 planning / execution / final_answer 等大阶段。</p>
     */
    private String resolveStage(Map<String, Object> requestSnapshot) {
        String current = nvl(AgentContext.getStage()).trim();
        if (!current.isBlank()) {
            return current;
        }
        if (requestSnapshot == null || requestSnapshot.isEmpty()) {
            return "";
        }
        Object meta = requestSnapshot.get("meta");
        if (meta instanceof Map<?, ?> metaMap) {
            Object stage = metaMap.get("stage");
            if (stage != null) {
                return String.valueOf(stage);
            }
        }
        Object stage = requestSnapshot.get("stage");
        return stage == null ? "" : String.valueOf(stage);
    }

    /**
     * 从 LLM 响应原文（通常是 OpenAI 兼容协议 JSON）中提取 reasoning/thinking 文本。
     *
     * <p>不同 provider 把推理过程放在不同字段，本方法递归遍历 JSON 找到第一个匹配字段。
     * 解析失败或 payload 为非 JSON 时返回 empty。</p>
     */
    private ReasoningExtraction extractReasoning(String payload) {
        if (payload == null || payload.isBlank()) {
            return ReasoningExtraction.empty();
        }
        Object parsed = payload;
        try {
            parsed = objectMapper.readValue(payload, Object.class);
        } catch (Exception ignored) {
            return ReasoningExtraction.empty();
        }
        return extractReasoningFromValue(parsed, 0);
    }

    /**
     * 递归遍历 JSON 值寻找 reasoning 字段，深度上限为 8 以防 stack overflow。
     */
    private ReasoningExtraction extractReasoningFromValue(Object value, int depth) {
        if (value == null || depth > 8) {
            return ReasoningExtraction.empty();
        }
        if (value instanceof Map<?, ?> map) {
            ReasoningExtraction direct = readDirectReasoning(map);
            if (direct.hasText()) {
                return direct;
            }
            for (Object child : map.values()) {
                ReasoningExtraction nested = extractReasoningFromValue(child, depth + 1);
                if (nested.hasText()) {
                    return nested;
                }
            }
            return ReasoningExtraction.empty();
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                ReasoningExtraction nested = extractReasoningFromValue(item, depth + 1);
                if (nested.hasText()) {
                    return nested;
                }
            }
            return ReasoningExtraction.empty();
        }
        return ReasoningExtraction.empty();
    }

    /**
     * 在单个 Map 上直接尝试取一组已知 reasoning 字段名。
     *
     * <p>支持的字段名按优先级：reasoning、reasoning_content / reasoningContent、
     * reasoning_text / reasoningText、thinking、thought、content。
     * 同时把 reasoning_details / reasoningDetails 作为结构化细节字段一并取出。</p>
     */
    private ReasoningExtraction readDirectReasoning(Map<?, ?> map) {
        Object details = map.get("reasoning_details");
        if (details == null) {
            details = map.get("reasoningDetails");
        }
        for (String key : List.of(
                "reasoning",
                "reasoning_content",
                "reasoningContent",
                "reasoning_text",
                "reasoningText",
                "thinking",
                "thought",
                "content"
        )) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            ReasoningExtraction extracted = reasoningFromObject(value, details);
            if (extracted.hasText()) {
                return extracted;
            }
        }
        return ReasoningExtraction.empty();
    }

    /**
     * 把候选 reasoning 字段值统一转换为 ReasoningExtraction：
     * 字符串直接使用；其他类型先经过 sanitizeForTrace 再 JSON 序列化为文本，
     * 最后截断到 {@link #llmTraceReasoningLimit()}。
     */
    private ReasoningExtraction reasoningFromObject(Object value, Object detailsCandidate) {
        Object details = detailsCandidate == null ? value : detailsCandidate;
        String text;
        if (value instanceof String str) {
            text = str;
        } else {
            text = safeWrite(sanitizeForTrace(value, 0));
        }
        text = nvl(text).trim();
        if (text.isBlank()) {
            return ReasoningExtraction.empty();
        }
        int limit = llmTraceReasoningLimit();
        boolean truncated = text.length() > limit;
        String normalized = truncated ? text.substring(0, limit) : text;
        Object detailsPayload = sanitizeForTrace(details, 0);
        return new ReasoningExtraction(normalized, detailsPayload, truncated);
    }

    /**
     * 判断当前 run 是否需要记录 LLM trace。
     *
     * <p>全局开关 {@code llmTraceEnabled} 优先；其次看 diagnostics.captureLlmRequests
     * （run 级别的显式启用）。</p>
     */
    private boolean shouldCaptureLlmTrace(Diagnostics diagnostics) {
        if (diagnostics == null) {
            return false;
        }
        return llmTraceEnabled || Boolean.TRUE.equals(diagnostics.getCaptureLlmRequests());
    }

    /**
     * 把 ChatMessage 列表与 meta 组合成 LLM 请求快照。
     *
     * <p>messages 会逐条经过 {@link #serializeChatMessage} 序列化，meta 走 sanitize 流程。
     * 都为空时返回 null，避免在 trace 中留下空对象。</p>
     */
    private Map<String, Object> buildLlmRequestSnapshot(List<ChatMessage> requestMessages, Map<String, Object> requestMeta) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (requestMeta != null && !requestMeta.isEmpty()) {
            snapshot.put("meta", sanitizeForTrace(requestMeta, 0));
        }
        if (requestMessages != null && !requestMessages.isEmpty()) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (ChatMessage message : requestMessages) {
                messages.add(serializeChatMessage(message));
            }
            snapshot.put("messages", messages);
        }
        return snapshot.isEmpty() ? null : snapshot;
    }

    /**
     * 清洗请求快照：递归截断超长字符串、做循环引用保护，最终把所有键统一为 String。
     */
    private Map<String, Object> sanitizeRequestSnapshot(Map<String, Object> requestSnapshot) {
        if (requestSnapshot == null || requestSnapshot.isEmpty()) {
            return null;
        }
        Object sanitized = sanitizeForTrace(requestSnapshot, 0);
        if (!(sanitized instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    /**
     * 将单条 ChatMessage 序列化为 {@code {class, body}} 结构。
     *
     * <p>class 字段保留 Java 类全名，便于事后区分 SystemMessage / UserMessage 等；
     * body 字段通过 Jackson 转换为通用对象后再经 sanitize。</p>
     */
    private Map<String, Object> serializeChatMessage(ChatMessage message) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (message == null) {
            return output;
        }
        output.put("class", message.getClass().getName());
        try {
            Object raw = objectMapper.convertValue(message, Object.class);
            output.put("body", sanitizeForTrace(raw, 0));
        } catch (Exception e) {
            output.put("body", trim(String.valueOf(message), llmTraceTextLimit()));
        }
        return output;
    }

    /**
     * 递归清洗 trace 字段，主要做三件事：
     * <ol>
     *   <li>字符串值截断到 {@link #llmTraceTextLimit()}。</li>
     *   <li>Map 与 Collection 保留结构、递归处理子元素。</li>
     *   <li>其他类型（自定义对象）统一转 toString 后截断，避免奇怪的序列化失败。</li>
     * </ol>
     *
     * <p>深度上限 6 层（depth &gt;= 6 时直接转字符串），防御循环引用导致的栈溢出。</p>
     */
    private Object sanitizeForTrace(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth >= 6) {
            return trim(String.valueOf(value), llmTraceTextLimit());
        }
        if (value instanceof String str) {
            return trim(str, llmTraceTextLimit());
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sanitized.put(String.valueOf(entry.getKey()), sanitizeForTrace(entry.getValue(), depth + 1));
            }
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : collection) {
                sanitized.add(sanitizeForTrace(item, depth + 1));
            }
            return sanitized;
        }
        return trim(String.valueOf(value), llmTraceTextLimit());
    }

    /**
     * 整个 run 的观测状态根对象。
     *
     * <p>持久化形态：JSON 字符串，存于 Redis 的 {@code agent:run:<runId>:observability} key 下。
     * 同时在 run 完成时复制一份到 DB snapshot JSON 的 {@code observability} 字段。</p>
     */
    @Data
    public static class ObservabilityState {
        /** 全局聚合指标（调用次数、token 用量、缓存命中率等） */
        private Summary summary;
        /** 按 phase 维度聚合的指标桶（key 为 phase 字符串） */
        private Map<String, PhaseMetrics> phases;
        /** 诊断信息（trace 列表、最近一次错误、流式进度等） */
        private Diagnostics diagnostics;
    }

    /**
     * Run 级聚合指标。
     *
     * <p>字段在 mutate 过程中累加，最终在 {@link #touch} 中重算耗时与缓存命中率。</p>
     */
    @Data
    public static class Summary {
        /** LLM 调用总次数 */
        private long llmCalls;
        /** 工具调用总次数 */
        private long toolCalls;
        /** 工具结果缓存命中次数（仅统计 cacheEligible 的调用） */
        private long cacheHits;
        /** 工具结果缓存未命中次数 */
        private long cacheMisses;
        /** 缓存命中率（hits / (hits + misses)） */
        private Double cacheHitRate;
        /** 估算由缓存节省的总耗时（毫秒） */
        private long estimatedSavedDurationMs;
        /** Run 总耗时（结束时间或当前时间 - 起始时间） */
        private long totalDurationMs;
        /** DAG 节点数（线性模式下为 0） */
        private long nodeCount;
        /** 输入 token 累计 */
        private long inputTokens;
        /** 输出 token 累计 */
        private long outputTokens;
        /** 总 token 累计 */
        private long totalTokens;
        /** Prompt cache 命中 token 数（仅在 captureCachedTokens 开启时累计） */
        private long cachedTokens;
        /** 估算成本（保留字段，目前未在本服务中填充） */
        private Double estimatedCost;
        /** Run 起始时间戳（毫秒） */
        private long startedAtMillis;
        /** Run 完成时间戳（毫秒，未完成时为 0） */
        private long completedAtMillis;
        /** Run 状态字符串（来自 AgentRunStatus.name()） */
        private String status;
        /** 静态错误恢复尝试次数（如 schema 校验失败重试） */
        private long staticRecoveryAttempts;
        /** 运行时错误恢复尝试次数 */
        private long runtimeRecoveryAttempts;
        /** 语义错误恢复尝试次数 */
        private long semanticRecoveryAttempts;
        /** Semantic Judge 调用次数 */
        private long semanticJudgeCalls;
        /** Semantic Judge 拒绝次数（rejected=true） */
        private long semanticJudgeRejects;
    }

    /**
     * 单个 phase 的聚合指标。
     *
     * <p>同一 phase 的 LLM 调用和工具调用都累加到同一个桶。</p>
     */
    @Data
    public static class PhaseMetrics {
        /** 总调用次数（LLM + 工具） */
        private long count;
        /** 该 phase 累计耗时 */
        private long durationMs;
        /** 输入 token */
        private long inputTokens;
        /** 输出 token */
        private long outputTokens;
        /** 总 token */
        private long totalTokens;
        /** Prompt cache 命中 token 数 */
        private long cachedTokens;
        /** 错误次数（LLM 错误 + 工具失败） */
        private long errorCount;
        /** LLM 调用次数 */
        private long llmCalls;
        /** 工具调用次数 */
        private long toolCalls;
    }

    /**
     * 诊断信息：包含详细 trace 列表和最近错误/流式进度等运行时状态。
     */
    @Data
    public static class Diagnostics {
        /** 最近使用的模型名 */
        private String lastModel;
        /** 最近使用的 endpoint 名 */
        private String lastEndpoint;
        /** 最近调用的工具名 */
        private String lastTool;
        /** 最近一次错误类型（如 LLM_ERROR / TOOL_ERROR） */
        private String lastErrorType;
        /** 最近一次错误消息（截断到 500 字符） */
        private String lastErrorMessage;
        /** 是否启用了 planning 结构化输出 */
        private Boolean planningStructured;
        /** 主 planner 调用次数 */
        private Long planningAttempts;
        /** 子代理 planner 调用次数 */
        private Long subAgentPlanningAttempts;
        /** 最近一次 planning 错误分类 */
        private String lastPlanningErrorCategory;
        /** 状态最近更新时间（ISO-8601 字符串） */
        private String updatedAt;
        /** 是否对本 run 启用了 LLM 请求完整捕获（ALP-25） */
        private Boolean captureLlmRequests;
        /** 流式生成最新进度快照 */
        private StreamingProgressStatus streamingProgress;
        /** LLM 调用 trace 列表（受 llmTraceMaxCalls 限制 FIFO 截断） */
        private List<LlmTrace> llmTraces;
        /** 工具调用 trace 列表 */
        private List<ToolTrace> toolTraces;

        /**
         * 流式生成最新进度快照（供前端展示"正在生成…"）。
         */
        @Data
        public static class StreamingProgressStatus {
            private String phase;
            private String endpoint;
            private String model;
            private boolean completed;
            private String updatedAt;
            private int contentCharCount;
            private int reasoningCharCount;
            private int toolCallCharCount;
            private int totalCharCount;
            private int chunkCount;
            private long durationMs;
            private double charsPerSecond;
        }
    }

    /**
     * 单次 LLM 调用 trace。
     *
     * <p>包含基础元数据（traceId、time、endpoint、model）、token 用量、错误信息、
     * reasoning 文本、流式进度、关联的 todo、原始 HTTP 信息（ALP-25）等。
     * 同时保留向后兼容的 {@code request} / {@code responsePreview} 字段。</p>
     */
    @Data
    public static class LlmTrace {
        /** 本次调用的全局唯一 traceId（32 位十六进制） */
        private String traceId;
        /** 调用记录写入时间（ISO-8601） */
        private String time;
        /** Run ID */
        private String runId;
        /** 阶段（planning / parallel_execution / sub_agent / tool_execution / summarizing） */
        private String phase;
        /** stage（business 层 stage，与 phase 不同维度） */
        private String stage;
        /** 调用耗时（毫秒，钳制到非负） */
        private long durationMs;
        /** 调用开始时间戳（毫秒） */
        private long startedAtMillis;
        /** 调用结束时间戳（毫秒） */
        private long completedAtMillis;
        /** Endpoint 名 */
        private String endpoint;
        /** 模型名 */
        private String model;
        /** 是否发生错误 */
        private boolean hasError;
        /** 错误信息（截断到 1000 字符） */
        private String error;
        /** Reasoning/thinking 文本（截断到 reasoning-max-chars） */
        private String reasoningText;
        /** Reasoning 结构化细节（如步骤列表，原样保留经 sanitize） */
        private Object reasoningDetails;
        /** Reasoning 是否被截断 */
        private boolean reasoningTruncated;
        /** 流式生成快照（非流式调用为 null） */
        private StreamingProgress streamingProgress;

        // ========== 关联的 Todo/DAG 节点 ==========

        /** 关联的 Todo 任务 ID（DAG 模式下为节点 ID） */
        private String todoId;
        /** Todo 序号 */
        private Integer todoSequence;

        // ========== LLM 输入输出 ==========

        /** LLM 调用时的完整 messages 快照 */
        private Map<String, Object> inputMessages;
        /** LLM 响应文本（截断预览） */
        private String outputText;

        // ========== Token 统计 ==========

        /** 输入 Token 数 */
        private Long inputTokens;
        /** 输出 Token 数 */
        private Long outputTokens;
        /** 总 Token 数 */
        private Long totalTokens;

        // ========== Token Cache 追踪 ==========

        /** Cache 命中 token 数 */
        private Integer cachedTokens;

        // ========== OpenRouter Spending ==========

        /** OpenRouter: 总费用 */
        private Double actualCost;
        /** OpenRouter: 上游成本 */
        private Double upstreamCost;
        /** OpenRouter: 缓存折扣 */
        private Double cacheDiscount;
        /** OpenRouter: 是否 BYOK */
        private Boolean isByok;
        /** OpenRouter generation id，用于 run 结束后补采集费用。 */
        private String generationId;

        // ========== ALP-25 新增：原始 HTTP 信息 ==========

        /**
         * 原始 HTTP 请求信息（包含完整 URL、headers、body）
         * 可直接用于 curl 复现
         */
        private RawHttpTrace httpRequest;

        /**
         * 原始 HTTP 响应信息（包含 statusCode、headers、body）
         */
        private RawHttpTrace httpResponse;

        /** HTTP attempt 明细，表示一次 logical LLM call 内的重试过程。 */
        private List<Map<String, Object>> attempts;

        /**
         * 可直接执行的 curl 命令（Authorization 已脱敏）
         */
        private String curlCommand;

        // ========== 向后兼容的字段 ==========

        /**
         * @deprecated 使用 {@link #inputMessages} 替代
         * 保留用于向后兼容，内容为 LangChain4j 转换后的请求快照
         */
        @Deprecated
        private Map<String, Object> request;

        /**
         * @deprecated 使用 {@link #outputText} 替代
         * 保留用于向后兼容，仅包含响应文本预览
         */
        @Deprecated
        private String responsePreview;

        /**
         * 流式生成进度快照（写入 trace 用），与 Diagnostics.StreamingProgressStatus
         * 字段大体一致，区别是这里没有 phase/endpoint/model/completed/updatedAt。
         */
        @Data
        public static class StreamingProgress {
            private int contentCharCount;
            private int reasoningCharCount;
            private int toolCallCharCount;
            private int totalCharCount;
            private int chunkCount;
            private long durationMs;
            private double charsPerSecond;
        }
    }

    /**
     * 单次工具调用 trace。
     *
     * <p>包含工具名、参数、输出（截断）、缓存元数据、关联的 todo/sub-agent 上下文，
     * 以及导致本次工具调用的 LLM 决策摘要（decisionLlmTraceId / decisionStage / decisionExcerpt）。</p>
     */
    @Data
    public static class ToolTrace {
        /** 本次工具调用的 traceId */
        private String traceId;
        /** 写入时间（ISO-8601） */
        private String time;
        /** Run ID */
        private String runId;
        /** Phase */
        private String phase;
        /** Stage */
        private String stage;
        /** 关联的 Todo ID */
        private String todoId;
        /** Todo 序号 */
        private Integer todoSequence;
        /** 子代理步骤序号（spawnSubAgent 内部使用） */
        private Integer subAgentStepIndex;
        /** Python refine 重试序号（executePython 失败后修正 code 的尝试编号） */
        private Integer pythonRefineAttempt;
        /** 工具名 */
        private String toolName;
        /** 工具参数（已 sanitize） */
        private Map<String, Object> params;
        /** 是否成功 */
        private boolean success;
        /** 耗时 */
        private long durationMs;
        /** 是否可缓存 */
        private boolean cacheEligible;
        /** 是否命中缓存 */
        private boolean cacheHit;
        /** 缓存键 */
        private String cacheKey;
        /** 缓存来源（如 "redis"、"local"） */
        private String cacheSource;
        /** 缓存剩余 TTL */
        private long cacheTtlRemainingMs;
        /** 命中缓存节省的耗时 */
        private long estimatedSavedDurationMs;
        /** 工具输出（长度受配置 agent.observability.tool-trace.max-output-chars 控制） */
        private String output;
        /** 错误信息（失败时） */
        private String error;
        /** 触发本次工具调用的 LLM 决策 traceId（便于追溯） */
        private String decisionLlmTraceId;
        /** 决策所在 stage */
        private String decisionStage;
        /** 决策摘要（LLM 当时给的工具调用理由前 1000 字符） */
        private String decisionExcerpt;

        /**
         * @deprecated 使用 {@link #output} 替代
         * 保留用于向后兼容（JSON 反序列化）
         */
        @Deprecated
        public void setOutputPreview(String value) {
            this.output = value;
        }

        /**
         * @deprecated 使用 {@link #getOutput()} 替代
         */
        @Deprecated
        public String getOutputPreview() {
            return this.output;
        }
    }
    
    /**
     * 原始 HTTP 追踪记录
     */
    @Data
    public static class RawHttpTrace {
        /**
         * 请求 URL（如 https://api.fireworks.ai/inference/v1/chat/completions）
         */
        private String url;
        
        /**
         * HTTP 方法（如 POST）
         */
        private String method;
        
        /**
         * HTTP 状态码（仅响应有，请求为 0）
         */
        private int statusCode;
        
        /**
         * HTTP headers（敏感信息已脱敏）
         */
        private Map<String, String> headers;
        
        /**
         * HTTP body（JSON 字符串，已截断）
         */
        private String body;
        
        /**
         * 耗时（毫秒，仅响应有）
         */
        private long durationMs;
        
        /**
         * 时间戳
         */
        private long timestamp;
    }

    /**
     * 从 LLM 响应中提取的 reasoning 内部表示。
     *
     * @param text       reasoning 主体文本（已截断）
     * @param details    结构化细节（经 sanitize）
     * @param truncated  原始文本是否在截断中被裁掉
     */
    private record ReasoningExtraction(String text, Object details, boolean truncated) {
        /** 空提取（无 reasoning 字段时返回）。 */
        private static ReasoningExtraction empty() {
            return new ReasoningExtraction("", null, false);
        }

        /** 是否存在非空 reasoning 文本。 */
        private boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    /**
     * 从 OpenRouter Chat Completion 响应 usage 字段中提取的费用信息。
     *
     * <p>OpenRouter 的官方文档说明 usage.cost 是可选字段；如果响应体未携带，
     * 后续仍可由 Generation API 异步补充。</p>
     */
    private record SpendingExtraction(
            Double actualCost,
            Double upstreamCost,
            Double cacheDiscount,
            Boolean isByok) {

        private static SpendingExtraction empty() {
            return new SpendingExtraction(null, null, null, null);
        }
    }

    /**
     * Run 列表展示用的轻量指标三元组。
     *
     * @param durationMs   Run 总耗时
     * @param totalTokens  Run 总 token 数
     * @param toolCalls    Run 总工具调用次数
     */
    public record ListMetrics(long durationMs, int totalTokens, int toolCalls) {
    }
}
