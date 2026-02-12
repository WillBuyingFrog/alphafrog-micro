package world.willfrog.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DagTaskExecutor {

    private final ParallelTaskExecutor taskExecutor;
    private final ParallelPlanValidator planValidator = new ParallelPlanValidator();
    private final AgentPromptService promptService;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    private final AgentObservabilityService observabilityService;
    private final AgentEventService eventService;
    private final AgentRunMapper runMapper;
    private final AgentRunStateStore stateStore;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    @Value("${agent.flow.parallel.max-tasks:6}")
    private int maxTasks;

    @Value("${agent.flow.parallel.sub-agent-max-steps:6}")
    private int subAgentMaxSteps;

    @Value("${agent.flow.parallel.max-parallel-tasks:-1}")
    private int maxParallelTasks;

    @Value("${agent.flow.parallel.max-sub-agents:-1}")
    private int maxSubAgents;

    @Value("${agent.flow.parallel.max-local-replans:2}")
    private int defaultMaxLocalReplans;

    public ExecutionResult execute(ExecutionRequest request) {
        if (request.getPlan() == null) {
            return ExecutionResult.failed("plan_missing", request.getPlan(), Map.of(), 0, List.of(), "");
        }

        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();

        ParallelPlan currentPlan = request.getPlan();
        Map<String, ParallelTaskResult> results = new HashMap<>(stateStore.loadTaskResults(runId));
        int maxLocalReplans = resolveMaxLocalReplans();
        int replanCount = 0;

        while (true) {
            long startedAt = System.currentTimeMillis();
            results = taskExecutor.execute(
                    currentPlan,
                    runId,
                    userId,
                    request.getToolWhitelist(),
                    subAgentMaxSteps,
                    request.getUserGoal(),
                    request.getModel(),
                    results,
                    request.getEndpointName(),
                    request.getEndpointBaseUrl(),
                    request.getModelName()
            );
            observabilityService.recordPhaseDuration(
                    runId,
                    AgentObservabilityService.PHASE_PARALLEL_EXECUTION,
                    System.currentTimeMillis() - startedAt
            );

            boolean paused = !eventService.isRunnable(runId, userId);
            List<String> unresolvedTaskIds = unresolvedTaskIds(currentPlan, results);
            boolean allDone = unresolvedTaskIds.isEmpty();

            if (paused) {
                return ExecutionResult.paused(currentPlan, results, replanCount, unresolvedTaskIds);
            }
            if (allDone) {
                String finalAnswer = generateFinalAnswer(request, results);
                if (finalAnswer.isBlank()) {
                    return ExecutionResult.failed("final_answer_blank", currentPlan, results, replanCount, List.of(), "");
                }
                return ExecutionResult.success(currentPlan, results, replanCount, finalAnswer);
            }

            if (replanCount >= maxLocalReplans) {
                String reason = "local_replan_exhausted";
                eventService.append(runId, userId, "PLAN_PATCH_EXHAUSTED", Map.of(
                        "replan_count", replanCount,
                        "max_local_replans", maxLocalReplans,
                        "unresolved_tasks", unresolvedTaskIds,
                        "reason", reason
                ));
                return ExecutionResult.failed(reason, currentPlan, results, replanCount, unresolvedTaskIds, "");
            }

            int round = replanCount + 1;
            eventService.append(runId, userId, "PLAN_PATCH_REQUESTED", Map.of(
                    "replan_round", round,
                    "unresolved_tasks", unresolvedTaskIds
            ));

            PatchPlan patch = generatePatchPlan(
                    request,
                    currentPlan,
                    results,
                    unresolvedTaskIds
            );
            if (patch == null || patch.getTasks().isEmpty()) {
                eventService.append(runId, userId, "PLAN_PATCH_REJECTED", Map.of(
                        "replan_round", round,
                        "reason", "empty_patch_tasks"
                ));
                replanCount = round;
                continue;
            }

            eventService.append(runId, userId, "PLAN_PATCH_CREATED", Map.of(
                    "replan_round", round,
                    "reason", safe(patch.getReason()),
                    "replace_task_ids", patch.getReplaceTaskIds(),
                    "tasks_count", patch.getTasks().size(),
                    "tasks", safeWrite(patch.getTasks())
            ));

            PatchApplyResult patchApply = applyPatchPlan(currentPlan, patch, unresolvedTaskIds, request.getToolWhitelist());
            if (!patchApply.isApplied()) {
                eventService.append(runId, userId, "PLAN_PATCH_REJECTED", Map.of(
                        "replan_round", round,
                        "reason", safe(patchApply.getReason())
                ));
                replanCount = round;
                continue;
            }

            currentPlan = patchApply.getPlan();
            ParallelPlan appliedPlan = currentPlan;
            String patchedPlanJson = safeWrite(currentPlan);
            runMapper.updatePlan(runId, userId, AgentRunStatus.EXECUTING, patchedPlanJson);
            stateStore.recordPlan(runId, patchedPlanJson, true);

            Set<String> replacedIds = new HashSet<>(patchApply.getReplacedTaskIds());
            results.keySet().removeIf(replacedIds::contains);
            results.keySet().removeIf(taskId -> !containsTaskId(appliedPlan, taskId));

            eventService.append(runId, userId, "PLAN_PATCH_APPLIED", Map.of(
                    "replan_round", round,
                    "replace_task_ids", patchApply.getReplacedTaskIds(),
                    "plan", patchedPlanJson
            ));
            replanCount = round;
        }
    }

    public Map<String, Object> buildQualityReport(ParallelPlan plan,
                                                   Map<String, ParallelTaskResult> results,
                                                   List<String> unresolvedTasks,
                                                   Map<String, Object> judgeSummary,
                                                   int replanCount,
                                                   Double planScore) {
        List<String> unresolved = unresolvedTasks == null ? List.of() : unresolvedTasks;
        int total = plan == null || plan.getTasks() == null ? 0 : plan.getTasks().size();
        int unresolvedCount = unresolved.size();
        double coverage = total <= 0 ? (unresolvedCount == 0 ? 1D : 0D) : ((double) (total - unresolvedCount)) / total;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("goal_coverage", clamp(coverage, 0D, 1D));
        report.put("unresolved_tasks", unresolved);
        report.put("judge_summary", judgeSummary == null ? Map.of() : judgeSummary);
        report.put("replan_count", Math.max(0, replanCount));
        report.put("plan_score", planScore == null ? 0D : planScore);
        report.put("task_total", total);
        report.put("task_success", Math.max(0, total - unresolvedCount));
        report.put("task_results_summary", summarizeTaskResults(results));
        return report;
    }

    private String generateFinalAnswer(ExecutionRequest request, Map<String, ParallelTaskResult> results) {
        String prompt = promptService.parallelFinalSystemPrompt();
        String resultJson = safeWrite(results);
        List<ChatMessage> finalMessages = List.of(
                new SystemMessage(prompt),
                new UserMessage("目标: " + request.getUserGoal() + "\n结果: " + resultJson)
        );

        long llmStartedAt = System.currentTimeMillis();
        Response<AiMessage> response = request.getModel().generate(finalMessages);
        long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
        String answer = response.content() == null ? "" : safe(response.content().text());
        Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                request.getEndpointName(),
                request.getEndpointBaseUrl(),
                request.getModelName(),
                finalMessages,
                null,
                Map.of("stage", "parallel_final")
        );
        observabilityService.recordLlmCall(
                request.getRun().getId(),
                AgentObservabilityService.PHASE_SUMMARIZING,
                response.tokenUsage(),
                llmDurationMs,
                request.getEndpointName(),
                request.getModelName(),
                null,
                llmRequestSnapshot,
                answer
        );
        return answer;
    }

    private PatchPlan generatePatchPlan(ExecutionRequest request,
                                        ParallelPlan currentPlan,
                                        Map<String, ParallelTaskResult> results,
                                        List<String> unresolvedTaskIds) {
        try {
            String prompt = promptService.parallelPatchPlannerSystemPrompt();
            if (prompt.isBlank()) {
                prompt = """
                        You are a patch planner for a running DAG plan.
                        Output JSON only with schema:
                        {"reason":"...","replace_task_ids":["..."],"tasks":[{"id":"...","type":"tool|sub_agent","tool":"...","args":{},"dependsOn":[],"goal":"...","maxSteps":1,"description":"..."}]}
                        Rules:
                        1) Patch only unresolved downstream region.
                        2) Keep plan executable and dependency-safe.
                        3) Placeholder protocol must be ${task_id.output} or ${task_id.output.path.to.field} only.
                        4) Never output {{...}}, {...}, task.output style placeholders.
                        5) Do not output markdown.
                        """;
            }
            String userPrompt = "Goal:\n" + safe(request.getUserGoal())
                    + "\n\nCurrent plan:\n" + safeWrite(currentPlan)
                    + "\n\nUnresolved task ids:\n" + safeWrite(unresolvedTaskIds)
                    + "\n\nCurrent results:\n" + safeWrite(results);
            List<ChatMessage> messages = List.of(
                    new SystemMessage(prompt),
                    new UserMessage(userPrompt)
            );
            long llmStartedAt = System.currentTimeMillis();
            Response<AiMessage> response = request.getModel().generate(messages);
            long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
            String raw = response.content() == null ? "" : safe(response.content().text());
            Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                    request.getEndpointName(),
                    request.getEndpointBaseUrl(),
                    request.getModelName(),
                    messages,
                    null,
                    Map.of("stage", "parallel_patch_plan")
            );
            observabilityService.recordLlmCall(
                    request.getRun().getId(),
                    AgentObservabilityService.PHASE_PLANNING,
                    response.tokenUsage(),
                    llmDurationMs,
                    request.getEndpointName(),
                    request.getModelName(),
                    null,
                    llmRequestSnapshot,
                    raw
            );
            return parsePatchPlan(raw);
        } catch (Exception e) {
            log.warn("Generate patch plan failed: runId={}", request.getRun().getId(), e);
            return null;
        }
    }

    private PatchApplyResult applyPatchPlan(ParallelPlan basePlan,
                                            PatchPlan patch,
                                            List<String> unresolvedTaskIds,
                                            Set<String> toolWhitelist) {
        if (basePlan == null || patch == null) {
            return PatchApplyResult.rejected("plan_or_patch_missing");
        }
        Set<String> replaceIds = new HashSet<>();
        if (patch.getReplaceTaskIds() != null) {
            for (String id : patch.getReplaceTaskIds()) {
                if (id != null && !id.isBlank()) {
                    replaceIds.add(id.trim());
                }
            }
        }
        if (replaceIds.isEmpty() && unresolvedTaskIds != null) {
            for (String id : unresolvedTaskIds) {
                if (id != null && !id.isBlank()) {
                    replaceIds.add(id.trim());
                }
            }
        }
        if (replaceIds.isEmpty()) {
            return PatchApplyResult.rejected("no_replace_task_ids");
        }
        if (patch.getTasks() == null || patch.getTasks().isEmpty()) {
            return PatchApplyResult.rejected("patch_tasks_empty");
        }

        ParallelPlan merged = new ParallelPlan();
        merged.setStrategy(basePlan.getStrategy());
        merged.setFinalHint(basePlan.getFinalHint());
        List<ParallelPlan.PlanTask> mergedTasks = new ArrayList<>();

        if (basePlan.getTasks() != null) {
            for (ParallelPlan.PlanTask task : basePlan.getTasks()) {
                if (task == null || task.getId() == null || replaceIds.contains(task.getId())) {
                    continue;
                }
                mergedTasks.add(task);
            }
        }
        mergedTasks.addAll(patch.getTasks());

        Set<String> taskIds = new HashSet<>();
        for (ParallelPlan.PlanTask task : mergedTasks) {
            if (task == null || task.getId() == null || task.getId().isBlank()) {
                return PatchApplyResult.rejected("patch_task_id_missing");
            }
            if (!taskIds.add(task.getId())) {
                return PatchApplyResult.rejected("duplicate_task_id_after_patch");
            }
        }

        merged.setTasks(mergedTasks);
        ParallelPlanValidator.Result validation = planValidator.validate(
                merged,
                toolWhitelist,
                maxTasks,
                subAgentMaxSteps,
                maxParallelTasks,
                maxSubAgents
        );
        if (!validation.isValid()) {
            return PatchApplyResult.rejected("patch_plan_invalid:" + safe(validation.getReason()));
        }
        return PatchApplyResult.applied(merged, new ArrayList<>(replaceIds));
    }

    private PatchPlan parsePatchPlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            List<String> replaceTaskIds = new ArrayList<>();
            JsonNode replaceNode = node.path("replace_task_ids");
            if (replaceNode.isArray()) {
                for (JsonNode item : replaceNode) {
                    String id = safe(item.asText(""));
                    if (!id.isBlank()) {
                        replaceTaskIds.add(id);
                    }
                }
            }
            List<ParallelPlan.PlanTask> tasks = new ArrayList<>();
            JsonNode tasksNode = node.path("tasks");
            if (tasksNode.isArray()) {
                for (JsonNode taskNode : tasksNode) {
                    ParallelPlan.PlanTask task = objectMapper.convertValue(taskNode, ParallelPlan.PlanTask.class);
                    if (task.getDependsOn() == null) {
                        task.setDependsOn(new ArrayList<>());
                    }
                    tasks.add(task);
                }
            }
            return PatchPlan.builder()
                    .reason(safe(node.path("reason").asText("")))
                    .replaceTaskIds(replaceTaskIds)
                    .tasks(tasks)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> unresolvedTaskIds(ParallelPlan plan, Map<String, ParallelTaskResult> results) {
        List<String> unresolved = new ArrayList<>();
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            return unresolved;
        }
        Map<String, ParallelTaskResult> safeResults = results == null ? Map.of() : results;
        for (ParallelPlan.PlanTask task : plan.getTasks()) {
            if (task == null || task.getId() == null || task.getId().isBlank()) {
                continue;
            }
            ParallelTaskResult result = safeResults.get(task.getId());
            if (result == null || !result.isSuccess()) {
                unresolved.add(task.getId());
            }
        }
        return unresolved;
    }

    private boolean containsTaskId(ParallelPlan plan, String taskId) {
        if (plan == null || plan.getTasks() == null || taskId == null) {
            return false;
        }
        for (ParallelPlan.PlanTask task : plan.getTasks()) {
            if (task != null && taskId.equals(task.getId())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> summarizeTaskResults(Map<String, ParallelTaskResult> results) {
        int success = 0;
        int failed = 0;
        for (ParallelTaskResult value : (results == null ? Map.<String, ParallelTaskResult>of() : results).values()) {
            if (value == null) {
                continue;
            }
            if (value.isSuccess()) {
                success++;
            } else {
                failed++;
            }
        }
        return Map.of(
                "success", success,
                "failed", failed
        );
    }

    private int resolveMaxLocalReplans() {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxLocalReplans)
                .orElse(0);
        if (local > 0) {
            return clampInt(local, 1, 10);
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxLocalReplans)
                .orElse(0);
        if (base > 0) {
            return clampInt(base, 1, 10);
        }
        return clampInt(defaultMaxLocalReplans, 1, 10);
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    private String safeWrite(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @Data
    @Builder
    public static class ExecutionRequest {
        private AgentRun run;
        private String userId;
        private String userGoal;
        private ParallelPlan plan;
        private ChatLanguageModel model;
        private Set<String> toolWhitelist;
        private String endpointName;
        private String endpointBaseUrl;
        private String modelName;
    }

    @Data
    @Builder
    public static class ExecutionResult {
        private boolean paused;
        private boolean success;
        private String failureReason;
        private String finalAnswer;
        private ParallelPlan finalPlan;
        private Map<String, ParallelTaskResult> taskResults;
        private int replanCount;
        private List<String> unresolvedTasks;

        public static ExecutionResult paused(ParallelPlan plan,
                                             Map<String, ParallelTaskResult> results,
                                             int replanCount,
                                             List<String> unresolvedTasks) {
            return ExecutionResult.builder()
                    .paused(true)
                    .success(false)
                    .failureReason("")
                    .finalAnswer("")
                    .finalPlan(plan)
                    .taskResults(results == null ? Map.of() : results)
                    .replanCount(Math.max(0, replanCount))
                    .unresolvedTasks(unresolvedTasks == null ? List.of() : unresolvedTasks)
                    .build();
        }

        public static ExecutionResult success(ParallelPlan plan,
                                              Map<String, ParallelTaskResult> results,
                                              int replanCount,
                                              String finalAnswer) {
            return ExecutionResult.builder()
                    .paused(false)
                    .success(true)
                    .failureReason("")
                    .finalAnswer(finalAnswer == null ? "" : finalAnswer)
                    .finalPlan(plan)
                    .taskResults(results == null ? Map.of() : results)
                    .replanCount(Math.max(0, replanCount))
                    .unresolvedTasks(List.of())
                    .build();
        }

        public static ExecutionResult failed(String reason,
                                             ParallelPlan plan,
                                             Map<String, ParallelTaskResult> results,
                                             int replanCount,
                                             List<String> unresolvedTasks,
                                             String finalAnswer) {
            return ExecutionResult.builder()
                    .paused(false)
                    .success(false)
                    .failureReason(reason == null ? "" : reason)
                    .finalAnswer(finalAnswer == null ? "" : finalAnswer)
                    .finalPlan(plan)
                    .taskResults(results == null ? Map.of() : results)
                    .replanCount(Math.max(0, replanCount))
                    .unresolvedTasks(unresolvedTasks == null ? List.of() : unresolvedTasks)
                    .build();
        }
    }

    @Data
    @Builder
    private static class PatchPlan {
        private String reason;
        private List<String> replaceTaskIds;
        private List<ParallelPlan.PlanTask> tasks;
    }

    @Data
    @Builder
    private static class PatchApplyResult {
        private boolean applied;
        private String reason;
        private ParallelPlan plan;
        private List<String> replacedTaskIds;

        static PatchApplyResult rejected(String reason) {
            return PatchApplyResult.builder()
                    .applied(false)
                    .reason(reason)
                    .plan(null)
                    .replacedTaskIds(List.of())
                    .build();
        }

        static PatchApplyResult applied(ParallelPlan plan, List<String> replacedTaskIds) {
            return PatchApplyResult.builder()
                    .applied(true)
                    .reason("")
                    .plan(plan)
                    .replacedTaskIds(replacedTaskIds == null ? List.of() : replacedTaskIds)
                    .build();
        }
    }
}
