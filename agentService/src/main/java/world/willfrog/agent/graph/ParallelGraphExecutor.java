package world.willfrog.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentRunStateStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
@Deprecated
public class ParallelGraphExecutor {

    private final PlanExecutionPlanner planExecutionPlanner;
    private final DagTaskExecutor dagTaskExecutor;
    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final ObjectMapper objectMapper;

    @Value("${agent.flow.parallel.enabled:true}")
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean execute(AgentRun run,
                           String userId,
                           String userGoal,
                           ChatLanguageModel model,
                           List<ToolSpecification> toolSpecifications,
                           String endpointName,
                           String endpointBaseUrl,
                           String modelName) {
        if (!enabled) {
            return false;
        }

        try {
            Set<String> toolWhitelist = toolSpecifications.stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toSet());

            PlanExecutionPlanner.PlanResult planResult = planExecutionPlanner.plan(
                    PlanExecutionPlanner.PlanRequest.builder()
                            .run(run)
                            .userId(userId)
                            .userGoal(userGoal)
                            .model(model)
                            .toolWhitelist(toolWhitelist)
                            .endpointName(endpointName)
                            .endpointBaseUrl(endpointBaseUrl)
                            .modelName(modelName)
                            .build()
            );

            if (!planResult.isPlanValid()) {
                String reason = safe(planResult.getFailureReason());
                markFailed(
                        run,
                        userId,
                        userGoal,
                        planResult.getPlan(),
                        Map.of(),
                        List.of(),
                        planResult.getJudgeSummary(),
                        0,
                        planResult.getPlanScore(),
                        reason.isBlank() ? "plan_invalid" : reason
                );
                emitParallelDecision(run.getId(), userId, false, false, false, true, true, "plan_invalid");
                return true;
            }

            DagTaskExecutor.ExecutionResult executionResult = dagTaskExecutor.execute(
                    DagTaskExecutor.ExecutionRequest.builder()
                            .run(run)
                            .userId(userId)
                            .userGoal(userGoal)
                            .plan(planResult.getPlan())
                            .model(model)
                            .toolWhitelist(toolWhitelist)
                            .endpointName(endpointName)
                            .endpointBaseUrl(endpointBaseUrl)
                            .modelName(modelName)
                            .build()
            );

            if (executionResult.isPaused()) {
                emitParallelDecision(
                        run.getId(),
                        userId,
                        true,
                        false,
                        true,
                        executionResult.getFinalAnswer() == null || executionResult.getFinalAnswer().isBlank(),
                        true,
                        "run_paused"
                );
                return true;
            }

            if (executionResult.isSuccess()) {
                markCompleted(run, userId, userGoal, planResult, executionResult);
                emitParallelDecision(run.getId(), userId, true, true, false, false, true, "completed");
                return true;
            }

            String failureReason = safe(executionResult.getFailureReason());
            markFailed(
                    run,
                    userId,
                    userGoal,
                    executionResult.getFinalPlan(),
                    executionResult.getTaskResults(),
                    executionResult.getUnresolvedTasks(),
                    planResult.getJudgeSummary(),
                    executionResult.getReplanCount(),
                    planResult.getPlanScore(),
                    failureReason
            );
            emitParallelDecision(
                    run.getId(),
                    userId,
                    true,
                    false,
                    false,
                    "final_answer_blank".equals(failureReason),
                    true,
                    "local_replan_exhausted".equals(failureReason) ? "failed_after_replan_exhausted" : safe(failureReason)
            );
            return true;
        } catch (Exception e) {
            try {
                emitParallelDecision(run.getId(), userId, false, false, false, true, false, "exception:" + e.getClass().getSimpleName());
            } catch (Exception eventEx) {
                log.warn("Failed to append PARALLEL_GRAPH_DECISION in exception path: runId={}", run.getId(), eventEx);
            }
            log.warn("Parallel graph execution failed", e);
            return false;
        }
    }

    private void markCompleted(AgentRun run,
                               String userId,
                               String userGoal,
                               PlanExecutionPlanner.PlanResult planResult,
                               DagTaskExecutor.ExecutionResult executionResult) {
        ParallelPlan finalPlan = executionResult.getFinalPlan() == null ? planResult.getPlan() : executionResult.getFinalPlan();
        Map<String, ParallelTaskResult> taskResults = executionResult.getTaskResults() == null ? Map.of() : executionResult.getTaskResults();

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("user_goal", userGoal);
        snapshot.put("plan", safeWrite(finalPlan));
        snapshot.put("task_results", taskResults);
        snapshot.put("answer", safe(executionResult.getFinalAnswer()));
        snapshot.put("quality_report", dagTaskExecutor.buildQualityReport(
                finalPlan,
                taskResults,
                List.of(),
                planResult.getJudgeSummary(),
                executionResult.getReplanCount(),
                planResult.getPlanScore()
        ));

        String snapshotJson = safeWrite(snapshot);
        snapshotJson = observabilityService.attachObservabilityToSnapshot(run.getId(), snapshotJson, AgentRunStatus.COMPLETED);
        runMapper.updateSnapshot(run.getId(), userId, AgentRunStatus.COMPLETED, snapshotJson, true, null);
        eventService.append(run.getId(), userId, "COMPLETED", Map.of("answer", safe(executionResult.getFinalAnswer())));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.COMPLETED.name());
    }

    private void markFailed(AgentRun run,
                            String userId,
                            String userGoal,
                            ParallelPlan plan,
                            Map<String, ParallelTaskResult> taskResults,
                            List<String> unresolvedTasks,
                            Map<String, Object> judgeSummary,
                            int replanCount,
                            Double planScore,
                            String failureReason) {
        ParallelPlan finalPlan = plan == null ? new ParallelPlan() : plan;
        Map<String, ParallelTaskResult> safeTaskResults = taskResults == null ? Map.of() : taskResults;
        List<String> safeUnresolvedTasks = unresolvedTasks == null ? List.of() : unresolvedTasks;

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("user_goal", userGoal);
        snapshot.put("plan", safeWrite(finalPlan));
        snapshot.put("task_results", safeTaskResults);
        snapshot.put("answer", "");
        snapshot.put("quality_report", dagTaskExecutor.buildQualityReport(
                finalPlan,
                safeTaskResults,
                safeUnresolvedTasks,
                judgeSummary,
                replanCount,
                planScore
        ));

        String snapshotJson = safeWrite(snapshot);
        snapshotJson = observabilityService.attachObservabilityToSnapshot(run.getId(), snapshotJson, AgentRunStatus.FAILED);
        runMapper.updateSnapshot(run.getId(), userId, AgentRunStatus.FAILED, snapshotJson, true, safe(failureReason));
        runMapper.updateStatusWithTtl(run.getId(), userId, AgentRunStatus.FAILED, eventService.nextInterruptedExpiresAt());
        eventService.append(run.getId(), userId, "FAILED", Map.of(
                "error", safe(failureReason),
                "unresolved_tasks", safeUnresolvedTasks,
                "replan_count", Math.max(0, replanCount)
        ));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.FAILED.name());
    }

    private void emitParallelDecision(String runId,
                                      String userId,
                                      boolean planValid,
                                      boolean allDone,
                                      boolean paused,
                                      boolean finalAnswerBlank,
                                      boolean handled,
                                      String reason) {
        eventService.append(runId, userId, "PARALLEL_GRAPH_DECISION", Map.of(
                "plan_valid", planValid,
                "all_done", allDone,
                "paused", paused,
                "final_answer_blank", finalAnswerBlank,
                "handled", handled,
                "reason", safe(reason)
        ));
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
}
