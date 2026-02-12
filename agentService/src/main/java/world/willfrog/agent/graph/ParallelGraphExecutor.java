package world.willfrog.agent.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
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
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Component
@RequiredArgsConstructor
@Slf4j
/**
 * 并行图执行器（LangGraph4j）。
 * <p>
 * 核心职责：
 * 1. 候选计划生成与选择；
 * 2. 执行并行任务并支持局部重规划；
 * 3. 在可观测事件中输出“并行是否接管、为何回退”的决策信息；
 * 4. 在满足条件时将 run 标记为 COMPLETED，重规划耗尽时标记 FAILED。
 */
public class ParallelGraphExecutor {

    /** 具体的并行任务调度执行器。 */
    private final ParallelTaskExecutor taskExecutor;
    /** 计划结构与约束校验器。 */
    private final ParallelPlanValidator planValidator = new ParallelPlanValidator();
    /** 计划复杂度评分器。 */
    private final PlanComplexityScorer complexityScorer;
    /** 计划 Judge（Layer1 + Layer3）。 */
    private final PlanJudgeService planJudgeService;
    /** run 主表更新。 */
    private final AgentRunMapper runMapper;
    /** 事件写入服务。 */
    private final AgentEventService eventService;
    /** Redis 状态缓存（计划、任务进度、run 状态）。 */
    private final AgentRunStateStore stateStore;
    /** Prompt 配置服务。 */
    private final AgentPromptService promptService;
    /** 可观测指标聚合服务。 */
    private final AgentObservabilityService observabilityService;
    /** LLM 原始请求快照构建器。 */
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    /** 本地 LLM 热更新配置。 */
    private final AgentLlmLocalConfigLoader localConfigLoader;
    /** 基础 LLM 配置。 */
    private final AgentLlmProperties llmProperties;
    /** JSON 序列化/反序列化。 */
    private final ObjectMapper objectMapper;

    /** 是否启用并行图总开关。 */
    @Value("${agent.flow.parallel.enabled:true}")
    private boolean enabled;

    /** 计划允许的最大任务数。 */
    @Value("${agent.flow.parallel.max-tasks:6}")
    private int maxTasks;

    /** 单个 sub_agent 允许的最大步数。 */
    @Value("${agent.flow.parallel.sub-agent-max-steps:6}")
    private int subAgentMaxSteps;

    /** 允许的“无依赖并行任务”上限，-1 表示不限制。 */
    @Value("${agent.flow.parallel.max-parallel-tasks:-1}")
    private int maxParallelTasks;

    /** 允许的 sub_agent 任务上限，-1 表示不限制。 */
    @Value("${agent.flow.parallel.max-sub-agents:-1}")
    private int maxSubAgents;

    /** 默认候选计划数量。 */
    @Value("${agent.flow.parallel.candidate-plan-count:3}")
    private int defaultCandidatePlanCount;

    /** 默认局部重规划次数上限。 */
    @Value("${agent.flow.parallel.max-local-replans:2}")
    private int defaultMaxLocalReplans;

    /** 默认复杂度惩罚系数。 */
    @Value("${agent.flow.parallel.complexity-penalty-lambda:0.25}")
    private double defaultComplexityPenaltyLambda;

    /** 是否启用并行图执行。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 执行并行图主流程。
     */
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

            var graph = buildGraph(run, userId, userGoal, model, toolWhitelist, endpointName, endpointBaseUrl, modelName);
            Map<String, Object> initial = Map.of("user_goal", userGoal);

            ParallelGraphState finalState = null;
            for (var event : graph.stream(initial)) {
                finalState = event.state();
            }
            if (finalState == null) {
                emitParallelDecision(run.getId(), userId, false, false, false, true, false, "final_state_missing");
                return false;
            }

