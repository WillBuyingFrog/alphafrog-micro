package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run 级资源预算检查。
 */
@Service
@RequiredArgsConstructor
public class AgentRunBudgetService {

    private final AgentRunStateStore stateStore;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;

    @Value("${agent.run.budget.max-wall-clock-ms:600000}")
    private long maxWallClockMs;

    @Value("${agent.run.budget.max-llm-calls:50}")
    private long maxLlmCalls;

    @Value("${agent.run.budget.max-tool-calls:30}")
    private long maxToolCalls;

    @Value("${agent.run.budget.max-tokens:300000}")
    private long maxTokens;

    @Value("${agent.run.budget.max-http-attempts-per-logical-call:2}")
    private int maxHttpAttemptsPerLogicalCall;

    public void checkBeforeLlmCall() {
        check("llm_call");
    }

    public void checkBeforeToolCall() {
        check("tool_call");
    }

    public int maxHttpAttemptsPerLogicalCall() {
        return Math.max(1, maxHttpAttemptsPerLogicalCall);
    }

    public void checkHttpAttempt(int nextAttempt) {
        int max = maxHttpAttemptsPerLogicalCall();
        if (nextAttempt > max) {
            throw exceeded("http_attempts_per_logical_call", nextAttempt, max);
        }
    }

    private void check(String operation) {
        String runId = AgentContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        Map<String, Object> summary = loadSummary(runId);
        long startedAt = toLong(summary.get("startedAtMillis"));
        long elapsed = startedAt <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - startedAt);
        if (maxWallClockMs > 0 && elapsed > maxWallClockMs) {
            throw exceeded("wall_clock_ms", elapsed, maxWallClockMs);
        }
        long llmCalls = toLong(summary.get("llmCalls"));
        if ("llm_call".equals(operation) && maxLlmCalls > 0 && llmCalls >= maxLlmCalls) {
            throw exceeded("llm_calls", llmCalls, maxLlmCalls);
        }
        long toolCalls = toLong(summary.get("toolCalls"));
        if ("tool_call".equals(operation) && maxToolCalls > 0 && toolCalls >= maxToolCalls) {
            throw exceeded("tool_calls", toolCalls, maxToolCalls);
        }
        long tokens = toLong(summary.get("totalTokens"));
        if (maxTokens > 0 && tokens >= maxTokens) {
            throw exceeded("tokens", tokens, maxTokens);
        }
    }

    private IllegalStateException exceeded(String dimension, long actual, long limit) {
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dimension", dimension);
        payload.put("actual", actual);
        payload.put("limit", limit);
        if (runId != null && userId != null) {
            eventService.append(runId, userId, "RUN_BUDGET_EXCEEDED", payload);
        }
        return new IllegalStateException("RUN_BUDGET_EXCEEDED:" + dimension + ":" + actual + "/" + limit);
    }

    private Map<String, Object> loadSummary(String runId) {
        try {
            String json = stateStore.loadObservability(runId).orElse("");
            if (json.isBlank()) {
                return Map.of();
            }
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Object summary = root.get("summary");
            if (summary instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return out;
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.of();
    }

    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }
}
