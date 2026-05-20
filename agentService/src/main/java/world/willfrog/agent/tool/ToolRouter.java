package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.config.StressTestProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentRunBudgetService;
import world.willfrog.agent.workflow.PythonStaticPrecheckService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 工具调用统一路由器，是 LLM 决定调用工具后，所有业务工具（除 spawnSubAgent/waitForSubAgent
 * 这两个子代理控制工具外）的执行入口。
 *
 * <h3>位置与角色</h3>
 * 本路由器是 {@link world.willfrog.agent.workflow.ReactTodoExecutor} 在 ReAct 循环中
 * 处理 LLM 返回的 {@code tool_calls} 时的核心依赖：当 LLM 返回某个 tool_call 后，
 * ReactTodoExecutor 将工具名和参数交给 {@link #invokeWithMeta(String, Map)} 路由执行。
 *
 * <h3>核心职责</h3>
 * <ol>
 *   <li><b>参数兼容</b>：不同 LLM 可能输出不同的参数键（{@code tsCode}、{@code ts_code}、
 *       {@code code}、{@code stock_code}、{@code arg0} 等），路由器统一在此层做别名兼容，
 *       下层工具实现只接收标准字段。</li>
 *   <li><b>能力校验</b>：在路由前检查 run 级能力开关（如 webSearch 未开启时拒绝 searchWeb）。</li>
 *   <li><b>预算检查</b>：调用 {@link AgentRunBudgetService#checkBeforeToolCall} 检查
 *       run 总额度/总耗时预算是否已用尽。</li>
 *   <li><b>结果缓存</b>：通过 {@link ToolResultCacheService} 对工具结果按用户或全局 scope
 *       做缓存复用，节省重复调用成本。</li>
 *   <li><b>观测记录</b>：通过 {@link AgentObservabilityService#recordToolCall} 记录每一次
 *       工具调用 trace（参数、结果、耗时、是否命中缓存等），供 run 观测视图展示。</li>
 *   <li><b>故障注入</b>：根据 {@link StressTestProperties} 注入模拟延迟/失败，用于压测。</li>
 *   <li><b>指标采集</b>：通过 Micrometer Timer 记录每个工具的调用耗时分布。</li>
 * </ol>
 *
 * <h3>统一响应格式</h3>
 * 所有路由出口（成功/失败/不支持的工具）都返回标准 JSON 响应：
 * <pre>
 * { "ok": true|false,
 *   "tool": "xxx",
 *   "data": { ... },
 *   "error": { "code": "...", "message": "...", "details": {...} }
 * }
 * </pre>
 *
 * @see world.willfrog.agent.workflow.ReactTodoExecutor
 * @see ToolResultCacheService
 * @see AgentObservabilityService
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolRouter {

    /** 行情数据工具集（个股、指数、基金、财报） */
    private final MarketDataTools marketDataTools;
    /** RAG 检索工具集（ragSearch、loadDocument） */
    private final RagTools ragTools;
    /** 网页搜索工具集（searchWeb），受 AgentContext.isWebSearchEnabled 能力开关控制 */
    private final SearchTools searchTools;
    /** Python 沙箱执行工具集（executePython） */
    private final PythonSandboxTools pythonSandboxTools;
    /** executePython 静态参数/代码预校验（B1） */
    private final PythonStaticPrecheckService pythonStaticPrecheckService;
    /** 运行时 LLM/执行配置（含 static-precheck-enabled） */
    private final AgentLlmProperties llmProperties;
    /** 工具结果缓存服务，按 toolName + params + scope 做去重缓存 */
    private final ToolResultCacheService toolResultCacheService;
    /** 观测数据服务，记录每次工具调用的 trace（参数、结果、耗时、缓存元数据等） */
    private final AgentObservabilityService observabilityService;
    /** JSON 序列化/反序列化，用于构建标准响应和判断工具成功状态 */
    private final ObjectMapper objectMapper;
    /** Micrometer 指标注册中心，用于按 toolName 标签上报工具调用耗时 */
    private final MeterRegistry meterRegistry;
    /** 压测开关，控制故障注入（模拟延迟、模拟失败） */
    private final StressTestProperties stressTestProperties;
    /** 按 toolName 缓存 Timer 实例，避免每次调用重新构建（线程安全） */
    private final ConcurrentHashMap<String, Timer> toolCallTimers = new ConcurrentHashMap<>();

    /**
     * Run 级预算服务 — 可选注入（@Autowired(required = false)），
     * 避免在没有完整 Spring 上下文的单元测试环境中出错。
     * 若为 null，则跳过预算检查。
     */
    @Autowired(required = false)
    private AgentRunBudgetService budgetService;

    /**
     * 本地热加载配置 — 可选注入，用于覆盖 application.yml 中的 execution 开关。
     */
    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    @Autowired(required = false)
    private ToolWeightedLimitService toolWeightedLimitService;

    /**
     * 简化入口：仅返回工具输出文本，丢弃成功标志、耗时、缓存元数据等。
     *
     * <p>保留此入口主要用于向后兼容老的调用点，新调用方应优先使用
     * {@link #invokeWithMeta(String, Map)} 以获取完整元数据。</p>
     *
     * @param toolName 工具名（如 "getStockInfo"、"searchWeb"）
     * @param params   工具参数 Map，键名兼容多种命名风格
     * @return 工具输出的标准 JSON 字符串
     */
    public String invoke(String toolName, Map<String, Object> params) {
        return invokeWithMeta(toolName, params).getOutput();
    }

    /**
     * 工具调用主入口：执行预算检查、故障注入、缓存路由、观测记录的完整流程。
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>调用 {@link AgentRunBudgetService#checkBeforeToolCall} 检查 run 预算（若已用尽则抛出）。</li>
     *   <li>若开启了压测延迟注入，则 sleep 指定毫秒数。</li>
     *   <li>若开启了压测失败注入且命中概率，直接返回模拟失败结果，仍记录观测。</li>
     *   <li>正常路径：调用 {@link ToolResultCacheService#executeWithCache} 走缓存逻辑，
     *       缓存未命中时回调 {@link #executeDirect} 真正执行工具。</li>
     *   <li>无论命中缓存与否，都记录观测 trace 和耗时 Timer。</li>
     *   <li>组装 {@link ToolInvocationResult} 返回给调用方。</li>
     * </ol>
     *
     * @param toolName 工具名
     * @param params   工具参数（来自 LLM tool_calls 的 arguments JSON）
     * @return 包含输出文本、成功标志、耗时、缓存元数据的封装对象
     */
    public ToolInvocationResult invokeWithMeta(String toolName, Map<String, Object> params) {
        // 预算检查：可能抛出 RunBudgetExceededException 中断本次工具调用
        if (budgetService != null) {
            budgetService.checkBeforeToolCall();
        }
        debugLog("tool invoke request: runId={}, tool={}, params={}",
                AgentContext.getRunId(), nvl(toolName), safeJson(params));

        // Fault injection: simulated latency
        // 压测场景下注入固定延迟，用于观察上游对慢工具的处理是否正常
        if (stressTestProperties.isToolLatencyEnabled() && stressTestProperties.getToolLatencyMs() > 0) {
            try {
                Thread.sleep(stressTestProperties.getToolLatencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Fault injection: simulated failure
        // 按指定概率随机返回失败，用于观察 ReAct 重试与降级是否正常工作
        if (stressTestProperties.getToolFailureRate() > 0 && Math.random() < stressTestProperties.getToolFailureRate()) {
            String errorResult = invocationError(toolName, "Simulated failure for stress test");
            recordObservability(toolName, params, errorResult, 0, false, null);
            getOrCreateToolCallTimer(nvl(toolName)).record(0, TimeUnit.MILLISECONDS);
            return ToolInvocationResult.builder()
                    .output(errorResult)
                    .success(false)
                    .durationMs(0)
                    .cacheMeta(null)
                    .build();
        }

        Optional<ToolWeightedLimitService.WeightLease> weightLease = Optional.empty();
        if (toolWeightedLimitService != null) {
            Optional<ToolWeightedLimitService.WeightLease> acquired = toolWeightedLimitService.tryAcquire(toolName, params);
            if (acquired.isEmpty()) {
                int effectiveWeight = toolWeightedLimitService.previewEffectiveWeight(toolName, params);
                String errorResult = weightLimitExceeded(toolName, effectiveWeight);
                recordObservability(toolName, params, errorResult, 0, false, null);
                getOrCreateToolCallTimer(nvl(toolName)).record(0, TimeUnit.MILLISECONDS);
                return ToolInvocationResult.builder()
                        .output(errorResult)
                        .success(false)
                        .durationMs(0)
                        .cacheMeta(null)
                        .build();
            }
            weightLease = acquired;
        }

        try {
        // 主调用路径：走缓存装饰，由 ToolResultCacheService 决定命中或回源到 executeDirect
        ToolResultCacheService.CachedToolCallResult cached = toolResultCacheService.executeWithCache(
                toolName,
                params,
                resolveScope(),
                () -> executeDirect(toolName, params)
        );
        String result = nvl(cached.getResult());
        boolean success = cached.isSuccess();
        long durationMs = Math.max(0L, cached.getDurationMs());
        ToolResultCacheService.CacheMeta cacheMeta = cached.getCacheMeta();
        // 记录观测 trace（参数、结果、耗时、缓存命中信息），供 run 详情页展示
        recordObservability(toolName, params, result, durationMs, success, cacheMeta);

        // 按 toolName 分桶上报耗时指标
        getOrCreateToolCallTimer(nvl(toolName)).record(durationMs, TimeUnit.MILLISECONDS);

        debugLog("tool invoke response: runId={}, tool={}, success={}, durationMs={}, cache={}, resultPreview={}",
                AgentContext.getRunId(),
                nvl(toolName),
                success,
                durationMs,
                toolResultCacheService.toPayload(cacheMeta),
                preview(result));
        return ToolInvocationResult.builder()
                .output(result)
                .success(success)
                .durationMs(durationMs)
                .cacheMeta(cacheMeta)
                .build();
        } finally {
            weightLease.ifPresent(ToolWeightedLimitService.WeightLease::release);
        }
    }

    /**
     * 将工具调用的缓存元数据转换为事件流上报用的轻量 payload。
     *
     * <p>事件流中只需要展示是否命中、缓存来源等关键字段，不需要完整 CacheMeta 对象。</p>
     *
     * @param invocationResult 工具调用结果（可为 null）
     * @return 适合写入事件 JSON 的 Map
     */
    public Map<String, Object> toEventCachePayload(ToolInvocationResult invocationResult) {
        return toolResultCacheService.toPayload(invocationResult == null ? null : invocationResult.getCacheMeta());
    }

    /**
     * 返回路由器支持的全部工具名集合。
     *
     * <p>用于上游（如 Planner 提示词生成、能力校验）枚举可路由的业务工具。
     * 注意：spawnSubAgent / waitForSubAgent 属于子代理控制工具，
     * 由 ReactTodoExecutor 直接处理而不经过本路由器。</p>
     *
     * @return 不可变的工具名集合
     */
    public Set<String> supportedTools() {
        return Set.of(
                "getStockInfo",
                "getStockDaily",
                "searchStock",
                "searchFund",
                "getIndexInfo",
                "getIndexDaily",
                "searchIndex",
                "searchAssetInfo",
                "getExchangeAssetDaily",
                "getOffExchangeAssetDaily",
                "getEtfAdj",
                "getListedAssetShareSize",
                "getFinancialReport",
                "ragSearch",
                "loadDocument",
                "searchWeb",
                "executePython",
                "spawnSubAgent",
                "waitForSubAgent"
        );
    }

    /**
     * 获取或创建指定工具名的耗时 Timer。
     *
     * <p>使用 ConcurrentHashMap 的 computeIfAbsent 保证线程安全且每个 toolName 只注册一次。</p>
     */
    private Timer getOrCreateToolCallTimer(String toolName) {
        return toolCallTimers.computeIfAbsent(toolName, name ->
                Timer.builder("tool.call")
                        .tag("toolName", name)
                        .register(meterRegistry));
    }

    /**
     * 真正执行工具调用的核心方法（不含缓存、观测、指标的装饰）。
     *
     * <p>由 {@link ToolResultCacheService#executeWithCache} 在缓存未命中时回调。</p>
     *
     * <h4>责任</h4>
     * <ul>
     *   <li>能力开关校验（如 webSearch 未开启时返回 CAPABILITY_DISABLED）。</li>
     *   <li>参数别名兼容：将 LLM 可能输出的不同键名（tsCode / ts_code / code / arg0 等）
     *       统一映射到工具实现的标准入参。</li>
     *   <li>switch 路由到具体工具实现。</li>
     *   <li>异常包装：任何工具内部抛出的异常都包装为标准失败 JSON。</li>
     *   <li>计算实际执行耗时（不含缓存开销）。</li>
     * </ul>
     *
     * @param toolName 工具名
     * @param params   原始参数 Map
     * @return 工具执行结果（含结果文本、耗时、成功标志）
     */
    private String invokeExecutePython(Map<String, Object> params) {
        String code = str(params.get("code"), params.get("arg0"));
        String datasetIds = collectExecutePythonDatasetIds(params);
        if (isStaticPrecheckEnabled()) {
            PythonStaticPrecheckService.Result precheck =
                    pythonStaticPrecheckService.check(code, datasetIds, params);
            if (!precheck.isPassed()) {
                return precheckFailure("executePython", precheck);
            }
        }
        return pythonSandboxTools.executePython(
                code,
                datasetIds,
                str(params.get("libraries"), params.get("arg3")),
                toNullableInt(params.get("timeout_seconds"), params.get("timeoutSeconds"), params.get("arg4"))
        );
    }

    private boolean isStaticPrecheckEnabled() {
        if (localConfigLoader != null) {
            Boolean local = localConfigLoader.current()
                    .map(AgentLlmProperties::getRuntime)
                    .map(AgentLlmProperties.Runtime::getExecution)
                    .map(AgentLlmProperties.Execution::getStaticPrecheckEnabled)
                    .orElse(null);
            if (local != null) {
                return local;
            }
        }
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getExecution() != null) {
            Boolean enabled = llmProperties.getRuntime().getExecution().getStaticPrecheckEnabled();
            if (enabled != null) {
                return enabled;
            }
        }
        return true;
    }

    private boolean isAdjFactorEnabled() {
        if (localConfigLoader != null) {
            Boolean local = localConfigLoader.current()
                    .map(AgentLlmProperties::getRuntime)
                    .map(AgentLlmProperties.Runtime::getExecution)
                    .map(AgentLlmProperties.Execution::getAdjFactorEnabled)
                    .orElse(null);
            if (local != null) {
                return local;
            }
        }
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getExecution() != null) {
            Boolean enabled = llmProperties.getRuntime().getExecution().getAdjFactorEnabled();
            if (enabled != null) {
                return enabled;
            }
        }
        return false;
    }

    private String precheckFailure(String toolName, PythonStaticPrecheckService.Result precheck) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pre_validation_failed", true);
        if (precheck.getReport() != null) {
            details.put("report", precheck.getReport());
        }
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", nvl(precheck.getErrorCode()),
                        "message", nvl(precheck.getMessage()),
                        "details", details
                )
        ));
    }

    private ToolResultCacheService.ToolExecutionOutcome executeDirect(String toolName, Map<String, Object> params) {
        long startedAt = System.currentTimeMillis();
        String result;
        try {
            // 能力校验：searchWeb 必须显式开启 webSearch 能力，否则返回不可用响应
            if ("searchWeb".equals(toolName) && !AgentContext.isWebSearchEnabled()) {
                result = writeJson(Map.of(
                        "ok", false,
                        "tool", "searchWeb",
                        "data", Map.of(),
                        "error", Map.of(
                                "code", "CAPABILITY_DISABLED",
                                "message", "webSearch is disabled for this run",
                                "details", Map.of()
                        )
                ));
                return ToolResultCacheService.ToolExecutionOutcome.builder()
                        .result(result)
                        .durationMs(Math.max(0L, System.currentTimeMillis() - startedAt))
                        .success(false)
                        .build();
            }
            // 统一入口负责兼容参数别名（ts_code/code 等），工具实现层只接收标准参数。
            result = switch (toolName) {
                case "getStockInfo" -> marketDataTools.getStockInfo(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("stock_code"), params.get("arg0"))
                );
                case "getStockDaily" -> marketDataTools.getStockDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("stock_code"), params.get("arg0")),
                        dateStr(params.get("startDateStr"), params.get("startDate"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDateStr"), params.get("endDate"), params.get("end_date"), params.get("arg2"))
                );
                case "searchStock" -> marketDataTools.searchStock(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "searchFund" -> marketDataTools.searchFund(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "getIndexInfo" -> marketDataTools.getIndexInfo(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("index_code"), params.get("arg0"))
                );
                case "getIndexDaily" -> marketDataTools.getIndexDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("index_code"), params.get("arg0")),
                        dateStr(params.get("startDateStr"), params.get("startDate"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDateStr"), params.get("endDate"), params.get("end_date"), params.get("arg2"))
                );
                case "searchIndex" -> marketDataTools.searchIndex(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "searchAssetInfo" -> marketDataTools.searchAssetInfo(
                        str(params.get("query"), params.get("keyword"), params.get("arg0")),
                        str(params.get("assetTypes"), params.get("asset_types"), params.get("arg1")),
                        str(params.get("marketScope"), params.get("market_scope"), params.get("arg2"), "domestic")
                );
                case "getExchangeAssetDaily" -> marketDataTools.getExchangeAssetDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("arg0")),
                        str(params.get("assetType"), params.get("asset_type"), params.get("arg1")),
                        dateStr(params.get("startDate"), params.get("startDateStr"), params.get("start_date"), params.get("arg2")),
                        dateStr(params.get("endDate"), params.get("endDateStr"), params.get("end_date"), params.get("arg3")),
                        str(params.get("priceMode"), params.get("price_mode"), params.get("arg4"), "raw_ohlc")
                );
                case "getOffExchangeAssetDaily" -> marketDataTools.getOffExchangeAssetDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("arg0")),
                        dateStr(params.get("startDate"), params.get("startDateStr"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDate"), params.get("endDateStr"), params.get("end_date"), params.get("arg2"))
                );
                case "getEtfAdj" -> {
                    if (!isAdjFactorEnabled()) {
                        yield writeJson(Map.of(
                                "ok", false,
                                "tool", "getEtfAdj",
                                "data", Map.of(),
                                "error", Map.of(
                                        "code", "CAPABILITY_DISABLED",
                                        "message", "ETF adj factor is disabled (adjFactorEnabled=false)",
                                        "details", Map.of("adjFactorEnabled", false)
                                )
                        ));
                    }
                    yield marketDataTools.getEtfAdj(
                            str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("arg0")),
                            dateStr(params.get("startDate"), params.get("startDateStr"), params.get("start_date"), params.get("arg1")),
                            dateStr(params.get("endDate"), params.get("endDateStr"), params.get("end_date"), params.get("arg2"))
                    );
                }
                case "getListedAssetShareSize" -> marketDataTools.getListedAssetShareSize(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("arg0")),
                        dateStr(params.get("startDate"), params.get("startDateStr"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDate"), params.get("endDateStr"), params.get("end_date"), params.get("arg2")),
                        str(params.get("exchange"), params.get("arg3"))
                );
                case "getFinancialReport" -> marketDataTools.getFinancialReport(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("ts code"), params.get("arg0")),
                        str(params.get("reportType"), params.get("report_type"), params.get("type"), params.get("report type"), params.get("arg1")),
                        dateStr(params.get("startPeriod"), params.get("start_period"), params.get("start"), params.get("arg2")),
                        dateStr(params.get("endPeriod"), params.get("end_period"), params.get("end"), params.get("arg3"))
                );
                case "ragSearch" -> ragTools.ragSearch(
                        str(params.get("queryText"), params.get("query_text"), params.get("query"), params.get("arg0")),
                        str(params.get("docType"), params.get("doc_type"), params.get("arg1")),
                        str(params.get("tsCode"), params.get("ts_code"), params.get("arg2")),
                        str(params.get("indName"), params.get("ind_name"), params.get("arg3")),
                        toIntWithDefault(5, params.get("topK"), params.get("top_k"), params.get("arg4"))
                );
                case "loadDocument" -> ragTools.loadDocument(
                        str(params.get("ossUrl"), params.get("oss_url"), params.get("url"), params.get("arg0"))
                );
                case "searchWeb" -> searchTools.searchWeb(
                        str(params.get("query"), params.get("arg0")),
                        str(params.get("scene"), params.get("arg1")),
                        str(params.get("backend"), params.get("arg2")),
                        str(params.get("strength"), params.get("arg3")),
                        toBool(params.get("skipHotCache"), params.get("skip_hot_cache"), params.get("arg4")),
                        toBool(params.get("skipRagPrefetch"), params.get("skip_rag_prefetch"), params.get("arg5")),
                        str(params.get("timeRangeStart"), params.get("time_range_start"), params.get("arg6")),
                        str(params.get("timeRangeEnd"), params.get("time_range_end"), params.get("arg7")),
                        toIntWithDefault(5, params.get("maxResults"), params.get("max_results"), params.get("arg8"))
                );
                case "executePython" -> invokeExecutePython(params);
                default -> unsupported(toolName);
            };
        } catch (Exception e) {
            // 任意工具实现抛出的异常都收敛为标准失败 JSON，避免对 LLM 暴露 Java 异常信息
            debugLog("tool invoke exception: runId={}, tool={}, error={}",
                    AgentContext.getRunId(), nvl(toolName), nvl(e.getMessage()));
            result = invocationError(toolName, e.getMessage());
        }
        return ToolResultCacheService.ToolExecutionOutcome.builder()
                .result(result)
                .durationMs(Math.max(0L, System.currentTimeMillis() - startedAt))
                .success(isToolSuccess(result))
                .build();
    }

    /**
     * 从多个候选值中取第一个非空白字符串。
     *
     * <p>用于参数别名兼容：例如 LLM 可能传入 tsCode、ts_code、code、arg0 任意之一，
     * 调用 {@code str(params.get("tsCode"), params.get("ts_code"), ...)} 即可取第一个有效值。</p>
     */
    private String str(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) {
                continue;
            }
            String s = String.valueOf(c).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    /**
     * 收集 executePython 的 datasetIds 参数（兼容多种命名风格）。
     *
     * <p>LLM 在不同 prompt 风格下可能输出：dataset_ids（数组）、datasetIds（驼峰）、
     * datasets、dataset_refs、单个 dataset_id 等多种写法，此处统一收集去重后
     * 用逗号拼接为字符串传给沙箱工具。</p>
     */
    private String collectExecutePythonDatasetIds(Map<String, Object> params) {
        LinkedHashSet<String> datasetIds = new LinkedHashSet<>();
        addDatasetIds(datasetIds,
                params.get("dataset_ids"),
                params.get("datasetIds"),
                params.get("datasets"),
                params.get("dataset_refs"),
                params.get("datasetRefs"),
                params.get("arg2"),
                params.get("dataset_id"),
                params.get("datasetId"),
                params.get("arg1")
        );
        return String.join(",", datasetIds);
    }

    /**
     * 将候选对象按 {@link #parseDatasetIds} 解析后追加到收集器，保留首次出现顺序、自动去重。
     */
    private void addDatasetIds(LinkedHashSet<String> collector, Object... candidates) {
        if (collector == null || candidates == null || candidates.length == 0) {
            return;
        }
        for (Object candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            List<String> parsed = parseDatasetIds(String.valueOf(candidate));
            collector.addAll(parsed);
        }
    }

    /**
     * 将一个候选字符串解析为 dataset id 列表。
     *
     * <p>兼容多种形态：</p>
     * <ul>
     *   <li>JSON 数组字面量：{@code ["a","b","c"]} — 去掉外层方括号后按逗号拆分</li>
     *   <li>逗号分隔字符串：{@code a,b,c}</li>
     *   <li>带双引号的元素：自动剥离首尾引号</li>
     * </ul>
     * 解析结果会保留顺序并去重。
     */
    private List<String> parseDatasetIds(String datasetIds) {
        if (datasetIds == null || datasetIds.isBlank()) {
            return List.of();
        }
        String raw = datasetIds.trim();
        // 去掉外层 [ ]
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        List<String> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = nvl(part).trim();
            // 去掉单个元素首尾的双引号
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (!value.isBlank() && !ids.contains(value)) {
                ids.add(value);
            }
        }
        return ids;
    }

    /**
     * 从候选值中解析可空整数，无有效值或格式非法时返回 null。
     */
    private Integer toNullableInt(Object... candidates) {
        String value = str(candidates);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从候选值中解析整数，无有效值或格式非法时返回 {@code defaultValue}。
     */
    private int toIntWithDefault(int defaultValue, Object... candidates) {
        String value = str(candidates);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 从候选值中解析布尔值，无值时默认 false。
     */
    private boolean toBool(Object... candidates) {
        String value = str(candidates);
        if (value.isEmpty()) {
            return false;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 规范化日期字符串。
     *
     * <p>若候选值的纯数字部分长度为 8（YYYYMMDD）或 13（毫秒时间戳），
     * 则返回纯数字字符串；否则返回原始 trim 后的字符串，由下游自行解析。</p>
     */
    private String dateStr(Object... candidates) {
        String raw = str(candidates);
        if (raw.isEmpty()) {
            return "";
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 8 || digits.length() == 13) {
            return digits;
        }
        return raw;
    }

    /**
     * 向观测服务写入一次工具调用 trace。
     *
     * <p>调用条件：必须存在有效的 runId。脱离 run 上下文（如冒烟测试）的调用不记录。
     * 字段映射：成功时不上报 errorMessage；缓存元数据若为 null 使用安全的占位值。</p>
     */
    private void recordObservability(String toolName,
                                     Map<String, Object> params,
                                     String result,
                                     long durationMs,
                                     boolean success,
                                     ToolResultCacheService.CacheMeta cacheMeta) {
        String runId = AgentContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        String phase = AgentContext.getPhase();
        observabilityService.recordToolCall(
                runId,
                phase,
                toolName,
                params,
                result,
                durationMs,
                success,
                cacheMeta != null && cacheMeta.isEligible(),
                cacheMeta != null && cacheMeta.isHit(),
                cacheMeta == null ? "" : cacheMeta.getKey(),
                cacheMeta == null ? "" : cacheMeta.getSource(),
                cacheMeta == null ? -1L : cacheMeta.getTtlRemainingMs(),
                cacheMeta == null ? 0L : cacheMeta.getEstimatedSavedDurationMs(),
                success ? null : result
        );
    }

    /**
     * 根据标准响应 JSON 的 {@code ok} 字段判断工具是否成功。
     *
     * <p>所有工具的成功/失败都通过响应顶层 {@code ok: true|false} 表达，
     * 解析失败或非 JSON 输出一律视为失败。</p>
     */
    private boolean isToolSuccess(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(result);
            return node.path("ok").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建 "工具不支持" 的标准失败响应。
     */
    private String unsupported(String toolName) {
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", "UNSUPPORTED_TOOL",
                        "message", "Unsupported tool",
                        "details", Map.of("tool", nvl(toolName))
                )
        ));
    }

    /**
     * 构建 "工具调用异常" 的标准失败响应。
     */
    private String invocationError(String toolName, String message) {
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", "TOOL_INVOCATION_ERROR",
                        "message", nvl(message),
                        "details", Map.of()
                )
        ));
    }

    private String weightLimitExceeded(String toolName, int effectiveWeight) {
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", "TOOL_WEIGHT_LIMIT_EXCEEDED",
                        "message", "Tool weighted concurrency limit exceeded, retry later",
                        "details", Map.of(
                                "effectiveWeight", Math.max(0, effectiveWeight),
                                "tool", nvl(toolName)
                        )
                )
        ));
    }

    /**
     * 序列化 payload 为 JSON 字符串。
     *
     * <p>若主序列化失败（极少见，例如循环引用），退化为返回包含
     * JSON_SERIALIZE_ERROR 错误码的兜底 JSON；若兜底 JSON 也序列化失败，
     * 最终返回硬编码字符串 {@code "{\"ok\":false}"}，确保始终返回合法 JSON。</p>
     */
    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("ok", false);
            fallback.put("tool", "unknown");
            fallback.put("data", Map.of());
            fallback.put("error", Map.of(
                    "code", "JSON_SERIALIZE_ERROR",
                    "message", nvl(e.getMessage()),
                    "details", Map.of()
            ));
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{\"ok\":false}";
            }
        }
    }

    /**
     * 解析工具结果缓存的 scope。
     *
     * <p>默认按用户隔离（{@code user:<userId>}），无用户上下文时退化为 global，
     * 后者意味着多个用户可能共享同一份结果（仅适用于完全无个人数据的工具）。</p>
     */
    private String resolveScope() {
        String userId = AgentContext.getUserId();
        if (userId == null || userId.isBlank()) {
            return "global";
        }
        return "user:" + userId.trim();
    }

    /** 空安全：null 转为空字符串。 */
    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 截取前 300 字符用于调试日志预览，避免长结果污染日志。 */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    /** 安全序列化为 JSON 字符串用于日志，失败时退化为 toString。 */
    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * 仅在 AgentContext.debugMode 为 true 时输出 info 级调试日志。
     *
     * <p>避免在生产环境中无差别打印工具参数和结果，节省日志量。</p>
     */
    private void debugLog(String pattern, Object... args) {
        if (!AgentContext.isDebugMode()) {
            return;
        }
        log.info("[agent-debug] " + pattern, args);
    }

    /**
     * 工具调用结果的完整封装。
     *
     * <p>包含执行输出、成功标志、耗时与缓存元数据，方便调用方判断是否触发重试、
     * 是否上报事件流缓存命中等。</p>
     */
    @Data
    @Builder
    public static class ToolInvocationResult {
        /** 工具输出的标准响应 JSON 字符串 */
        private String output;
        /** 是否成功（根据响应顶层 ok 字段判定） */
        private boolean success;
        /** 本次调用耗时（含缓存判断和实际执行） */
        private long durationMs;
        /** 缓存元数据（是否符合缓存条件、是否命中、来源、剩余 TTL 等） */
        private ToolResultCacheService.CacheMeta cacheMeta;
    }
}
