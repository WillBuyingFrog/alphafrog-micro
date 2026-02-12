package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.agent.workflow.TodoStatus;
import world.willfrog.agent.workflow.WorkflowState;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunStateStore {

    private static final String PREFIX = "agent:run:";
    private static final String PLAN_KEY = ":plan";
    private static final String PLAN_VALID_KEY = ":plan_valid";
    private static final String PLAN_OVERRIDE_KEY = ":plan_override";
    private static final String STATUS_KEY = ":status";
    private static final String OBSERVABILITY_KEY = ":observability";

    private static final String WORKFLOW_STATE_KEY = ":workflow_state";
    private static final String TOOL_CALL_COUNT_KEY = ":tool_call_count";

    // legacy keys for read compatibility
    private static final String TASK_INDEX_KEY = ":tasks";
    private static final String TASK_KEY_PREFIX = ":task:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.flow.hitl.state-ttl-seconds:3600}")
    private long ttlSeconds;

    public void recordPlan(String runId, String planJson, boolean valid) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(planKey(runId), nvl(planJson));
        redisTemplate.opsForValue().set(planValidKey(runId), String.valueOf(valid));
        touch(planKey(runId));
        touch(planValidKey(runId));
    }

    public void storePlanOverride(String runId, String planJson) {
        if (blank(runId)) {
            return;
        }
        recordPlan(runId, planJson, false);
        markPlanOverride(runId, true);
    }

    public void markPlanOverride(String runId, boolean override) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(planOverrideKey(runId), String.valueOf(override));
        touch(planOverrideKey(runId));
    }

    public void clearPlanOverride(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(planOverrideKey(runId));
    }

    public Optional<String> loadPlan(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String planJson = redisTemplate.opsForValue().get(planKey(runId));
        if (planJson == null || planJson.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(planJson);
    }

    public Optional<Boolean> loadPlanValid(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(planValidKey(runId));
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(Boolean.parseBoolean(value));
    }

    public boolean isPlanOverride(String runId) {
        if (blank(runId)) {
            return false;
        }
        String value = redisTemplate.opsForValue().get(planOverrideKey(runId));
        return Boolean.parseBoolean(value);
    }

    public void markRunStatus(String runId, String status) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(statusKey(runId), nvl(status));
        touch(statusKey(runId));
    }

    public Optional<String> loadRunStatus(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String status = redisTemplate.opsForValue().get(statusKey(runId));
        if (status == null || status.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(status);
    }

    public void saveObservability(String runId, String observabilityJson) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(observabilityKey(runId), nvl(observabilityJson));
        touch(observabilityKey(runId));
    }

    public Optional<String> loadObservability(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(observabilityKey(runId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(json);
    }

    public void saveWorkflowState(String runId, WorkflowState state) {
        if (blank(runId) || state == null) {
            return;
        }
        redisTemplate.opsForValue().set(workflowStateKey(runId), safeWrite(state));
        touch(workflowStateKey(runId));
    }

    public Optional<WorkflowState> loadWorkflowState(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(workflowStateKey(runId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, WorkflowState.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void clearWorkflowState(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(workflowStateKey(runId));
    }

    public int incrementToolCallCount(String runId, int delta) {
        if (blank(runId)) {
            return 0;
        }
        int safeDelta = Math.max(0, delta);
        if (safeDelta == 0) {
            return getToolCallCount(runId);
        }
        Long value = redisTemplate.opsForValue().increment(toolCallCountKey(runId), safeDelta);
        touch(toolCallCountKey(runId));
        return value == null ? 0 : Math.max(0, value.intValue());
    }

    public int getToolCallCount(String runId) {
        if (blank(runId)) {
            return 0;
        }
        String value = redisTemplate.opsForValue().get(toolCallCountKey(runId));
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (Exception e) {
            return 0;
        }
    }

    public void resetToolCallCount(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(toolCallCountKey(runId));
    }

    public void setToolCallCount(String runId, int count) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(toolCallCountKey(runId), String.valueOf(Math.max(0, count)));
        touch(toolCallCountKey(runId));
    }

    public String buildProgressJson(String runId, String planJson) {
        JsonNode root = parseJson(planJson);
        if (root != null && root.path("items").isArray()) {
            return buildTodoProgressJson(runId, root.path("items"));
        }
        if (root != null && root.path("tasks").isArray()) {
            return buildLegacyProgressJson(runId, root.path("tasks"));
        }
        return "{}";
    }

    public void clear(String runId) {
        if (blank(runId)) {
            return;
        }
        clearTasks(runId);
        redisTemplate.delete(planKey(runId));
        redisTemplate.delete(planValidKey(runId));
        redisTemplate.delete(planOverrideKey(runId));
        redisTemplate.delete(statusKey(runId));
        redisTemplate.delete(observabilityKey(runId));
        redisTemplate.delete(workflowStateKey(runId));
        redisTemplate.delete(toolCallCountKey(runId));
    }

    public void clearTasks(String runId) {
        if (blank(runId)) {
            return;
        }
        // clear new workflow checkpoint
        redisTemplate.delete(workflowStateKey(runId));
        // clear legacy task states
        Set<String> taskIds = redisTemplate.opsForSet().members(taskIndexKey(runId));
        if (taskIds != null) {
            for (String taskId : taskIds) {
                redisTemplate.delete(taskKey(runId, taskId));
            }
        }
        redisTemplate.delete(taskIndexKey(runId));
    }

    private String buildTodoProgressJson(String runId, JsonNode itemsNode) {
        Optional<WorkflowState> workflowState = loadWorkflowState(runId);
        Map<String, String> completedStatusById = new HashMap<>();
        int currentIndex = -1;
        if (workflowState.isPresent()) {
            currentIndex = workflowState.get().getCurrentIndex();
            for (var item : workflowState.get().getCompletedItems()) {
                String key = nvl(item.getId());
                if (key.isBlank()) {
                    continue;
                }
                TodoStatus status = item.getStatus() == null ? TodoStatus.COMPLETED : item.getStatus();
                completedStatusById.put(key, status.name());
            }
        }

        int total = 0;
        int completed = 0;
        int failed = 0;
        int running = 0;
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();

        int idx = 0;
        for (JsonNode node : itemsNode) {
            total++;
            String id = nvl(node.path("id").asText("todo_" + (idx + 1)));
            String status = completedStatusById.get(id);
            if (status == null || status.isBlank()) {
                status = idx == currentIndex ? "RUNNING" : "PENDING";
            }

            if ("COMPLETED".equals(status)) {
                completed++;
            } else if ("FAILED".equals(status)) {
                failed++;
            } else if ("RUNNING".equals(status)) {
                running++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("sequence", node.path("sequence").asInt(idx + 1));
            row.put("type", nvl(node.path("type").asText("")));
            row.put("tool", nvl(node.path("toolName").asText("")));
            row.put("status", status);
            tasks.add(row);
            idx++;
        }

        int pending = Math.max(0, total - completed - failed - running);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", total);
        payload.put("completed", completed);
        payload.put("failed", failed);
        payload.put("running", running);
        payload.put("pending", pending);
        payload.put("tasks", tasks);
        payload.put("tool_calls_used", getToolCallCount(runId));
        return safeWrite(payload);
    }

    private String buildLegacyProgressJson(String runId, JsonNode tasksNode) {
        Set<String> taskIds = redisTemplate.opsForSet().members(taskIndexKey(runId));
        Map<String, String> legacyStatus = new HashMap<>();
        if (taskIds != null) {
            for (String taskId : taskIds) {
                JsonNode taskState = parseJson(redisTemplate.opsForValue().get(taskKey(runId, taskId)));
                if (taskState != null) {
                    legacyStatus.put(taskId, nvl(taskState.path("status").asText("PENDING")));
                }
            }
        }

        int total = 0;
        int completed = 0;
        int failed = 0;
        int running = 0;
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();

        for (JsonNode node : tasksNode) {
            total++;
            String id = nvl(node.path("id").asText(""));
            String status = legacyStatus.getOrDefault(id, "PENDING");
            if ("COMPLETED".equals(status)) {
                completed++;
            } else if ("FAILED".equals(status)) {
                failed++;
            } else if ("RUNNING".equals(status)) {
                running++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("type", nvl(node.path("type").asText("")));
            row.put("tool", nvl(node.path("tool").asText("")));
            row.put("status", status);
            tasks.add(row);
        }

        int pending = Math.max(0, total - completed - failed - running);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", total);
        payload.put("completed", completed);
        payload.put("failed", failed);
        payload.put("running", running);
        payload.put("pending", pending);
        payload.put("tasks", tasks);
        return safeWrite(payload);
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private void touch(String key) {
        if (ttlSeconds <= 0 || key == null || key.isBlank()) {
            return;
        }
        try {
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.debug("touch redis key failed: {}", key, e);
        }
    }

    private String safeWrite(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private String planKey(String runId) {
        return PREFIX + runId + PLAN_KEY;
    }

    private String planValidKey(String runId) {
        return PREFIX + runId + PLAN_VALID_KEY;
    }

    private String planOverrideKey(String runId) {
        return PREFIX + runId + PLAN_OVERRIDE_KEY;
    }

    private String statusKey(String runId) {
        return PREFIX + runId + STATUS_KEY;
    }

    private String observabilityKey(String runId) {
        return PREFIX + runId + OBSERVABILITY_KEY;
    }

    private String workflowStateKey(String runId) {
        return PREFIX + runId + WORKFLOW_STATE_KEY;
    }

    private String toolCallCountKey(String runId) {
        return PREFIX + runId + TOOL_CALL_COUNT_KEY;
    }

    private String taskIndexKey(String runId) {
        return PREFIX + runId + TASK_INDEX_KEY;
    }

    private String taskKey(String runId, String taskId) {
        return PREFIX + runId + TASK_KEY_PREFIX + taskId;
    }
}