            boolean planValid = finalState.planValid().orElse(false);
            boolean allDone = finalState.allDone().orElse(false);
            boolean paused = finalState.paused().orElse(false);
            boolean executionFailed = finalState.executionFailed().orElse(false);
            String failureReason = safe(finalState.failureReason().orElse(""));
            String finalAnswer = safe(finalState.finalAnswer().orElse(""));
            int replanCount = finalState.replanCount().orElse(0);
            List<String> unresolvedTasks = finalState.unresolvedTasks().orElse(List.of());
            Map<String, ParallelTaskResult> taskResults = finalState.taskResults().orElse(Map.of());
            Map<String, Object> judgeSummary = finalState.judgeSummary().orElse(Map.of());
            Double planScore = finalState.planScore().orElse(0D);

            if (!planValid) {
                emitParallelDecision(run.getId(), userId, false, allDone, paused, finalAnswer.isBlank(), false, "plan_invalid");
                return false;
            }

            if (executionFailed) {
                Map<String, Object> snapshot = new HashMap<>();
                snapshot.put("user_goal", userGoal);
                snapshot.put("plan", finalState.planJson().orElse("{}"));
                snapshot.put("task_results", taskResults);
                snapshot.put("answer", "");
                snapshot.put("quality_report", buildQualityReport(
                        parsePlan(finalState.planJson().orElse("{}")),
                        taskResults,
                        unresolvedTasks,
                        judgeSummary,
                        replanCount,
                        planScore
                ));
                String snapshotJson = objectMapper.writeValueAsString(snapshot);
                snapshotJson = observabilityService.attachObservabilityToSnapshot(run.getId(), snapshotJson, AgentRunStatus.FAILED);
                runMapper.updateSnapshot(run.getId(), userId, AgentRunStatus.FAILED, snapshotJson, true, failureReason);
                runMapper.updateStatusWithTtl(run.getId(), userId, AgentRunStatus.FAILED, eventService.nextInterruptedExpiresAt());
                eventService.append(run.getId(), userId, "FAILED", Map.of(
                        "error", safe(failureReason),
                        "unresolved_tasks", unresolvedTasks,
                        "replan_count", replanCount
                ));
                stateStore.markRunStatus(run.getId(), AgentRunStatus.FAILED.name());
                emitParallelDecision(run.getId(), userId, true, false, paused, true, true, "failed_after_replan_exhausted");
                return true;
            }

            if (!allDone) {
                boolean handled = paused;
                emitParallelDecision(
                        run.getId(),
                        userId,
                        true,
                        false,
                        paused,
                        finalAnswer.isBlank(),
                        handled,
                        paused ? "run_paused" : "tasks_not_all_done"
                );
                return paused;
            }

            if (finalAnswer.isBlank()) {
                emitParallelDecision(run.getId(), userId, true, true, paused, true, false, "final_answer_blank");
                return false;
            }

            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("user_goal", userGoal);
            snapshot.put("plan", finalState.planJson().orElse("{}"));
            snapshot.put("task_results", taskResults);
            snapshot.put("answer", finalAnswer);
            snapshot.put("quality_report", buildQualityReport(
                    parsePlan(finalState.planJson().orElse("{}")),
                    taskResults,
                    List.of(),
                    judgeSummary,
                    replanCount,
                    planScore
            ));

            String snapshotJson = objectMapper.writeValueAsString(snapshot);
            snapshotJson = observabilityService.attachObservabilityToSnapshot(run.getId(), snapshotJson, AgentRunStatus.COMPLETED);
            runMapper.updateSnapshot(run.getId(), userId, AgentRunStatus.COMPLETED, snapshotJson, true, null);
            eventService.append(run.getId(), userId, "COMPLETED", Map.of("answer", finalAnswer));
            stateStore.markRunStatus(run.getId(), AgentRunStatus.COMPLETED.name());
            emitParallelDecision(run.getId(), userId, true, true, paused, false, true, "completed");
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

