package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run 级资源预算检查。
 *
 * <p>生效优先级：agent-llm.json（Nacos 热加载） &gt; agent.llm.runtime.runBudget &gt; agent.run.budget（@Value 启动默认）。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunBudgetService {

    private final AgentRunStateStore stateStore;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;
    private final AgentLlmProperties llmProperties;

    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    @Value("${agent.run.budget.max-wall-clock-ms:600000}")
    private long defaultMaxWallClockMs;

    @Value("${agent.run.budget.max-llm-calls:50}")
    private long defaultMaxLlmCalls;

    @Value("${agent.run.budget.max-tool-calls:30}")
    private long defaultMaxToolCalls;

    @Value("${agent.run.budget.max-tokens:300000}")
    private long defaultMaxTokens;

    @Value("${agent.run.budget.max-http-attempts-per-logical-call:2}")
    private int defaultMaxHttpAttemptsPerLogicalCall;

    public void checkBeforeLlmCall() {
        check("llm_call");
    }

    public void checkBeforeToolCall() {
        check("tool_call");
    }

    public int maxHttpAttemptsPerLogicalCall() {
        return Math.max(1, effectiveConfig().maxHttpAttemptsPerLogicalCall());
    }

    public EffectiveRunBudget effectiveConfig() {
        AgentLlmProperties.RunBudget local = resolveLocalRunBudget();
        AgentLlmProperties.RunBudget spring = resolveSpringRunBudget();
        return new EffectiveRunBudget(
                resolveLong(local, spring, defaultMaxWallClockMs, AgentLlmProperties.RunBudget::getMaxWallClockMs),
                resolveLong(local, spring, defaultMaxLlmCalls, AgentLlmProperties.RunBudget::getMaxLlmCalls),
                resolveLong(local, spring, defaultMaxToolCalls, AgentLlmProperties.RunBudget::getMaxToolCalls),
                resolveLong(local, spring, defaultMaxTokens, AgentLlmProperties.RunBudget::getMaxTokens),
                resolveInt(local, spring, defaultMaxHttpAttemptsPerLogicalCall,
                        AgentLlmProperties.RunBudget::getMaxHttpAttemptsPerLogicalCall)
        );
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
        EffectiveRunBudget budget = effectiveConfig();
        Map<String, Object> summary = loadSummary(runId);
        long startedAt = toLong(summary.get("startedAtMillis"));
        long elapsed = startedAt <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - startedAt);
        if (budget.maxWallClockMs() > 0 && elapsed > budget.maxWallClockMs()) {
            throw exceeded("wall_clock_ms", elapsed, budget.maxWallClockMs());
        }
        long llmCalls = toLong(summary.get("llmCalls"));
        if ("llm_call".equals(operation) && budget.maxLlmCalls() > 0 && llmCalls >= budget.maxLlmCalls()) {
            throw exceeded("llm_calls", llmCalls, budget.maxLlmCalls());
        }
        long toolCalls = toLong(summary.get("toolCalls"));
        if ("tool_call".equals(operation) && budget.maxToolCalls() > 0 && toolCalls >= budget.maxToolCalls()) {
            throw exceeded("tool_calls", toolCalls, budget.maxToolCalls());
        }
        long tokens = toLong(summary.get("totalTokens"));
        if (budget.maxTokens() > 0 && tokens >= budget.maxTokens()) {
            throw exceeded("tokens", tokens, budget.maxTokens());
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

    private AgentLlmProperties.RunBudget resolveLocalRunBudget() {
        if (localConfigLoader == null) {
            return null;
        }
        return localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getRunBudget)
                .orElse(null);
    }

    private AgentLlmProperties.RunBudget resolveSpringRunBudget() {
        if (llmProperties.getRuntime() == null) {
            return null;
        }
        return llmProperties.getRuntime().getRunBudget();
    }

    private long resolveLong(AgentLlmProperties.RunBudget local,
                             AgentLlmProperties.RunBudget spring,
                             long applicationDefault,
                             java.util.function.Function<AgentLlmProperties.RunBudget, Long> getter) {
        if (local != null && getter.apply(local) != null) {
            return getter.apply(local);
        }
        if (spring != null && getter.apply(spring) != null) {
            return getter.apply(spring);
        }
        return applicationDefault;
    }

    private int resolveInt(AgentLlmProperties.RunBudget local,
                           AgentLlmProperties.RunBudget spring,
                           int applicationDefault,
                           java.util.function.Function<AgentLlmProperties.RunBudget, Integer> getter) {
        if (local != null && getter.apply(local) != null) {
            return getter.apply(local);
        }
        if (spring != null && getter.apply(spring) != null) {
            return getter.apply(spring);
        }
        return applicationDefault;
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

    public record EffectiveRunBudget(
            long maxWallClockMs,
            long maxLlmCalls,
            long maxToolCalls,
            long maxTokens,
            int maxHttpAttemptsPerLogicalCall
    ) {
    }
}
