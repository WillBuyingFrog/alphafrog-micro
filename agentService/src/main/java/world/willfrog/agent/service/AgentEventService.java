package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentEventService {

    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.run.ttl-minutes:60}")
    private int ttlMinutes;

    public AgentRun createRun(String userId, String message, String contextJson, String idempotencyKey) {
        String runId = java.util.UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> ext = new HashMap<>();
        ext.put("user_goal", message);
        ext.put("context_json", contextJson == null ? "" : contextJson);
        ext.put("idempotency_key", idempotencyKey == null ? "" : idempotencyKey);

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

    public boolean isRunnable(String runId, String userId) {
        AgentRun run = runMapper.findByIdAndUser(runId, userId);
        if (run == null) {
            return false;
        }
        if (run.getStatus() == AgentRunStatus.CANCELED) {
            log.info("Run canceled, stop: {}", runId);
            return false;
        }
        if (run.getTtlExpiresAt() != null && OffsetDateTime.now().isAfter(run.getTtlExpiresAt())) {
            log.info("Run expired (ttl), stop: {}", runId);
            return false;
        }
        return true;
    }

    public void append(String runId, String userId, String eventType, Object payload) {
        AgentRun run = runMapper.findByIdAndUser(runId, userId);
        if (run == null) {
            return;
        }
        Integer maxSeq = eventMapper.findMaxSeq(runId);
        int nextSeq = (maxSeq == null ? 0 : maxSeq) + 1;

        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(runId);
        event.setSeq(nextSeq);
        event.setEventType(eventType);
        event.setPayloadJson(payload instanceof String ? (String) payload : writeJson(payload));
        eventMapper.insert(event);
    }

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

    public OffsetDateTime nextTtlExpiresAt() {
        return OffsetDateTime.now().plusMinutes(ttlMinutes);
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