    private CompiledGraph<ParallelGraphState> buildGraph(AgentRun run,
                                                         String userId,
                                                         String userGoal,
                                                         ChatLanguageModel model,
                                                         Set<String> toolWhitelist,
                                                         String endpointName,
                                                         String endpointBaseUrl,
                                                         String modelName) {
        try {
            var stateGraph = new StateGraph<>(ParallelGraphState.SCHEMA, ParallelGraphState::new)
                    .addNode("plan", node_async(state -> planNode(
                            run,
                            userId,
                            userGoal,
                            model,
                            toolWhitelist,
                            endpointName,
                            endpointBaseUrl,
                            modelName
                    )))
                    .addNode("execute", node_async(state -> executeNode(
                            run,
                            userId,
                            userGoal,
                            model,
                            toolWhitelist,
                            endpointName,
                            endpointBaseUrl,
                            modelName,
                            state
                    )))
                    .addNode("final", node_async(state -> finalNode(
                            run,
                            userId,
                            userGoal,
                            model,
                            endpointName,
                            endpointBaseUrl,
                            modelName,
                            state
                    )))
                    .addEdge(StateGraph.START, "plan")
                    .addEdge("plan", "execute")
                    .addEdge("execute", "final")
                    .addEdge("final", StateGraph.END);

            return stateGraph.compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to build LangGraph4j flow", e);
        }
    }

