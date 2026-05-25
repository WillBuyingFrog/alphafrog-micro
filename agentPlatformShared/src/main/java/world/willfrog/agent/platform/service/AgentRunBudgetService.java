package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run 级资源预算（budget）检查服务 —— 从四个维度限制单个 agent run 的资源消耗。
 *
 * <h2>四维预算</h2>
 * <ol>
 *   <li><b>wall_clock_ms</b>（挂钟时间）：从 run 启动到当前的毫秒数，超限即终止。</li>
 *   <li><b>llm_calls</b>（LLM 调用次数）：仅 LLM 调用（非工具调用）触发检查，超过上限时拒绝后续 LLM 请求。</li>
 *   <li><b>tool_calls</b>（工具调用次数）：仅工具调用触发检查。</li>
 *   <li><b>tokens</b>（token 消耗）：每次 LLM 调用前后都检查，
 *       因为 token 的增长不发生在 LLM 调用中（调用前已消耗），也不是工具调用中（工具不消耗 token），
 *       所以两个入口都有。这是四维中最容易超的维度——复杂回测场景单个 LLM 请求就可能上报数十万 token。</li>
 * </ol>
 *
 * <h2>配置加载优先级</h2>
 * <ol>
 *   <li>Nacos 热加载：{@code agent-llm.local.json → runtime.runBudget.*}</li>
 *   <li>Spring 静态配置：{@code agent.llm.runtime.runBudget.*}</li>
 *   <li>启动默认值：{@code agent.run.budget.*}（本类 @Value 注解），硬编码 fallback</li>
 * </ol>
 *
 * <h2>检查时机</h2>
 * <ul>
 *   <li><b>LLM 调用前</b>：{@code OpenRouterProviderRoutedChatModel} 和 {@code DashScopeChatModel}
 *       在发 HTTP 请求之前调用 {@link #checkBeforeLlmCall()}。</li>
 *   <li><b>工具调用前</b>：{@code ToolRouter#invoke} 在执行工具之前调用
 *       {@link #checkBeforeToolCall()}。</li>
 *   <li><b>HTTP 重试检查</b>：{@link #checkHttpAttempt} 限制单次逻辑 LLM 调用的最大 HTTP 重试次数，
 *       防止网络抖动导致无限重试消耗资源。</li>
 * </ul>
 *
 * <h2>预算超限后的行为</h2>
 * <p>抛出 {@code IllegalStateException} 消息格式 {@code RUN_BUDGET_EXCEEDED:<维度>:<实际值>/<上限>}，
 * 同时向事件总线写入 {@code RUN_BUDGET_EXCEEDED} 事件（含维度、实际值、上限等 payload）。
 * 这个异常会被工具层的 {@code LangchainTerminalToolErrorHandler} 识别为不可重试的致命信号，
 * 并被 {@code LangchainFailureMapper} 映射为 {@code RunBudgetExceeded} 的可观测失败类型。</p>
 *
 * <h2>{@code EffectiveRunBudget} 记录</h2>
 * <p>封装了生效的五个预算维度值，每次调用 {@code check()} 时动态计算并读取。
 * 支持 Nacos 热加载——下一轮检查即可生效，无需重启 run。</p>
 *
 * <h2>面试常考点</h2>
 * <ul>
 *   <li>"为什么 llm_calls 和 tool_calls 分开检查？"→ 两者消耗模式不同：LLM 调用贵但低频，工具调用便宜但高频。
 *       分开计数可以更精细地控制预算分配。</li>
 *   <li>"tokens 为什么比 llm_calls 更容易超？"→ 一次复杂回测的 planning 阶段可能消耗 50K+ token，
 *       而 llm_calls 可能还不到 10 次。</li>
 *   <li>"预算超限后 run 怎么处理？"→ 抛异常 → TerminalToolErrorHandler 识别 → FailureMapper 分类 →
 *       Pipeline 写入失败事件 → 前端看到 RUN_BUDGET_EXCEEDED。</li>
 * </ul>
 *
 * @see OpenRouterProviderRoutedChatModel LLM 调用前检查的消费方
 * @see world.willfrog.agent.tools.router.ToolRouter 工具调用前检查的消费方
 * @see world.willfrog.agentlangchain.failure.LangchainFailureMapper 预算超限异常的分类映射
 */
@Service
@RequiredArgsConstructor
public class AgentRunBudgetService {

    private final AgentRunStateStore stateStore;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;
    private final AgentLlmProperties llmProperties;

    /**
     * Nacos 热加载配置器（optional），用于读取 {@code runtime.runBudget.*}。
     * 如果当前环境未注入（如没有 Nacos 的场景），则 fallback 到 Spring 静态配置。
     */
    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    /** 挂钟时间硬上限（毫秒），默认 600000ms = 10 分钟 */
    @Value("${agent.run.budget.max-wall-clock-ms:600000}")
    private long defaultMaxWallClockMs;

    /** LLM 调用次数硬上限，默认 50 */
    @Value("${agent.run.budget.max-llm-calls:50}")
    private long defaultMaxLlmCalls;

    /** 工具调用次数硬上限，默认 30 */
    @Value("${agent.run.budget.max-tool-calls:30}")
    private long defaultMaxToolCalls;

    /** Token 消耗硬上限，默认 300000 */
    @Value("${agent.run.budget.max-tokens:300000}")
    private long defaultMaxTokens;

    /** 单次逻辑 LLM 调用的最大 HTTP 重试次数，默认 2 */
    @Value("${agent.run.budget.max-http-attempts-per-logical-call:2}")
    private int defaultMaxHttpAttemptsPerLogicalCall;

    /**
     * 在每次 LLM 调用前检查预算（由 {@code OpenRouterProviderRoutedChatModel} 调用）。
     * <p>注意：此入口不检查 llm_calls 维度（llm_calls 的检查在消费方通过 observability 计数后进行），
     * 这里主要检查 wallClock 和 tokens。</p>
     */
    public void checkBeforeLlmCall() {
        check("llm_call");
    }

    /**
     * 在每次工具调用前检查预算（由 {@code ToolRouter#invoke} 调用）。
     */
    public void checkBeforeToolCall() {
        check("tool_call");
    }

    /** 返回生效的单次逻辑 LLM 调用最大 HTTP 重试次数（至少为 1）。 */
    public int maxHttpAttemptsPerLogicalCall() {
        return Math.max(1, effectiveConfig().maxHttpAttemptsPerLogicalCall());
    }

    /**
     * 计算当前生效的预算配置，按三优先级合并：Nacos 热加载 > Spring 静态 > 启动 @Value 默认值。
     *
     * @return 包含五个维度值的 {@link EffectiveRunBudget} 记录
     */
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

    /**
     * 检查 HTTP 重试次数是否超过限制。
     * 每次重试前调用，防止网络不稳定导致同一个逻辑 LLM 请求无限重试。
     *
     * @param nextAttempt 下一轮尝试的序号（1-based）
     * @throws IllegalStateException 如果 nextAttempt 超过上限
     */
    public void checkHttpAttempt(int nextAttempt) {
        int max = maxHttpAttemptsPerLogicalCall();
        if (nextAttempt > max) {
            throw exceeded("http_attempts_per_logical_call", nextAttempt, max);
        }
    }

    /**
     * 四维预算检查的核心方法。
     * <p>注意 tokens 维度无论 operation 类型都检查，因为 token 增长可以发生在任何阶段。</p>
     *
     * @param operation "llm_call" 或 "tool_call"，决定是否检查 llm_calls / tool_calls 维度
     */
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

    /**
     * 构造预算超限异常，同时向事件总线写入 RUN_BUDGET_EXCEEDED 事件。
     * <p>异常消息格式：{@code RUN_BUDGET_EXCEEDED:<维度>:<实际值>/<上限>}，
     * 例如 {@code RUN_BUDGET_EXCEEDED:tool_calls:30/30}。</p>
     */
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

    /** 从 Nacos 热加载配置读取 {@code runtime.runBudget}。 */
    private AgentLlmProperties.RunBudget resolveLocalRunBudget() {
        if (localConfigLoader == null) {
            return null;
        }
        return localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getRunBudget)
                .orElse(null);
    }

    /** 从 Spring 静态配置读取 {@code agent.llm.runtime.runBudget}。 */
    private AgentLlmProperties.RunBudget resolveSpringRunBudget() {
        if (llmProperties.getRuntime() == null) {
            return null;
        }
        return llmProperties.getRuntime().getRunBudget();
    }

    /** Long 型配置值按三优先级解析：Nacos > Spring > @Value 默认。 */
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

    /** Int 型配置值按三优先级解析。 */
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

    /**
     * 从 observability JSON 中加载当前 run 的累计统计（llmCalls / toolCalls / totalTokens / startedAtMillis）。
     * 数据由 {@code AgentObservabilityService} 在每次 LLM 调用后写入。
     */
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

    /** 安全地将 Object 转为 long，非数字或异常时返回 0。 */
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

    /**
     * 生效的预算配置记录——封装五个维度的最终上限值。
     * 每次 {@link #check} 时动态计算，支持 Nacos 热加载无需重启。
     */
    public record EffectiveRunBudget(
            long maxWallClockMs,
            long maxLlmCalls,
            long maxToolCalls,
            long maxTokens,
            int maxHttpAttemptsPerLogicalCall
    ) {
    }
}
