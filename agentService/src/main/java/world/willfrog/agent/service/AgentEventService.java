package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentEventService {

    private static final String EVENT_SEQ_KEY_PREFIX = "agent:run:event_seq:";

    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${agent.run.ttl-minutes:60}")
    private int ttlMinutes;

    /**
     * 创建新的 run 记录并写入初始事件。
     *
     * @param userId         用户 ID
     * @param message        用户输入
     * @param contextJson    上下文 JSON
     * @param idempotencyKey 幂等键
     * @param modelName      模型名（可为空，后续会用默认）
     * @param endpointName   端点名（可为空，后续会用默认）
     * @return 创建后的 run
     */
    public AgentRun createRun(String userId,
                              String message,
                              String contextJson,
                              String idempotencyKey,
                              String modelName,
                              String endpointName) {
        String runId = java.util.UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> ext = new HashMap<>();
        ext.put("user_goal", message);
        ext.put("context_json", contextJson == null ? "" : contextJson);
        ext.put("idempotency_key", idempotencyKey == null ? "" : idempotencyKey);
        ext.put("model_name", modelName == null ? "" : modelName);
        ext.put("endpoint_name", endpointName == null ? "" : endpointName);

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
        int nextSeq = nextSeq(runId);
        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(runId);
        event.setSeq(nextSeq);
        event.setEventType(eventType);
        event.setPayloadJson(payload instanceof String ? (String) payload : writeJson(payload));
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
     * 计算下一次 TTL 过期时间。
     *
     * @return OffsetDateTime
     */
    public OffsetDateTime nextTtlExpiresAt() {
        return OffsetDateTime.now().plusMinutes(ttlMinutes);
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

    private String eventSeqKey(String runId) {
        return EVENT_SEQ_KEY_PREFIX + runId;
    }

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

}