    private Map<String, Object> planNode(AgentRun run,
                                         String userId,
                                         String userGoal,
                                         ChatLanguageModel model,
                                         Set<String> toolWhitelist,
                                         String endpointName,
                                         String endpointBaseUrl,
                                         String modelName) {
        eventService.append(run.getId(), userId, "PLAN_STARTED", Map.of("run_id", run.getId()));

        String selectedPlanJson;
        ParallelPlan selectedPlan;
        ParallelPlanValidator.Result selectedValidation;
        double selectedScore = 0D;
        Map<String, Object> selectedJudgeSummary = Map.of();

        boolean usedStoredPlan = false;
        boolean override = stateStore.isPlanOverride(run.getId());
        Optional<String> stored = stateStore.loadPlan(run.getId());
        if (stored.isEmpty() && run.getPlanJson() != null && !run.getPlanJson().isBlank() && !"{}".equals(run.getPlanJson().trim())) {
            stored = Optional.of(run.getPlanJson());
        }

        if (stored.isPresent()) {
            selectedPlanJson = stored.get();
            selectedPlan = parsePlan(selectedPlanJson);
            selectedValidation = planValidator.validate(
                    selectedPlan,
                    toolWhitelist,
                    maxTasks,
                    subAgentMaxSteps,
                    maxParallelTasks,
                    maxSubAgents
            );
            selectedJudgeSummary = Map.of("source", override ? "plan_override" : "stored_plan");
            usedStoredPlan = true;
        } else {
            int candidateCount = resolveCandidatePlanCount(run.getExt());
            double lambda = resolveComplexityPenaltyLambda();
            List<CandidatePlan> candidates = new ArrayList<>();
            for (int i = 1; i <= candidateCount; i++) {
                String plannerPrompt = buildPlannerPrompt(toolWhitelist, maxTasks, subAgentMaxSteps, i, candidateCount);
                List<dev.langchain4j.data.message.ChatMessage> plannerMessages = List.of(
                        new SystemMessage(plannerPrompt),
                        new UserMessage(userGoal)
                );
                long llmStartedAt = System.currentTimeMillis();
                Response<AiMessage> response = model.generate(plannerMessages);
                long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
                String planText = response.content() == null ? "" : safe(response.content().text());
                String planJson = extractJson(planText);

                Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                        endpointName,
                        endpointBaseUrl,
                        modelName,
                        plannerMessages,
                        null,
                        Map.of("stage", "parallel_plan_candidate", "candidate_index", i)
                );
                observabilityService.recordLlmCall(
                        run.getId(),
                        AgentObservabilityService.PHASE_PLANNING,
                        response.tokenUsage(),
                        llmDurationMs,
                        endpointName,
                        modelName,
                        null,
                        llmRequestSnapshot,
                        planText
                );

                eventService.append(run.getId(), userId, "PLAN_CANDIDATE_GENERATED", Map.of(
                        "candidate_index", i,
                        "raw_plan", planText
                ));

                ParallelPlan candidatePlan = parsePlan(planJson);
                ParallelPlanValidator.Result validation = planValidator.validate(
                        candidatePlan,
                        toolWhitelist,
                        maxTasks,
                        subAgentMaxSteps,
                        maxParallelTasks,
                        maxSubAgents
                );
                PlanComplexityScorer.Result complexity = complexityScorer.score(candidatePlan, lambda);
                PlanJudgeService.Evaluation evaluation = planJudgeService.evaluate(
                        PlanJudgeService.EvaluationRequest.builder()
                                .runId(run.getId())
                                .userGoal(userGoal)
                                .plan(candidatePlan)
                                .validation(validation)
                                .defaultEndpointName(endpointName)
                                .defaultModelName(modelName)
                                .complexityPenalty(complexity.getPenalty())
                                .structuralScoreWeight(1D)
                                .llmJudgeScoreWeight(1D)
                                .build()
                );

                eventService.append(run.getId(), userId, "PLAN_CANDIDATE_JUDGED", Map.of(
                        "candidate_index", i,
                        "valid", evaluation.isValid(),
                        "validation_reason", safe(validation.getReason()),
                        "structural_score", evaluation.getStructuralScore(),
                        "llm_judge_score", evaluation.getLlmJudgeScore(),
                        "complexity_penalty", evaluation.getComplexityPenalty(),
                        "complexity", Map.of(
                                "step_count", complexity.getStepCount(),
                                "fanout", complexity.getFanout(),
                                "critical_path_depth", complexity.getCriticalPathDepth(),
                                "lambda", complexity.getLambda(),
                                "raw", complexity.getRawComplexity()
                        ),
                        "final_score", evaluation.getFinalScore(),
                        "summary", evaluation.getSummary()
                ));

                candidates.add(CandidatePlan.builder()
                        .candidateIndex(i)
                        .planText(planText)
                        .plan(candidatePlan)
                        .validation(validation)
                        .evaluation(evaluation)
                        .build());
            }

            CandidatePlan selected = selectCandidate(candidates);
            if (selected == null) {
                selectedPlan = new ParallelPlan();
                selectedValidation = ParallelPlanValidator.Result.builder().valid(false).reason("no_candidate_generated").build();
                selectedPlanJson = safeWrite(selectedPlan);
                selectedScore = -1000D;
                selectedJudgeSummary = Map.of("source", "none");
            } else {
                selectedPlan = selected.getPlan();
                selectedValidation = selected.getValidation();
                selectedPlanJson = safeWrite(selectedPlan);
                selectedScore = selected.getEvaluation() == null ? 0D : selected.getEvaluation().getFinalScore();
                selectedJudgeSummary = selected.getEvaluation() == null ? Map.of() : selected.getEvaluation().getSummary();
                eventService.append(run.getId(), userId, "PLAN_SELECTED", Map.of(
                        "candidate_index", selected.getCandidateIndex(),
                        "valid", selectedValidation.isValid(),
                        "final_score", selectedScore,
                        "summary", selectedJudgeSummary,
                        "plan", selectedPlanJson
                ));
            }
        }

        boolean valid = selectedValidation != null && selectedValidation.isValid();
        runMapper.updatePlan(run.getId(), userId, AgentRunStatus.EXECUTING, selectedPlanJson);
        stateStore.recordPlan(run.getId(), selectedPlanJson, valid);
        stateStore.markRunStatus(run.getId(), AgentRunStatus.EXECUTING.name());
        observabilityService.addNodeCount(run.getId(), selectedPlan.getTasks() == null ? 0 : selectedPlan.getTasks().size());

        if (valid) {
            eventService.append(run.getId(), userId, "PLAN_CREATED", Map.of(
                    "plan", selectedPlanJson,
                    "strategy", safe(selectedPlan.getStrategy())
            ));
        } else {
            eventService.append(run.getId(), userId, "PLAN_INVALID", Map.of(
                    "reason", safe(selectedValidation == null ? "unknown" : selectedValidation.getReason()),
                    "raw_plan", selectedPlanJson
            ));
        }

