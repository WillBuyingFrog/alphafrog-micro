package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
/**
 * Agent 运行事件服务。
 * <p>
 * 职责：
 * 1. 创建 run 并写入初始事件；
 * 2. 对 run 生命周期事件进行持久化；
 * 3. 提供 ext 字段中常用业务字段的读取能力；
 * 4. 使用 Redis 原子序号保证同一 run 的事件顺序。
 *
 * <h3>事件流与 ext 字段</h3>
 * 每个 Agent Run 在 DB 中除了主表 {@code agent_run} 还有事件表 {@code agent_run_event}。
 * <ul>
 *   <li>主表 {@code ext} 字段存 run 级 JSON 配置(model_name / endpoint_name / debug_mode /
 *       stage_config_json / context_json 等),供后续执行环节读取。</li>
 *   <li>事件表按 (runId, seq) 顺序追加每一步关键事件(RUN_RECEIVED / EXECUTION_STARTED /
 *       TODO_STARTED / WORKFLOW_COMPLETED 等),seq 通过 Redis INCR 原子生成,
 *       保证同一 run 内并发追加时事件不会乱序。</li>
 * </ul>
 *
 * <h3>ext 字段提取方法集</h3>
 * 大量 {@code extractXxx(String extJson)} 方法都遵循统一约定:解析 JSON,缺失/异常返回安全默认值,
 * 不抛出异常,避免单个字段问题阻塞 run 执行。多数方法还兼容 ext 顶层 / context_json 嵌套两种位置,
 * 同时兼容 camelCase / snake_case 两种命名。
 *
 * <h3>Payload 截断</h3>
 * 事件 payload 超长时通过 {@link #normalizePayloadJson} 截断为摘要对象,避免单事件膨胀打爆 DB。
 *
 * <h3>TTL 管理</h3>
 * - {@link #nextTtlExpiresAt()} 计算正常执行的 run TTL(默认 60 分钟)
 * - {@link #nextInterruptedExpiresAt()} 计算中断状态(FAILED/WAITING/CANCELED)的延长 TTL,
 *   允许用户在更长时间窗口内查询失败原因
 */
public class AgentEventService {

    /** Redis 事件序号 key 前缀,完整 key 为 {@code agent:run:event_seq:<runId>} */
    private static final String EVENT_SEQ_KEY_PREFIX = "agent:run:event_seq:";

    /** run 主表读写。 */
    private final AgentRunMapper runMapper;
    /** run 事件表读写。 */
    private final AgentRunEventMapper eventMapper;
    /** JSON 序列化/反序列化工具。 */
    private final ObjectMapper objectMapper;
    /** Redis 客户端：用于事件序号原子递增。 */
    private final StringRedisTemplate redisTemplate;
    /** 本地 llm/runtim 配置加载器。 */
    private final AgentLlmLocalConfigLoader llmLocalConfigLoader;
    /** 消息服务:用于在 run 创建时写入初始用户消息 */
    private final AgentMessageService messageService;

    /** Run 正常生命周期 TTL(分钟),默认 60 分钟,过期后视为 EXPIRED */
    @Value("${agent.run.ttl-minutes:60}")
    private int ttlMinutes;

    /** Run 中断状态(FAILED/WAITING/CANCELED)的额外 TTL(天),供查询失败原因用 */
    @Value("${agent.run.interrupted-ttl-days:7}")
    private int interruptedTtlDays;

    /** Checkpoint 协议版本,写入 ext 用于未来兼容性升级 */
    @Value("${agent.run.checkpoint-version:v2}")
    private String checkpointVersion;

    /** 单事件 payload JSON 最大字符数,超出会被截断为摘要对象 */
    @Value("${agent.event.payload.max-chars:10000}")
    private int payloadMaxChars;

    /** 截断时保留的预览字符数 */
    @Value("${agent.event.payload.preview-chars:4096}")
    private int payloadPreviewChars;

