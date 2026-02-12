package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;

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
 */
public class AgentEventService {

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

    @Value("${agent.run.ttl-minutes:60}")
    private int ttlMinutes;

    @Value("${agent.run.interrupted-ttl-days:7}")
    private int interruptedTtlDays;

    @Value("${agent.run.checkpoint-version:v2}")
    private String checkpointVersion;

    @Value("${agent.event.payload.max-chars:10000}")
    private int payloadMaxChars;

    @Value("${agent.event.payload.preview-chars:4096}")
    private int payloadPreviewChars;

    /**
     * 创建新的 run 记录并写入初始事件。
     *
     * @param userId         用户 ID
     * @param message        用户输入
     * @param contextJson    上下文 JSON
     * @param idempotencyKey 幂等键
     * @param modelName      模型名（可为空，后续会用默认）
     * @param endpointName   端点名（可为空，后续会用默认）
     * @param debugMode      run 级调试模式开关
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
                              boolean debugMode) {
        String runId = java.util.UUID.randomUUID().toString().replace("-", "");

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
        append(runId, userId, "RUN_RECEIVED", ext);
        return runMapper.findByIdAndUser(runId, userId);
    }

    /**
     * 判断当前 run 是否还可继续执行。
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
            if (Boolean.TRUE.equals(direct)) {
                return true;
            }
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

    private Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 计算下一次 TTL 过期时间。
     *
     * @return OffsetDateTime
     */
    public OffsetDateTime nextTtlExpiresAt() {
        return OffsetDateTime.now().plusMinutes(ttlMinutes);
    }

    public OffsetDateTime nextInterruptedExpiresAt() {
        return OffsetDateTime.now().plusDays(resolveInterruptedTtlDays());
    }

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
            redisTemplate.opsForValue().setIfAbsent(key, "0", Duration.ofMinutes(ttlMinutes));
            Long next = redisTemplate.opsForValue().increment(key);
            if (next == null) {
                throw new IllegalStateException("Redis INCR returned null");
            }
            redisTemplate.expire(key, Duration.ofMinutes(ttlMinutes));
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
     * @param eventType   事件类型
     * @param payloadJson 原始 payload JSON
     * @return 可落库的 payload JSON
     */
    private String normalizePayloadJson(String eventType, String payloadJson) {
        String normalized = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        if (payloadMaxChars <= 0 || normalized.length() <= payloadMaxChars) {
            return normalized;
        }

        int previewChars = payloadPreviewChars <= 0 ? 1024 : payloadPreviewChars;
        previewChars = Math.min(previewChars, payloadMaxChars);
        previewChars = Math.max(previewChars, 128);
        String preview = normalized.substring(0, Math.min(previewChars, normalized.length()));

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

        // 极端情况下继续缩减，确保落库字符串可控。
        int adjustedPreview = Math.max(64, payloadMaxChars / 4);
        compact.put("payload_preview", normalized.substring(0, Math.min(adjustedPreview, normalized.length())));
        compactJson = writeJson(compact);
        if (compactJson.length() <= payloadMaxChars) {
            log.warn("Event payload truncated with compact preview: eventType={}, originalSize={}, maxSize={}",
                    eventType, normalized.length(), payloadMaxChars);
            return compactJson;
        }

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

    private String resolveCheckpointVersion() {
        String version = checkpointVersion == null ? "" : checkpointVersion.trim();
        if (version.isBlank()) {
            return "v1";
        }
        return version;
    }

}