        if (usedStoredPlan) {
            eventService.append(run.getId(), userId, override ? "PLAN_OVERRIDE_USED" : "PLAN_REUSED", Map.of("plan", selectedPlanJson));
            if (override) {
                stateStore.clearPlanOverride(run.getId());
            }
        }

        Map<String, Object> update = new HashMap<>();
        update.put("plan_json", selectedPlanJson);
        update.put("plan_valid", valid);
        update.put("plan_score", selectedScore);
        update.put("judge_summary", selectedJudgeSummary);
        return update;
    }

    private Map<String, Object> executeNode(AgentRun run,
                                            String userId,
                                            String userGoal,
                                            ChatLanguageModel model,
                                            Set<String> toolWhitelist,
                                            String endpointName,
                                            String endpointBaseUrl,
                                            String modelName,
                                            ParallelGraphState state) {
        boolean planValid = state.planValid().orElse(false);
        if (!planValid) {
            return Map.of(
                    "plan_json", state.planJson().orElse("{}"),
                    "execution_failed", false,
                    "replan_count", 0,
                    "unresolved_tasks", List.of()
            );
        }

        ParallelPlan currentPlan = parsePlan(state.planJson().orElse("{}"));
        Map<String, ParallelTaskResult> results = new HashMap<>(stateStore.loadTaskResults(run.getId()));
        int maxLocalReplans = resolveMaxLocalReplans();
        int replanCount = 0;

        while (true) {
            long startedAt = System.currentTimeMillis();
            results = taskExecutor.execute(
                    currentPlan,
                    run.getId(),
                    userId,
                    toolWhitelist,
                    subAgentMaxSteps,
                    userGoal,
                    model,
                    results,
                    endpointName,
                    endpointBaseUrl,
                    modelName
            );
            observabilityService.recordPhaseDuration(
                    run.getId(),
                    AgentObservabilityService.PHASE_PARALLEL_EXECUTION,
                    System.currentTimeMillis() - startedAt
            );

            boolean paused = !eventService.isRunnable(run.getId(), userId);
            List<String> unresolvedTaskIds = unresolvedTaskIds(currentPlan, results);
            boolean allDone = unresolvedTaskIds.isEmpty();
            if (paused) {
                return Map.of(
                        "plan_json", safeWrite(currentPlan),
                        "task_results", results,
                        "all_done", false,
                        "paused", true,
                        "replan_count", replanCount,
                        "execution_failed", false,
                        "failure_reason", "",
                        "unresolved_tasks", unresolvedTaskIds
                );
            }
            if (allDone) {
                return Map.of(
                        "plan_json", safeWrite(currentPlan),
                        "task_results", results,
                        "all_done", true,
                        "paused", false,
                        "replan_count", replanCount,
                        "execution_failed", false,
                        "failure_reason", "",
                        "unresolved_tasks", List.of()
                );
            }

            if (replanCount >= maxLocalReplans) {
                String reason = "local_replan_exhausted";
                eventService.append(run.getId(), userId, "PLAN_PATCH_EXHAUSTED", Map.of(
                        "replan_count", replanCount,
                        "max_local_replans", maxLocalReplans,
                        "unresolved_tasks", unresolvedTaskIds,
                        "reason", reason
                ));
                return Map.of(
                        "plan_json", safeWrite(currentPlan),
                        "task_results", results,
                        "all_done", false,
                        "paused", false,
                        "replan_count", replanCount,
                        "execution_failed", true,
                        "failure_reason", reason,
                        "unresolved_tasks", unresolvedTaskIds
                );
            }

            int round = replanCount + 1;
            eventService.append(run.getId(), userId, "PLAN_PATCH_REQUESTED", Map.of(
                    "replan_round", round,
                    "unresolved_tasks", unresolvedTaskIds
            ));

            PatchPlan patch = generatePatchPlan(
                    run,
                    userGoal,
                    model,
                    endpointName,
                    endpointBaseUrl,
                    modelName,
                    currentPlan,
                    results,
                    unresolvedTaskIds
            );
            if (patch == null || patch.getTasks().isEmpty()) {
                eventService.append(run.getId(), userId, "PLAN_PATCH_REJECTED", Map.of(
                        "replan_round", round,
                        "reason", "empty_patch_tasks"
                ));
                replanCount = round;
                continue;
            }

            eventService.append(run.getId(), userId, "PLAN_PATCH_CREATED", Map.of(
                    "replan_round", round,
                    "reason", safe(patch.getReason()),
                    "replace_task_ids", patch.getReplaceTaskIds(),
                    "tasks_count", patch.getTasks().size(),
                    "tasks", safeWrite(patch.getTasks())
            ));

            PatchApplyResult patchApply = applyPatchPlan(currentPlan, patch, unresolvedTaskIds, toolWhitelist);
            if (!patchApply.isApplied()) {
                eventService.append(run.getId(), userId, "PLAN_PATCH_REJECTED", Map.of(
                        "replan_round", round,
                        "reason", safe(patchApply.getReason())
                ));
                replanCount = round;
                continue;
            }

            currentPlan = patchApply.getPlan();
            ParallelPlan appliedPlan = currentPlan;
            String patchedPlanJson = safeWrite(currentPlan);
            runMapper.updatePlan(run.getId(), userId, AgentRunStatus.EXECUTING, patchedPlanJson);
            stateStore.recordPlan(run.getId(), patchedPlanJson, true);

            Set<String> replacedIds = new HashSet<>(patchApply.getReplacedTaskIds());
            results.keySet().removeIf(replacedIds::contains);
            results.keySet().removeIf(taskId -> !containsTaskId(appliedPlan, taskId));

            eventService.append(run.getId(), userId, "PLAN_PATCH_APPLIED", Map.of(
                    "replan_round", round,
                    "replace_task_ids", patchApply.getReplacedTaskIds(),
                    "plan", patchedPlanJson
            ));
            replanCount = round;
        }
    }

    private Map<String, Object> finalNode(AgentRun run,
                                          String userId,
                                          String userGoal,
                                          ChatLanguageModel model,
                                          String endpointName,
                                          String endpointBaseUrl,
                                          String modelName,
                                          ParallelGraphState state) {
        boolean planValid = state.planValid().orElse(false);
        boolean allDone = state.allDone().orElse(false);
        boolean executionFailed = state.executionFailed().orElse(false);
        if (!planValid || !allDone || executionFailed) {
            return Map.of("final_answer", "");
        }
        Map<String, ParallelTaskResult> results = state.taskResults().orElse(Map.of());
        String resultJson = safeWrite(results);
        String prompt = promptService.parallelFinalSystemPrompt();
        List<dev.langchain4j.data.message.ChatMessage> finalMessages = List.of(
                new SystemMessage(prompt),
                new UserMessage("目标: " + userGoal + "\n结果: " + resultJson)
        );
        long llmStartedAt = System.currentTimeMillis();
        Response<AiMessage> response = model.generate(finalMessages);
        long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
        String answer = response.content() == null ? "" : safe(response.content().text());
        Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                endpointName,
                endpointBaseUrl,
                modelName,
                finalMessages,
                null,
                Map.of("stage", "parallel_final")
        );
        observabilityService.recordLlmCall(
                run.getId(),
                AgentObservabilityService.PHASE_SUMMARIZING,
                response.tokenUsage(),
                llmDurationMs,
                endpointName,
                modelName,
                null,
                llmRequestSnapshot,
                answer
        );
        return Map.of("final_answer", answer);
    }

    private CandidatePlan selectCandidate(List<CandidatePlan> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        CandidatePlan bestValid = null;
        for (CandidatePlan candidate : candidates) {
            if (candidate == null || candidate.getEvaluation() == null || candidate.getValidation() == null) {
                continue;
            }
            if (!candidate.getValidation().isValid()) {
                continue;
            }
            if (bestValid == null || candidate.getEvaluation().getFinalScore() > bestValid.getEvaluation().getFinalScore()) {
                bestValid = candidate;
            }
        }
        if (bestValid != null) {
            return bestValid;
        }
        CandidatePlan best = null;
        for (CandidatePlan candidate : candidates) {
            if (candidate == null || candidate.getEvaluation() == null) {
                continue;
            }
            if (best == null || candidate.getEvaluation().getFinalScore() > best.getEvaluation().getFinalScore()) {
                best = candidate;
            }
        }
        return best == null ? candidates.get(0) : best;
    }

    private PatchPlan generatePatchPlan(AgentRun run,
                                        String userGoal,
                                        ChatLanguageModel model,
                                        String endpointName,
                                        String endpointBaseUrl,
                                        String modelName,
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
            String userPrompt = "Goal:\n" + safe(userGoal)
                    + "\n\nCurrent plan:\n" + safeWrite(currentPlan)
                    + "\n\nUnresolved task ids:\n" + safeWrite(unresolvedTaskIds)
                    + "\n\nCurrent results:\n" + safeWrite(results);
            List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                    new SystemMessage(prompt),
                    new UserMessage(userPrompt)
            );
            long llmStartedAt = System.currentTimeMillis();
            Response<AiMessage> response = model.generate(messages);
            long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
            String raw = response.content() == null ? "" : safe(response.content().text());
            Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                    endpointName,
                    endpointBaseUrl,
                    modelName,
                    messages,
                    null,
                    Map.of("stage", "parallel_patch_plan")
            );
            observabilityService.recordLlmCall(
                    run.getId(),
                    AgentObservabilityService.PHASE_PLANNING,
                    response.tokenUsage(),
                    llmDurationMs,
                    endpointName,
                    modelName,
                    null,
                    llmRequestSnapshot,
                    raw
            );
            return parsePatchPlan(raw);
        } catch (Exception e) {
            log.warn("Generate patch plan failed: runId={}", run.getId(), e);
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

    private Map<String, Object> buildQualityReport(ParallelPlan plan,
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

    private ParallelPlan parsePlan(String json) {
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

    private String buildPlannerPrompt(Set<String> toolWhitelist, int maxTasks, int maxSubSteps, int candidateIndex, int candidateCount) {
        String toolList = toolWhitelist == null
                ? ""
                : toolWhitelist.stream().sorted().collect(Collectors.joining(", "));
        return promptService.parallelPlannerSystemPrompt(
                toolList,
                maxTasks,
                maxSubSteps,
                maxParallelTasks,
                maxSubAgents,
                candidateIndex,
                candidateCount
        );
    }

    private int resolveCandidatePlanCount(String runExtJson) {
        int adminOverride = eventService.extractPlannerCandidateCount(runExtJson);
        if (adminOverride > 0) {
            return clampInt(adminOverride, 1, 5);
        }
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getCandidatePlanCount)
                .orElse(0);
        if (local > 0) {
            return clampInt(local, 1, 5);
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getCandidatePlanCount)
                .orElse(0);
        if (base > 0) {
            return clampInt(base, 1, 5);
        }
        return clampInt(defaultCandidatePlanCount, 1, 5);
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

    private double resolveComplexityPenaltyLambda() {
        double local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getComplexityPenaltyLambda)
                .orElse(0D);
        if (local > 0D) {
            return local;
        }
        double base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getComplexityPenaltyLambda)
                .orElse(0D);
        if (base > 0D) {
            return base;
        }
        return defaultComplexityPenaltyLambda > 0D ? defaultComplexityPenaltyLambda : 0D;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @Data
    @Builder
    private static class CandidatePlan {
        private int candidateIndex;
        private String planText;
        private ParallelPlan plan;
        private ParallelPlanValidator.Result validation;
        private PlanJudgeService.Evaluation evaluation;
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