    /**
     * 创建新的 run 记录并写入初始事件。
     *
     * @param userId           用户 ID
     * @param message          用户输入
     * @param contextJson      上下文 JSON
     * @param idempotencyKey   幂等键
     * @param modelName        模型名（可为空，后续会用默认）
     * @param endpointName     端点名（可为空，后续会用默认）
     * @param debugMode        run 级调试模式开关
     * @param stageConfigJson  各阶段 LLM 配置 JSON（可为空）
     * @return 创建后的 run
     */
    public AgentRun createRun(String userId,
                              String message,
                              String contextJson,
                              String idempotencyKey,
                              String modelName,
                              String endpointName,
                              boolean captureLlmRequests,
                              String provider,
                              int plannerCandidateCount,
                              boolean debugMode,
                              String stageConfigJson) {
        log.info("[AgentEventService] 创建 Run: userId={}, stageConfigJson={}", userId, stageConfigJson);
        // 生成无连字符 UUID 作为 runId
        String runId = java.util.UUID.randomUUID().toString().replace("-", "");

        // ── 构建 ext 字段:聚合所有 run 级配置 ──
        Map<String, Object> ext = new HashMap<>();
        ext.put("user_goal", message);
        ext.put("context_json", contextJson == null ? "" : contextJson);
        ext.put("idempotency_key", idempotencyKey == null ? "" : idempotencyKey);
        ext.put("model_name", modelName == null ? "" : modelName);
        ext.put("endpoint_name", endpointName == null ? "" : endpointName);
        ext.put("capture_llm_requests", captureLlmRequests);
        ext.put("debug_mode", debugMode);
        ext.put("provider", provider == null ? "" : provider.trim());
        if (plannerCandidateCount > 0) {
            ext.put("planner_candidate_count", plannerCandidateCount);
        }
        ext.put("checkpoint_version", resolveCheckpointVersion());
        if (stageConfigJson != null && !stageConfigJson.isBlank()) {
            try {
                // 存储为 JSON 对象而非字符串，便于后续解析
                ext.put("stage_config_json", objectMapper.readTree(stageConfigJson));
                log.info("[AgentEventService] stage_config_json 已存入 ext: {}", stageConfigJson);
            } catch (Exception e) {
                // 解析失败时保留原始字符串,后续解析时按字符串再 fallback 一次
                log.warn("[AgentEventService] 解析 stage_config_json 失败，存储为原始字符串: {}", e.getMessage());
                ext.put("stage_config_json", stageConfigJson);
            }
        } else {
            log.warn("[AgentEventService] stageConfigJson 为空，未存入 ext");
        }

        // 从 contextJson 中提取 execution_mode
        String executionMode = extractExecutionModeFromContext(contextJson);
        if (executionMode != null && !executionMode.isBlank()) {
            ext.put("execution_mode", executionMode);
        }

        // ── 构建 run 实体并写入 DB ──
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(userId);
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setCurrentStep(0);
        run.setMaxSteps(12);
        run.setPlanJson("{}");
        run.setSnapshotJson("{}");
        run.setLastError(null);
        run.setTtlExpiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes));
        run.setExt(writeJson(ext));

        runMapper.insert(run);
        // 紧接着写入 RUN_RECEIVED 事件,保留 ext 全文作为事件 payload 便于审计
        append(runId, userId, "RUN_RECEIVED", ext);

        // 写入首条用户消息（initial）
        try {
            messageService.createInitialMessage(runId, message);
        } catch (Exception e) {
            // 消息写入失败不影响主流程，仅记录日志
            log.warn("Failed to create initial message for runId={}, but continuing: {}", runId, e.getMessage());
        }

        // 重新查询返回,保证字段(自增 id、created_at 等)是 DB 最终视图
        return runMapper.findByIdAndUser(runId, userId);
    }

    /**
     * 判断当前 run 是否还可继续执行。
     *
     * <p>不可执行的条件:</p>
     * <ul>
     *   <li>run 不存在</li>
     *   <li>状态为 CANCELED / EXPIRED / WAITING</li>
     *   <li>TTL 已过期</li>
     * </ul>
     *
     * <p>调用方一般是 AgentRunExecutor 在进入执行前做最后一次检查,
     * 避免对一个已经被用户取消的 run 继续浪费 LLM 资源。</p>
     *
     * @param runId  任务 ID
     * @param userId 用户 ID
     * @return 可执行返回 true，否则返回 false
     */
    public boolean isRunnable(String runId, String userId) {
        AgentRun run = runMapper.findByIdAndUser(runId, userId);
        if (run == null) {
            return false;
        }
        if (run.getStatus() == AgentRunStatus.CANCELED) {
            log.info("Run canceled, stop: {}", runId);
            return false;
        }
        if (run.getStatus() == AgentRunStatus.EXPIRED) {
            log.info("Run expired, stop: {}", runId);
            return false;
        }
        if (run.getStatus() == AgentRunStatus.WAITING) {
            log.info("Run paused (waiting), stop: {}", runId);
            return false;
        }
        if (run.getTtlExpiresAt() != null && OffsetDateTime.now().isAfter(run.getTtlExpiresAt())) {
            log.info("Run expired (ttl), stop: {}", runId);
            return false;
        }
        return true;
    }

    /**
     * 追加事件到 run 的事件流中。
     *
     * <p>事件 seq 通过 {@link #nextSeq} 借助 Redis INCR 原子生成,即便多线程并发追加
     * 也能保证事件顺序唯一。落库失败时直接抛 {@link IllegalStateException} fail-fast,
     * 避免静默丢事件导致数据流缺失。</p>
     *
     * @param runId     任务 ID
     * @param userId    用户 ID
     * @param eventType 事件类型
     * @param payload   事件负载（对象会序列化为 JSON）
     */
    public void append(String runId, String userId, String eventType, Object payload) {
        AgentRun run = runMapper.findByIdAndUser(runId, userId);
        if (run == null) {
            return;
        }
        // 事件序号采用 Redis 原子递增，避免并发落库时 seq 冲突。
        int nextSeq = nextSeq(runId);
        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(runId);
        event.setSeq(nextSeq);
        event.setEventType(eventType);
        // payload 已经是字符串则直接使用,否则序列化为 JSON
        String payloadJson = payload instanceof String ? (String) payload : writeJson(payload);
        event.setPayloadJson(normalizePayloadJson(eventType, payloadJson));
        try {
            eventMapper.insert(event);
        } catch (Exception e) {
            String msg = String.format(
                    "Append event failed (fail-fast): runId=%s, eventType=%s, seq=%d",
                    runId, eventType, nextSeq
            );
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 从 ext JSON 中提取用户目标。
     *
     * @param extJson ext 字段 JSON
     * @return user_goal 字段值
     */
    public String extractUserGoal(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> map = objectMapper.readValue(extJson, Map.class);
            Object v = map.get("user_goal");
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 会话标题优先级：
     * 1) ext.title（重命名后显示名）
     * 2) ext.user_goal（历史默认标题）
     */
    public String extractRunDisplayTitle(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> map = objectMapper.readValue(extJson, Map.class);
            // 优先 title:用户主动重命名时写入
            Object title = map.get("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                return String.valueOf(title).trim();
            }
            // 退到 user_goal:历史默认标题
            Object userGoal = map.get("user_goal");
            return userGoal == null ? "" : String.valueOf(userGoal);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从 ext JSON 中提取模型名。
     *
     * @param extJson ext 字段 JSON
     * @return model_name 字段值
     */
    public String extractModelName(String extJson) {
        return extractField(extJson, "model_name");
    }

    /**
     * 从 ext JSON 中提取端点名。
     *
     * @param extJson ext 字段 JSON
     * @return endpoint_name 字段值
     */
    public String extractEndpointName(String extJson) {
        return extractField(extJson, "endpoint_name");
    }

    /**
     * run 级开关：是否抓取 LLM 原始请求。
     * 支持 ext 明文字段与 context_json 回退读取。
     */
    public boolean extractCaptureLlmRequests(String extJson) {
        return extractBooleanFromExt(extJson, "capture_llm_requests", "captureLlmRequests", "capture_llm_requests");
    }

    /**
     * run 级调试模式：
     * 开启后会把关键中间态写入微服务日志，便于线上问题复盘。
     */
    public boolean extractDebugMode(String extJson) {
        return extractBooleanFromExt(extJson, "debug_mode", "debugMode", "debug_mode");
    }

    /**
     * 从 ext JSON 中提取执行模式。
     *
     * <p>兼容 snake_case ({@code execution_mode}) 和 camelCase ({@code executionMode}) 两种命名。
     * 未配置时返回 {@code "AUTO"},由 Planner 根据 Plan 特征自动选择 LINEAR 或 DAG。</p>
     *
     * @param extJson ext 字段 JSON
     * @return execution_mode 字段值，默认为 AUTO
     */
    public String extractExecutionMode(String extJson) {
        String mode = extractField(extJson, "execution_mode");
        if (mode == null || mode.isBlank()) {
            mode = extractField(extJson, "executionMode");
        }
        return mode == null || mode.isBlank() ? "AUTO" : mode;
    }

    /**
     * 从 ext JSON 中提取是否启用 Plan Patch（默认 false）。
     */
    public boolean extractEnablePlanPatch(String extJson) {
        return extractBooleanFromExt(extJson, "enable_plan_patch", "enablePlanPatch", "enable_plan_patch");
    }

    /**
     * 从 ext JSON 中提取每次 run 的 maxTodos 上限（可选，未传则返回 null，由服务端配置兜底）。
     *
     * <p>兼容两种命名,容错解析:</p>
     * <ul>
     *   <li>缺失/空白 → null(由调用方使用配置默认值)</li>
     *   <li>非数字 → null</li>
     *   <li>非正数 → null(避免出现 0 步骤这种无意义配置)</li>
     * </ul>
     */
    public Integer extractMaxTodos(String extJson) {
        String raw = extractField(extJson, "max_todos");
        if (raw == null || raw.isBlank()) {
            raw = extractField(extJson, "maxTodos");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从 contextJson 中提取执行模式。
     * 仅在 createRun 时使用,把 contextJson 中潜在的 execution_mode 提升到 ext 顶层。
     */
    private String extractExecutionModeFromContext(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(contextJson, Map.class);
            Object mode = map.get("execution_mode");
            if (mode == null) {
                mode = map.get("executionMode");
            }
            return mode != null ? mode.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 提取用户指定的 OpenRouter provider 偏好顺序。
     *
     * <p>查找顺序:ext.provider 优先,缺失时回退到 context_json.provider。
     * provider 值为逗号分隔字符串,解析后去重去空白返回列表。</p>
     *
     * @return provider 名列表,无配置时返回空列表
     */
    public List<String> extractOpenRouterProviderOrder(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return List.of();
        }
        try {
            Map<?, ?> map = objectMapper.readValue(extJson, Map.class);
            Object raw = map.get("provider");
            List<String> providers = parseProviderOrderValue(raw);
            if (!providers.isEmpty()) {
                return providers;
            }
            // ext 顶层没有就尝试 context_json 内的 provider
            Object contextRaw = map.get("context_json");
            if (!(contextRaw instanceof String contextJson) || contextJson.isBlank()) {
                return List.of();
            }
            Map<?, ?> contextMap = objectMapper.readValue(contextJson, Map.class);
            return parseProviderOrderValue(contextMap.get("provider"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 提取 Planner 候选方案数量。
     * 用于并行规划场景:一次 run 同时生成 N 个候选 Plan,后选择最佳。
     *
     * @return 非负整数,缺失或解析失败返回 0
     */
    public int extractPlannerCandidateCount(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return 0;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(extJson, Map.class);
            Object raw = map.get("planner_candidate_count");
            if (raw == null) {
                return 0;
            }
            if (raw instanceof Number number) {
                return Math.max(0, number.intValue());
            }
            String text = String.valueOf(raw).trim();
            if (text.isBlank()) {
                return 0;
            }
            return Math.max(0, Integer.parseInt(text));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 提取 run 级 config 配置。
     * <p>
     * 默认值：
     * - webSearch.enabled = false
     * - webSearch.backend/strength/skipHotCache/skipRagPrefetch/maxResults = unset
     * - codeInterpreter.enabled = true（保持历史行为兼容）
     * - codeInterpreter.maxCredits = 0
     * - smartRetrieval.enabled = false
     *
     * <p>查找路径:优先 {@code ext.context_json.config},退到 {@code ext.config}。
     * 任一节点缺失/解析异常 → 返回 {@link RunConfig#defaults()}。</p>
     */
    public RunConfig extractRunConfig(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return RunConfig.defaults();
        }
        try {
            Map<?, ?> extMap = objectMapper.readValue(extJson, Map.class);
            Map<?, ?> contextMap = mapFromObject(extMap.get("context_json"));
            // 优先从 context_json.config 读取
            Map<?, ?> configMap = mapFromObject(contextMap.get("config"));
            if (configMap.isEmpty()) {
                // 退到 ext.config(历史路径)
                configMap = mapFromObject(extMap.get("config"));
            }
            if (configMap.isEmpty()) {
                return RunConfig.defaults();
            }
            // 读取三个能力分区:webSearch / codeInterpreter / smartRetrieval
            Map<?, ?> webSearch = readSection(configMap, "webSearch", "web_search");
            Map<?, ?> codeInterpreter = readSection(configMap, "codeInterpreter", "code_interpreter");
            Map<?, ?> smartRetrieval = readSection(configMap, "smartRetrieval", "smart_retrieval");

            boolean webSearchEnabled = readBoolean(webSearch, "enabled", "enabled", false);
            AgentContext.WebSearchConfig webSearchConfig = new AgentContext.WebSearchConfig(
                    readString(webSearch, "backend", "backend", ""),
                    readString(webSearch, "strength", "strength", ""),
                    readBooleanNullable(webSearch, "skipHotCache", "skip_hot_cache"),
                    readBooleanNullable(webSearch, "skipRagPrefetch", "skip_rag_prefetch"),
                    positiveOrNull(readInt(webSearch, "maxResults", "max_results", 0))
            );
            // codeInterpreter 默认开启(保持历史行为兼容)
            boolean codeInterpreterEnabled = readBoolean(codeInterpreter, "enabled", "enabled", true);
            int codeInterpreterMaxCredits = readInt(codeInterpreter, "maxCredits", "max_credits", 0);
            boolean smartRetrievalEnabled = readBoolean(smartRetrieval, "enabled", "enabled", false);

            return new RunConfig(
                    webSearchEnabled,
                    webSearchConfig,
                    codeInterpreterEnabled,
                    // 负值规范化为 0
                    Math.max(0, codeInterpreterMaxCredits),
                    smartRetrievalEnabled
            );
        } catch (Exception e) {
            return RunConfig.defaults();
        }
    }

    /** 便捷方法:只取 webSearch 开关。 */
    public boolean extractWebSearchEnabled(String extJson) {
        return extractRunConfig(extJson).webSearchEnabled();
    }

    /** 便捷方法:只取 codeInterpreter 开关。 */
    public boolean extractCodeInterpreterEnabled(String extJson) {
        return extractRunConfig(extJson).codeInterpreterEnabled();
    }

    /** 便捷方法:只取 codeInterpreter 信用额度上限。 */
    public int extractCodeInterpreterMaxCredits(String extJson) {
        return extractRunConfig(extJson).codeInterpreterMaxCredits();
    }

    /** 便捷方法:只取 smartRetrieval 开关。 */
    public boolean extractSmartRetrievalEnabled(String extJson) {
        return extractRunConfig(extJson).smartRetrievalEnabled();
    }

    /**
     * 解析逗号分隔的 provider 列表。
     * 自动去空白,跳过空 token。
     */
    private List<String> parseProviderOrderValue(Object raw) {
        if (raw == null) {
            return List.of();
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return List.of();
        }
        List<String> providers = new ArrayList<>();
        for (String token : text.split(",")) {
            String provider = token == null ? "" : token.trim();
            if (!provider.isBlank()) {
                providers.add(provider);
            }
        }
        return providers;
    }

    /**
     * 从 ext JSON 中提取布尔值的统一方法,支持多路径查找。
     *
     * <p>查找顺序:</p>
     * <ol>
     *   <li>ext 顶层的 extKey 字段。</li>
     *   <li>ext.context_json 嵌套对象内的 contextKeyCamel(camelCase)。</li>
     *   <li>同样位置的 contextKeySnake(snake_case)。</li>
     * </ol>
     * <p>任一位置为 true 则返回 true(就高合并),全为 false/缺失则返回 false。</p>
     */
    private boolean extractBooleanFromExt(String extJson,
                                          String extKey,
                                          String contextKeyCamel,
                                          String contextKeySnake) {
        if (extJson == null || extJson.isBlank()) {
            return false;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(extJson, Map.class);
            Boolean direct = toBoolean(map.get(extKey));
            // 顶层就为 true 时已可短路返回
            if (Boolean.TRUE.equals(direct)) {
                return true;
            }
            // 否则继续看 context_json 嵌套
            Object contextRaw = map.get("context_json");
            if (!(contextRaw instanceof String contextJson) || contextJson.isBlank()) {
                return Boolean.TRUE.equals(direct);
            }
            Map<?, ?> contextMap = objectMapper.readValue(contextJson, Map.class);
            Boolean contextValue = toBoolean(contextMap.get(contextKeyCamel));
            if (contextValue != null) {
                return contextValue || Boolean.TRUE.equals(direct);
            }
            return Boolean.TRUE.equals(direct) || Boolean.TRUE.equals(toBoolean(contextMap.get(contextKeySnake)));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 容错地把任意对象转成 Map:
     * 已经是 Map 直接返回;若是 JSON 字符串则解析为 Map;其他类型/异常返回空 Map。
     */
    private Map<?, ?> mapFromObject(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return map;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(text, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return map;
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 读取 parent 中指定子节(优先 camelCase,后 snake_case)。
     * 找不到或非 Map 类型则返回空 Map。
     */
    private Map<?, ?> readSection(Map<?, ?> parent, String keyCamel, String keySnake) {
        if (parent == null || parent.isEmpty()) {
            return Map.of();
        }
        Object value = parent.get(keyCamel);
        if (value == null) {
            value = parent.get(keySnake);
        }
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    /** 读取布尔字段,缺失或不可解析时返回 defaultValue。 */
    private boolean readBoolean(Map<?, ?> map, String keyCamel, String keySnake, boolean defaultValue) {
        if (map == null || map.isEmpty()) {
            return defaultValue;
        }
        Object value = map.get(keyCamel);
        if (value == null) {
            value = map.get(keySnake);
        }
        Boolean boolValue = toBoolean(value);
        return boolValue == null ? defaultValue : boolValue;
    }

    /**
     * 读取布尔字段,缺失返回 null。
     * 用于 WebSearchConfig 中需要区分"未设置"和"显式 false"的字段。
     */
    private Boolean readBooleanNullable(Map<?, ?> map, String keyCamel, String keySnake) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Object value = map.get(keyCamel);
        if (value == null) {
            value = map.get(keySnake);
        }
        return toBoolean(value);
    }

    /** 读取字符串字段,缺失返回 defaultValue。返回值已 trim。 */
    private String readString(Map<?, ?> map, String keyCamel, String keySnake, String defaultValue) {
        if (map == null || map.isEmpty()) {
            return defaultValue;
        }
        Object value = map.get(keyCamel);
        if (value == null) {
            value = map.get(keySnake);
        }
        if (value == null) {
            return defaultValue;
        }
        return String.valueOf(value).trim();
    }

    /** 读取整数字段,支持 Number 类型直接转换或字符串解析。 */
    private int readInt(Map<?, ?> map, String keyCamel, String keySnake, int defaultValue) {
        if (map == null || map.isEmpty()) {
            return defaultValue;
        }
        Object value = map.get(keyCamel);
        if (value == null) {
            value = map.get(keySnake);
        }
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 容错地把任意对象转成 Boolean:
     * Boolean 类型直接返回,String 调用 Boolean.parseBoolean,null/其他类型返回 null。
     */
    private Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /** 正数原样返回,非正数返回 null(用于 maxResults 等"未设置 vs 设置为0"的区分)。 */
    private Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    /**
     * 计算下一次 TTL 过期时间。
     *
     * @return OffsetDateTime
     */
    public OffsetDateTime nextTtlExpiresAt() {
        return OffsetDateTime.now().plusMinutes(ttlMinutes);
    }

    /**
     * 计算中断状态(WAITING/FAILED/CANCELED)的延长 TTL。
     *
     * <p>失败/暂停的 run 保留较长时间(默认 7 天),便于用户事后查询失败原因或恢复执行。
     * 优先从本地热加载配置读取,允许动态调整;否则用 application.yml 配置或硬编码 7 天兜底。</p>
     */
    public OffsetDateTime nextInterruptedExpiresAt() {
        return OffsetDateTime.now().plusDays(resolveInterruptedTtlDays());
    }

    /**
     * 判断中断状态 run 是否到了应该被清理为 EXPIRED 的时刻。
     *
     * <p>仅 WAITING/FAILED/CANCELED 三种状态走 interrupted TTL 检查,
     * 其他状态由其他流程管理生命周期。</p>
     */
    public boolean shouldMarkExpired(AgentRun run) {
        if (run == null) {
            return false;
        }
        AgentRunStatus status = run.getStatus();
        if (status != AgentRunStatus.WAITING
                && status != AgentRunStatus.FAILED
                && status != AgentRunStatus.CANCELED) {
            return false;
        }
        if (run.getTtlExpiresAt() == null) {
            return false;
        }
        return OffsetDateTime.now().isAfter(run.getTtlExpiresAt());
    }

    /**
     * 对象转 JSON 字符串。
     *
     * @param obj 任意对象
     * @return JSON 字符串（失败时返回 {}）
     */
    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 生成下一条事件序号（Redis-only, fail-fast）。
     * <p>
     * 设计说明：
     * 1. 使用 setIfAbsent 初始化 key，避免首次写入时 key 不存在；
     * 2. 使用 INCR 实现原子递增；
     * 3. 每次刷新 TTL，确保 run 生命周期内 key 有效；
     * 4. 任一环节异常直接抛错，不做 DB 回退，避免“看似可用但可能乱序”的隐患。
     *
     * @param runId 任务 ID
     * @return 下一序号
     */
    private int nextSeq(String runId) {
        String key = eventSeqKey(runId);
        try {
            // 第一次写入时初始化为 0,INCR 后即为 1
            redisTemplate.opsForValue().setIfAbsent(key, "0", Duration.ofMinutes(ttlMinutes));
            Long next = redisTemplate.opsForValue().increment(key);
            if (next == null) {
                throw new IllegalStateException("Redis INCR returned null");
            }
            // 每次刷新 TTL,防止长 run 中途 key 过期
            redisTemplate.expire(key, Duration.ofMinutes(ttlMinutes));
            // seq 是 INT 类型,溢出时抛错,理论上不该发生
            if (next > Integer.MAX_VALUE) {
                throw new IllegalStateException("Event seq overflow: " + next);
            }
            return next.intValue();
        } catch (Exception e) {
            String msg = String.format("Next seq generation failed (Redis only): runId=%s", runId);
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 组装 run 对应的 Redis 事件序号 key。
     *
     * @param runId 任务 ID
     * @return Redis key
     */
    private String eventSeqKey(String runId) {
        return EVENT_SEQ_KEY_PREFIX + runId;
    }

    /**
     * 规范化事件 payload。
     * <p>
     * 策略：
     * 1. 默认允许原样写入；
     * 2. 当 payload 长度超过上限时，写入“截断摘要对象”，避免事件体无限增长；
     * 3. 摘要对象保留 event_type、原始长度和预览内容，便于排查。
     *
     * <p>三级降级:</p>
     * <ol>
     *   <li>正常预览(payloadPreviewChars 字符):若摘要对象总长仍在限制内,直接落库。</li>
     *   <li>缩减预览(maxChars/4 字符):适用于摘要对象元数据加预览仍超长的极端场景。</li>
     *   <li>最简标记 {@code {"truncated":true}}:兜底,确保任何情况下都能落库。</li>
     * </ol>
     *
     * @param eventType   事件类型
     * @param payloadJson 原始 payload JSON
     * @return 可落库的 payload JSON
     */
    private String normalizePayloadJson(String eventType, String payloadJson) {
        String normalized = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        // 未超限直接返回原文
        if (payloadMaxChars <= 0 || normalized.length() <= payloadMaxChars) {
            return normalized;
        }

        // 计算合理的预览长度,夹在 [128, payloadMaxChars] 之间
        int previewChars = payloadPreviewChars <= 0 ? 1024 : payloadPreviewChars;
        previewChars = Math.min(previewChars, payloadMaxChars);
        previewChars = Math.max(previewChars, 128);
        String preview = normalized.substring(0, Math.min(previewChars, normalized.length()));

        // 第一级:正常预览
        Map<String, Object> compact = new HashMap<>();
        compact.put("truncated", true);
        compact.put("event_type", eventType == null ? "" : eventType);
        compact.put("original_size", normalized.length());
        compact.put("max_size", payloadMaxChars);
        compact.put("payload_preview", preview);
        String compactJson = writeJson(compact);
        if (compactJson.length() <= payloadMaxChars) {
            log.warn("Event payload truncated: eventType={}, originalSize={}, maxSize={}",
                    eventType, normalized.length(), payloadMaxChars);
            return compactJson;
        }

        // 第二级:极端情况下继续缩减，确保落库字符串可控。
        int adjustedPreview = Math.max(64, payloadMaxChars / 4);
        compact.put("payload_preview", normalized.substring(0, Math.min(adjustedPreview, normalized.length())));
        compactJson = writeJson(compact);
        if (compactJson.length() <= payloadMaxChars) {
            log.warn("Event payload truncated with compact preview: eventType={}, originalSize={}, maxSize={}",
                    eventType, normalized.length(), payloadMaxChars);
            return compactJson;
        }

        // 第三级:最简标记兜底
        log.warn("Event payload replaced by minimal marker: eventType={}, originalSize={}, maxSize={}",
                eventType, normalized.length(), payloadMaxChars);
        return "{\"truncated\":true}";
    }

    /**
     * 从 ext JSON 里读取指定字段，缺失或异常时返回空字符串。
     *
     * @param extJson ext 字段 JSON
     * @param field   目标字段名
     * @return 字段值字符串
     */
    private String extractField(String extJson, String field) {
        if (extJson == null || extJson.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> map = objectMapper.readValue(extJson, Map.class);
            Object v = map.get(field);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解析中断状态 run 的 TTL 天数。
     *
     * <p>优先级:</p>
     * <ol>
     *   <li>本地热加载配置 {@code agent.llm.runtime.resume.interruptedTtlDays}(支持热更新)</li>
     *   <li>application.yml 中 {@code agent.run.interrupted-ttl-days}</li>
     *   <li>硬编码兜底 7 天</li>
     * </ol>
     */
    private int resolveInterruptedTtlDays() {
        int local = llmLocalConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getResume)
                .map(AgentLlmProperties.Resume::getInterruptedTtlDays)
                .orElse(0);
        if (local > 0) {
            return local;
        }
        return interruptedTtlDays > 0 ? interruptedTtlDays : 7;
    }

    /**
     * 解析 checkpoint 版本号,空白则返回 "v1"。
     * 主要供 createRun 时写入 ext.checkpoint_version 用。
     */
    private String resolveCheckpointVersion() {
        String version = checkpointVersion == null ? "" : checkpointVersion.trim();
        if (version.isBlank()) {
            return "v1";
        }
        return version;
    }

    /**
     * Run 级能力配置聚合结果。
     *
     * @param webSearchEnabled          网页搜索是否启用
     * @param webSearchConfig           网页搜索详细配置(backend / strength 等)
     * @param codeInterpreterEnabled    代码解释器是否启用(默认 true,保持历史行为兼容)
     * @param codeInterpreterMaxCredits 代码解释器的信用额度上限(0 表示不限)
     * @param smartRetrievalEnabled     智能检索是否启用(后端尚未实现,目前仅占位)
     */
    public record RunConfig(
            boolean webSearchEnabled,
            AgentContext.WebSearchConfig webSearchConfig,
            boolean codeInterpreterEnabled,
            int codeInterpreterMaxCredits,
            boolean smartRetrievalEnabled
    ) {
        /**
         * 默认配置:webSearch 关、codeInterpreter 开(兼容历史)、smartRetrieval 关。
         * 用于 ext 解析失败时的安全兜底。
         */
        public static RunConfig defaults() {
            return new RunConfig(false, AgentContext.WebSearchConfig.empty(), true, 0, false);
        }
    }

}
