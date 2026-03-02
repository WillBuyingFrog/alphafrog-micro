package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.TokenUsage;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.model.AgentRunStatus;

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

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentObservabilityService {

    public static final String PHASE_PLANNING = "planning";
    public static final String PHASE_PARALLEL_EXECUTION = "parallel_execution";
    public static final String PHASE_SUB_AGENT = "sub_agent";
    public static final String PHASE_TOOL_EXECUTION = "tool_execution";
    public static final String PHASE_SUMMARIZING = "summarizing";

    private final AgentRunStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final AgentObservabilityDebugFileWriter debugFileWriter;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Value("${agent.observability.llm-trace.enabled:false}")
    private boolean llmTraceEnabled;

    @Value("${agent.observability.llm-trace.max-calls:100}")
    private int llmTraceMaxCalls;

    @Value("${agent.observability.llm-trace.max-text-chars:20000}")
    private int llmTraceMaxTextChars;

    @Value("${agent.observability.llm-trace.capture-cached-tokens:true}")
    private boolean captureCachedTokens;

    @Value("${agent.observability.llm-trace.reasoning-max-chars:20000}")
    private int llmTraceReasoningMaxChars;

    public void initializeRun(String runId, String endpointName, String modelName) {
        initializeRun(runId, endpointName, modelName, false);
    }

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

    public void addNodeCount(String runId, int delta) {
        if (delta == 0) {
            return;
        }
        mutate(runId, state -> {
            long current = state.getSummary().getNodeCount();
            state.getSummary().setNodeCount(Math.max(0, current + delta));
        });
    }

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
            state.getSummary().setLlmCalls(state.getSummary().getLlmCalls() + 1);
            PhaseMetrics phaseMetrics = phaseMetrics(state, phase);
            phaseMetrics.setCount(phaseMetrics.getCount() + 1);
            phaseMetrics.setLlmCalls(phaseMetrics.getLlmCalls() + 1);
            phaseMetrics.setDurationMs(phaseMetrics.getDurationMs() + clampDuration(durationMs));
            applyTokens(state.getSummary(), phaseMetrics, tokenUsage);
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
            appendLlmTrace(state.getDiagnostics(), traceId, runId, phase, stage, durationMs, startedAtMillis, completedAtMillis,
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
                errorMessage, httpRequest, httpResponse, curlCommand);
    }

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
                endpointName, modelName, errorMessage, httpRequest, httpResponse, curlCommand);
    }

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
        String traceId = newTraceId();
        String stage = resolveStage(null);
        String rawResponseBody = httpResponse == null ? null : httpResponse.getBody();
        ReasoningExtraction reasoning = extractReasoning(rawResponseBody);
        
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
            payload.put("reasoningText", trim(reasoning.text(), 500));
            payload.put("reasoningTruncated", reasoning.truncated());
            debugFileWriter.write("OBS_LLM_RAW_HTTP", payload);
        }
        
        // 更新观测状态
        mutate(runId, state -> {
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
                    cachedTokens,
                    durationMs, 
                    startedAtMillis,
                    completedAtMillis,
                    endpointName, 
                    modelName, 
                    errorMessage,
                    httpRequest,
                    httpResponse,
                    curlCommand,
                    reasoning
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
            state.getSummary().setToolCalls(state.getSummary().getToolCalls() + 1);
            updateCacheSummary(state.getSummary(), cacheEligible, cacheHit, estimatedSavedDurationMs);
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

    public void recordPhaseDuration(String runId, String phase, long durationMs) {
        mutate(runId, state -> {
            PhaseMetrics phaseMetrics = phaseMetrics(state, phase);
            phaseMetrics.setDurationMs(phaseMetrics.getDurationMs() + clampDuration(durationMs));
        });
    }

    public void recordFailure(String runId, String errorType, String errorMessage) {
        mutate(runId, state -> {
            state.getSummary().setStatus(AgentRunStatus.FAILED.name());
            state.getDiagnostics().setLastErrorType(nvl(errorType));
            state.getDiagnostics().setLastErrorMessage(trim(errorMessage, 500));
        });
    }

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

    public void recordSemanticJudgeCall(String runId, boolean rejected) {
        mutate(runId, state -> {
            Summary summary = state.getSummary();
            summary.setSemanticJudgeCalls(summary.getSemanticJudgeCalls() + 1);
            if (rejected) {
                summary.setSemanticJudgeRejects(summary.getSemanticJudgeRejects() + 1);
            }
        });
    }

    public void markPlanningStructured(String runId, boolean enabled) {
        mutate(runId, state -> state.getDiagnostics().setPlanningStructured(enabled));
    }

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

    public void setLastPlanningErrorCategory(String runId, String category) {
        mutate(runId, state -> state.getDiagnostics().setLastPlanningErrorCategory(nvl(category)));
    }

    public String loadObservabilityJson(String runId, String snapshotJson) {
        Optional<String> cached = stateStore.loadObservability(runId);
        if (cached.isPresent()) {
            return cached.get();
        }
        Map<String, Object> snapshot = parseJsonObject(snapshotJson);
        Object observability = snapshot.get("observability");
        if (observability == null) {
            return "";
        }
        return safeWrite(observability);
    }

    public String attachObservabilityToSnapshot(String runId, String snapshotJson, AgentRunStatus status) {
        ObservabilityState state = mutate(runId, current -> {
            if (status != null) {
                current.getSummary().setStatus(status.name());
            }
            if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED) {
                current.getSummary().setCompletedAtMillis(System.currentTimeMillis());
            }
        });
        Map<String, Object> snapshot = parseJsonObject(snapshotJson);
        snapshot.put("observability", objectMapper.convertValue(state, new TypeReference<Map<String, Object>>() {
        }));
        String output = safeWrite(snapshot);
        if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED) {
            locks.remove(runId);
        }
        return output;
    }

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

    private ObservabilityState mutate(String runId, Consumer<ObservabilityState> updater) {
        Object lock = locks.computeIfAbsent(runId, key -> new Object());
        synchronized (lock) {
            ObservabilityState state = loadState(runId);
            updater.accept(state);
            touch(state);
            stateStore.saveObservability(runId, safeWrite(state));
            return state;
        }
    }

    private ObservabilityState loadState(String runId) {
        Optional<String> existing = stateStore.loadObservability(runId);
        if (existing.isEmpty()) {
            return newState();
        }
        try {
            ObservabilityState parsed = objectMapper.readValue(existing.get(), ObservabilityState.class);
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
            log.warn("Parse observability state failed, fallback empty state", e);
            return newState();
        }
    }

    private void touch(ObservabilityState state) {
        long now = System.currentTimeMillis();
        Summary summary = state.getSummary();
        if (summary.getStartedAtMillis() <= 0) {
            summary.setStartedAtMillis(now);
        }
        long end = summary.getCompletedAtMillis() > 0 ? summary.getCompletedAtMillis() : now;
        summary.setTotalDurationMs(Math.max(0, end - summary.getStartedAtMillis()));
        recomputeCacheHitRate(summary);
        state.getDiagnostics().setUpdatedAt(OffsetDateTime.now().toString());
    }

    private void applyTokens(Summary summary, PhaseMetrics phaseMetrics, TokenUsage usage) {
        applyTokens(summary, phaseMetrics, usage, null);
    }

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

    private PhaseMetrics phaseMetrics(ObservabilityState state, String phase) {
        String normalized = normalizePhase(phase);
        return state.getPhases().computeIfAbsent(normalized, key -> new PhaseMetrics());
    }

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

    private Map<String, PhaseMetrics> defaultPhases() {
        Map<String, PhaseMetrics> phases = new LinkedHashMap<>();
        phases.put(PHASE_PLANNING, new PhaseMetrics());
        phases.put(PHASE_PARALLEL_EXECUTION, new PhaseMetrics());
        phases.put(PHASE_SUB_AGENT, new PhaseMetrics());
        phases.put(PHASE_TOOL_EXECUTION, new PhaseMetrics());
        phases.put(PHASE_SUMMARIZING, new PhaseMetrics());
        return phases;
    }

    private void ensurePhaseKeys(ObservabilityState state) {
        state.getPhases().putIfAbsent(PHASE_PLANNING, new PhaseMetrics());
        state.getPhases().putIfAbsent(PHASE_PARALLEL_EXECUTION, new PhaseMetrics());
        state.getPhases().putIfAbsent(PHASE_SUB_AGENT, new PhaseMetrics());
        state.getPhases().putIfAbsent(PHASE_TOOL_EXECUTION, new PhaseMetrics());
        state.getPhases().putIfAbsent(PHASE_SUMMARIZING, new PhaseMetrics());
    }

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

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

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

    private long clampDuration(long durationMs) {
        return Math.max(0L, durationMs);
    }

    private String trim(String value, int maxChars) {
        String text = nvl(value);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private int llmTraceTextLimit() {
        return llmTraceMaxTextChars <= 0 ? 20000 : llmTraceMaxTextChars;
    }

    private int llmTraceReasoningLimit() {
        return llmTraceReasoningMaxChars <= 0 ? 20000 : llmTraceReasoningMaxChars;
    }

    private int llmTraceCallLimit() {
        return llmTraceMaxCalls <= 0 ? 100 : llmTraceMaxCalls;
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void appendLlmTrace(Diagnostics diagnostics,
                                String traceId,
                                String runId,
                                String phase,
                                String stage,
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
        trace.setReasoningText(reasoning == null ? "" : reasoning.text());
        trace.setReasoningDetails(reasoning == null ? null : reasoning.details());
        trace.setReasoningTruncated(reasoning != null && reasoning.truncated());
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
            Integer cachedTokens,
            long durationMs,
            long startedAtMillis,
            long completedAtMillis,
            String endpointName,
            String modelName,
            String errorMessage,
            RawHttpLogger.HttpRequestRecord httpRequest,
            RawHttpLogger.HttpResponseRecord httpResponse,
            String curlCommand,
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
        trace.setReasoningText(reasoning == null ? "" : reasoning.text());
        trace.setReasoningDetails(reasoning == null ? null : reasoning.details());
        trace.setReasoningTruncated(reasoning != null && reasoning.truncated());
        trace.setCachedTokens(captureCachedTokens ? cachedTokens : null);
        
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
        
        // 保留向后兼容的字段
        trace.setRequest(null);
        trace.setResponsePreview(httpResponse != null ? preview(httpResponse.getBody(), llmTraceTextLimit()) : null);
        
        traces.add(trace);
        
        int limit = llmTraceCallLimit();
        while (traces.size() > limit) {
            traces.remove(0);
        }
    }

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
        trace.setOutputPreview(preview(output, llmTraceTextLimit()));
        trace.setError(trim(errorMessage, 1000));
        trace.setDecisionLlmTraceId(nvl(AgentContext.getDecisionTraceId()));
        trace.setDecisionStage(nvl(AgentContext.getDecisionStage()));
        trace.setDecisionExcerpt(trim(AgentContext.getDecisionExcerpt(), 1000));
        diagnostics.getToolTraces().add(trace);
    }
    
    private String preview(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...[truncated]";
    }

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

    private boolean shouldCaptureLlmTrace(Diagnostics diagnostics) {
        if (diagnostics == null) {
            return false;
        }
        return llmTraceEnabled || Boolean.TRUE.equals(diagnostics.getCaptureLlmRequests());
    }

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

    @Data
    public static class ObservabilityState {
        private Summary summary;
        private Map<String, PhaseMetrics> phases;
        private Diagnostics diagnostics;
    }

    @Data
    public static class Summary {
        private long llmCalls;
        private long toolCalls;
        private long cacheHits;
        private long cacheMisses;
        private Double cacheHitRate;
        private long estimatedSavedDurationMs;
        private long totalDurationMs;
        private long nodeCount;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private long cachedTokens;
        private Double estimatedCost;
        private long startedAtMillis;
        private long completedAtMillis;
        private String status;
        private long staticRecoveryAttempts;
        private long runtimeRecoveryAttempts;
        private long semanticRecoveryAttempts;
        private long semanticJudgeCalls;
        private long semanticJudgeRejects;
    }

    @Data
    public static class PhaseMetrics {
        private long count;
        private long durationMs;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private long cachedTokens;
        private long errorCount;
        private long llmCalls;
        private long toolCalls;
    }

    @Data
    public static class Diagnostics {
        private String lastModel;
        private String lastEndpoint;
        private String lastTool;
        private String lastErrorType;
        private String lastErrorMessage;
        private Boolean planningStructured;
        private Long planningAttempts;
        private Long subAgentPlanningAttempts;
        private String lastPlanningErrorCategory;
        private String updatedAt;
        private Boolean captureLlmRequests;
        private List<LlmTrace> llmTraces;
        private List<ToolTrace> toolTraces;
    }

    @Data
    public static class LlmTrace {
        private String traceId;
        private String time;
        private String runId;
        private String phase;
        private String stage;
        private long durationMs;
        private long startedAtMillis;      // 调用开始时间
        private long completedAtMillis;    // 调用结束时间
        private String endpoint;
        private String model;
        private boolean hasError;
        private String error;
        private String reasoningText;
        private Object reasoningDetails;
        private boolean reasoningTruncated;
        
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
        
        /**
         * 可直接执行的 curl 命令（Authorization 已脱敏）
         */
        private String curlCommand;
        
        // ========== 向后兼容的字段 ==========
        
        /**
         * @deprecated 使用 {@link #httpRequest} 替代
         * 保留用于向后兼容，内容为 LangChain4j 转换后的请求快照
         */
        @Deprecated
        private Map<String, Object> request;
        
        /**
         * @deprecated 使用 {@link #httpResponse} 替代
         * 保留用于向后兼容，仅包含响应文本预览
         */
        @Deprecated
        private String responsePreview;
    }

    @Data
    public static class ToolTrace {
        private String traceId;
        private String time;
        private String runId;
        private String phase;
        private String stage;
        private String todoId;
        private Integer todoSequence;
        private Integer subAgentStepIndex;
        private Integer pythonRefineAttempt;
        private String toolName;
        private Map<String, Object> params;
        private boolean success;
        private long durationMs;
        private boolean cacheEligible;
        private boolean cacheHit;
        private String cacheKey;
        private String cacheSource;
        private long cacheTtlRemainingMs;
        private long estimatedSavedDurationMs;
        private String outputPreview;
        private String error;
        private String decisionLlmTraceId;
        private String decisionStage;
        private String decisionExcerpt;
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

    private record ReasoningExtraction(String text, Object details, boolean truncated) {
        private static ReasoningExtraction empty() {
            return new ReasoningExtraction("", null, false);
        }

        private boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    public record ListMetrics(long durationMs, int totalTokens, int toolCalls) {
    }
}
