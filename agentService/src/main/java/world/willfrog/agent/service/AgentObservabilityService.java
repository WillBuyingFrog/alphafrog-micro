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
import world.willfrog.agent.model.AgentRunStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public void addNodeCount(String runId, int delta) {
        if (delta == 0) {
            return;
        }
        mutate(runId, state -> {
            long current = state.getSummary().getNodeCount();
            state.getSummary().setNodeCount(Math.max(0, current + delta));
        });
    }

    public void recordLlmCall(String runId,
                              String phase,
                              TokenUsage tokenUsage,
                              long durationMs,
                              String endpointName,
                              String modelName,
                              String errorMessage) {
        recordLlmCall(
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

    public void recordLlmCall(String runId,
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
        recordLlmCall(
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

    public void recordLlmCall(String runId,
                              String phase,
                              TokenUsage tokenUsage,
                              long durationMs,
                              String endpointName,
                              String modelName,
                              String errorMessage,
                              Map<String, Object> requestSnapshot,
                              String responseText) {
        Map<String, Object> sanitizedRequestSnapshot = sanitizeRequestSnapshot(requestSnapshot);
        String responsePreview = trim(responseText, llmTraceTextLimit());
        if (log.isDebugEnabled()) {
            log.debug("OBS_LLM runId={} phase={} durationMs={} endpoint={} model={} hasError={}",
                    runId, normalizePhase(phase), clampDuration(durationMs), nvl(endpointName), nvl(modelName),
                    errorMessage != null && !errorMessage.isBlank());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("phase", normalizePhase(phase));
            payload.put("durationMs", clampDuration(durationMs));
            payload.put("endpoint", nvl(endpointName));
            payload.put("model", nvl(modelName));
            payload.put("hasError", errorMessage != null && !errorMessage.isBlank());
            payload.put("error", trim(errorMessage, 500));
            payload.put("tokenUsage", tokenUsage == null ? null : Map.of(
                    "input", tokenUsage.inputTokenCount(),
                    "output", tokenUsage.outputTokenCount(),
                    "total", tokenUsage.totalTokenCount()
            ));
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
            appendLlmTrace(state.getDiagnostics(), runId, phase, durationMs, endpointName, modelName, errorMessage, sanitizedRequestSnapshot, responsePreview);
        });
    }

    public void recordToolCall(String runId,
                               String phase,
                               String toolName,
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
            payload.put("runId", runId);
            payload.put("phase", normalizePhase(phase));
            payload.put("tool", nvl(toolName));
            payload.put("durationMs", clampDuration(durationMs));
            payload.put("success", success);
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
        state.setDiagnostics(new Diagnostics());
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

    private int llmTraceCallLimit() {
        return llmTraceMaxCalls <= 0 ? 100 : llmTraceMaxCalls;
    }

    private void appendLlmTrace(Diagnostics diagnostics,
                                String runId,
                                String phase,
                                long durationMs,
                                String endpointName,
                                String modelName,
                                String errorMessage,
                                Map<String, Object> requestSnapshot,
                                String responsePreview) {
        if (!shouldCaptureLlmTrace(diagnostics)) {
            return;
        }
        if (diagnostics.getLlmTraces() == null) {
            diagnostics.setLlmTraces(new ArrayList<>());
        }
        List<LlmTrace> traces = diagnostics.getLlmTraces();
        LlmTrace trace = new LlmTrace();
        trace.setTime(OffsetDateTime.now().toString());
        trace.setRunId(nvl(runId));
        trace.setPhase(normalizePhase(phase));
        trace.setDurationMs(clampDuration(durationMs));
        trace.setEndpoint(nvl(endpointName));
        trace.setModel(nvl(modelName));
        trace.setHasError(errorMessage != null && !errorMessage.isBlank());
        trace.setError(trim(errorMessage, 1000));
        trace.setRequest(requestSnapshot);
        trace.setResponsePreview(responsePreview);
        traces.add(trace);
        int limit = llmTraceCallLimit();
        while (traces.size() > limit) {
            traces.remove(0);
        }
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
        private Double estimatedCost;
        private long startedAtMillis;
        private long completedAtMillis;
        private String status;
    }

    @Data
    public static class PhaseMetrics {
        private long count;
        private long durationMs;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
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
        private String updatedAt;
        private Boolean captureLlmRequests;
        private List<LlmTrace> llmTraces;
    }

    @Data
    public static class LlmTrace {
        private String time;
        private String runId;
        private String phase;
        private long durationMs;
        private String endpoint;
        private String model;
        private boolean hasError;
        private String error;
        private Map<String, Object> request;
        private String responsePreview;
    }

    public record ListMetrics(long durationMs, int totalTokens, int toolCalls) {
    }
}
