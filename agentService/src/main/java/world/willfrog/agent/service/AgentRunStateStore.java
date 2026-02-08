package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.agent.graph.ParallelPlan;
import world.willfrog.agent.graph.ParallelTaskResult;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final String TASK_INDEX_KEY = ":tasks";
    private static final String TASK_KEY_PREFIX = ":task:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.flow.hitl.state-ttl-seconds:3600}")
    private long ttlSeconds;

    @Data
    @Builder
    public static class TaskState {
        private String taskId;
        private String status;
        private String type;
        private String tool;
        private boolean success;
        private String output;
        private String outputPreview;
        private String error;
        private String updatedAt;
    }

    public void recordPlan(String runId, String planJson, boolean valid) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(planKey(runId), nvl(planJson));
        redisTemplate.opsForValue().set(planValidKey(runId), String.valueOf(valid));
        touch(runId, planKey(runId));
        touch(runId, planValidKey(runId));
    }

    public void storePlanOverride(String runId, String planJson) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        recordPlan(runId, planJson, false);
        markPlanOverride(runId, true);
    }

    public void markPlanOverride(String runId, boolean override) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(planOverrideKey(runId), String.valueOf(override));
        touch(runId, planOverrideKey(runId));
    }

    public void clearPlanOverride(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        redisTemplate.delete(planOverrideKey(runId));
    }

    public Optional<String> loadPlan(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        String planJson = redisTemplate.opsForValue().get(planKey(runId));
        if (planJson == null || planJson.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(planJson);
    }

    public Optional<Boolean> loadPlanValid(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(planValidKey(runId));
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(Boolean.parseBoolean(value));
    }

    public boolean isPlanOverride(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        String value = redisTemplate.opsForValue().get(planOverrideKey(runId));
        return Boolean.parseBoolean(value);
    }

    public void markRunStatus(String runId, String status) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(statusKey(runId), nvl(status));
        touch(runId, statusKey(runId));
    }

    public Optional<String> loadRunStatus(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        String status = redisTemplate.opsForValue().get(statusKey(runId));
        if (status == null || status.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(status);
    }

    public void markTaskStarted(String runId, ParallelPlan.PlanTask task) {
        if (runId == null || runId.isBlank() || task == null || task.getId() == null) {
            return;
        }
        TaskState state = TaskState.builder()
                .taskId(task.getId())
                .status("RUNNING")
                .type(nvl(task.getType()))
                .tool(nvl(task.getTool()))
                .success(false)
                .output("")
                .outputPreview("")
                .error("")
                .updatedAt(OffsetDateTime.now().toString())
                .build();
        saveTaskState(runId, task.getId(), state);
    }

    public void saveTaskResult(String runId, String taskId, ParallelTaskResult result) {
        if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()) {
            return;
        }
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .status(result != null && result.isSuccess() ? "COMPLETED" : "FAILED")
                .type(result == null ? "" : nvl(result.getType()))
                .tool("")
                .success(result != null && result.isSuccess())
                .output(result == null ? "" : nvl(result.getOutput()))
                .outputPreview(preview(result == null ? "" : result.getOutput()))
                .error(result == null ? "" : nvl(result.getError()))
                .updatedAt(OffsetDateTime.now().toString())
                .build();
        saveTaskState(runId, taskId, state);
    }

    public Map<String, ParallelTaskResult> loadTaskResults(String runId) {
        if (runId == null || runId.isBlank()) {
            return Map.of();
        }
        Set<String> taskIds = redisTemplate.opsForSet().members(taskIndexKey(runId));
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ParallelTaskResult> results = new HashMap<>();
        for (String taskId : taskIds) {
            TaskState state = loadTaskState(runId, taskId);
            if (state == null) {
                continue;
            }
            if (!"COMPLETED".equals(state.getStatus()) && !"FAILED".equals(state.getStatus())) {
                continue;
            }
            ParallelTaskResult result = ParallelTaskResult.builder()
                    .taskId(taskId)
                    .type(state.getType())
                    .success(state.isSuccess())
                    .output(state.getOutput())
                    .error(state.getError())
                    .build();
            results.put(taskId, result);
        }
        return results;
    }

    public String buildProgressJson(String runId, String planJson) {
        ParallelPlan plan = parsePlan(planJson);
        List<ParallelPlan.PlanTask> tasks = plan.getTasks() == null ? List.of() : plan.getTasks();
        Map<String, TaskState> states = loadTaskStates(runId);

        int total = tasks.size();
        int completed = 0;
        int failed = 0;
        int running = 0;

        List<Map<String, Object>> taskSummaries = new ArrayList<>();
        for (ParallelPlan.PlanTask task : tasks) {
            TaskState state = states.get(task.getId());
            String status = state == null ? "PENDING" : nvl(state.getStatus());
            if ("COMPLETED".equals(status)) {
                completed++;
            } else if ("FAILED".equals(status)) {
                failed++;
            } else if ("RUNNING".equals(status)) {
                running++;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("id", task.getId());
            item.put("type", nvl(task.getType()));
            item.put("tool", nvl(task.getTool()));
            item.put("status", status);
            item.put("success", state != null && state.isSuccess());
            item.put("output_preview", state == null ? "" : nvl(state.getOutputPreview()));
            taskSummaries.add(item);
        }

        int pending = Math.max(0, total - completed - failed - running);
        Map<String, Object> progress = new HashMap<>();
        progress.put("total", total);
        progress.put("completed", completed);
        progress.put("failed", failed);
        progress.put("running", running);
        progress.put("pending", pending);
        progress.put("tasks", taskSummaries);
        try {
            return objectMapper.writeValueAsString(progress);
        } catch (Exception e) {
            return "{}";
        }
    }

    public void clear(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        Set<String> taskIds = redisTemplate.opsForSet().members(taskIndexKey(runId));
        if (taskIds != null) {
            for (String taskId : taskIds) {
                redisTemplate.delete(taskKey(runId, taskId));
            }
        }
        redisTemplate.delete(taskIndexKey(runId));
        redisTemplate.delete(planKey(runId));
        redisTemplate.delete(planValidKey(runId));
        redisTemplate.delete(planOverrideKey(runId));
        redisTemplate.delete(statusKey(runId));
    }

    public void clearTasks(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        Set<String> taskIds = redisTemplate.opsForSet().members(taskIndexKey(runId));
        if (taskIds != null) {
            for (String taskId : taskIds) {
                redisTemplate.delete(taskKey(runId, taskId));
            }
        }
        redisTemplate.delete(taskIndexKey(runId));
    }

    private void saveTaskState(String runId, String taskId, TaskState state) {
        String json = safeWrite(state);
        redisTemplate.opsForValue().set(taskKey(runId, taskId), json);
        redisTemplate.opsForSet().add(taskIndexKey(runId), taskId);
        touch(runId, taskKey(runId, taskId));
        touch(runId, taskIndexKey(runId));
    }

    private TaskState loadTaskState(String runId, String taskId) {
        String json = redisTemplate.opsForValue().get(taskKey(runId, taskId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TaskState.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, TaskState> loadTaskStates(String runId) {
        Set<String> taskIds = redisTemplate.opsForSet().members(taskIndexKey(runId));
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        Map<String, TaskState> states = new HashMap<>();
        for (String taskId : taskIds) {
            TaskState state = loadTaskState(runId, taskId);
            if (state != null) {
                states.put(taskId, state);
            }
        }
        return states;
    }

    private ParallelPlan parsePlan(String json) {
        if (json == null || json.isBlank()) {
            return new ParallelPlan();
        }
        try {
            return objectMapper.readValue(json, ParallelPlan.class);
        } catch (Exception e) {
            return new ParallelPlan();
        }
    }

    private String safeWrite(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void touch(String runId, String key) {
        if (ttlSeconds <= 0) {
            return;
        }
        try {
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.debug("Failed to expire key: {}", key, e);
        }
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

    private String taskIndexKey(String runId) {
        return PREFIX + runId + TASK_INDEX_KEY;
    }

    private String taskKey(String runId, String taskId) {
        return PREFIX + runId + TASK_KEY_PREFIX + taskId;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    private String nvl(String v) {
        return v == null ? "" : v;
    }
}
