package world.willfrog.agent.graph;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlanExecutionPlanner {

    private final ParallelPlanValidator planValidator = new ParallelPlanValidator();
    private final PlanComplexityScorer complexityScorer;
    private final PlanJudgeService planJudgeService;
    private final DagAnalyzer dagAnalyzer;
    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final AgentPromptService promptService;
    private final AgentObservabilityService observabilityService;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
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

    @Value("${agent.flow.parallel.candidate-plan-count:3}")
    private int defaultCandidatePlanCount;

    @Value("${agent.flow.parallel.complexity-penalty-lambda:0.25}")
    private double defaultComplexityPenaltyLambda;

    public PlanResult plan(PlanRequest request) {
        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();
        String userGoal = request.getUserGoal();

        eventService.append(runId, userId, "PLAN_STARTED", Map.of("run_id", runId));

        String selectedPlanJson;
        ParallelPlan selectedPlan;
        ParallelPlanValidator.Result selectedValidation;
        double selectedScore = 0D;
        Map<String, Object> selectedJudgeSummary = Map.of();
        boolean usedStoredPlan = false;

        boolean override = stateStore.isPlanOverride(runId);
        Optional<String> stored = stateStore.loadPlan(runId);
        if (stored.isEmpty() && run.getPlanJson() != null && !run.getPlanJson().isBlank() && !"{}".equals(run.getPlanJson().trim())) {
            stored = Optional.of(run.getPlanJson());
        }

        if (stored.isPresent()) {
            selectedPlanJson = stored.get();
            selectedPlan = parsePlan(selectedPlanJson);
            selectedValidation = planValidator.validate(
                    selectedPlan,
                    request.getToolWhitelist(),
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
                String plannerPrompt = buildPlannerPrompt(request.getToolWhitelist(), maxTasks, subAgentMaxSteps, i, candidateCount);
                List<ChatMessage> plannerMessages = List.of(
                        new SystemMessage(plannerPrompt),
                        new UserMessage(userGoal)
                );
                long llmStartedAt = System.currentTimeMillis();
                Response<AiMessage> response = request.getModel().generate(plannerMessages);
                long llmDurationMs = System.currentTimeMillis() - llmStartedAt;
                String planText = response.content() == null ? "" : safe(response.content().text());
                String planJson = extractJson(planText);

                Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                        request.getEndpointName(),
                        request.getEndpointBaseUrl(),
                        request.getModelName(),
                        plannerMessages,
                        null,
                        Map.of("stage", "parallel_plan_candidate", "candidate_index", i)
                );
                observabilityService.recordLlmCall(
                        runId,
                        AgentObservabilityService.PHASE_PLANNING,
                        response.tokenUsage(),
                        llmDurationMs,
                        request.getEndpointName(),
                        request.getModelName(),
                        null,
                        llmRequestSnapshot,
                        planText
                );

                eventService.append(runId, userId, "PLAN_CANDIDATE_GENERATED", Map.of(
                        "candidate_index", i,
                        "raw_plan", planText
                ));

                ParallelPlan candidatePlan = parsePlan(planJson);
                ParallelPlanValidator.Result validation = planValidator.validate(
                        candidatePlan,
                        request.getToolWhitelist(),
                        maxTasks,
                        subAgentMaxSteps,
                        maxParallelTasks,
                        maxSubAgents
                );
                PlanComplexityScorer.Result complexity = complexityScorer.score(candidatePlan, lambda);
                PlanJudgeService.Evaluation evaluation = planJudgeService.evaluate(
                        PlanJudgeService.EvaluationRequest.builder()
                                .runId(runId)
                                .userGoal(userGoal)
                                .plan(candidatePlan)
                                .validation(validation)
                                .defaultEndpointName(request.getEndpointName())
                                .defaultModelName(request.getModelName())
                                .complexityPenalty(complexity.getPenalty())
                                .structuralScoreWeight(1D)
                                .llmJudgeScoreWeight(1D)
                                .build()
                );

                eventService.append(runId, userId, "PLAN_CANDIDATE_JUDGED", Map.of(
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
                eventService.append(runId, userId, "PLAN_SELECTED", Map.of(
                        "candidate_index", selected.getCandidateIndex(),
                        "valid", selectedValidation.isValid(),
                        "final_score", selectedScore,
                        "summary", selectedJudgeSummary,
                        "plan", selectedPlanJson
                ));
            }
        }

        if (selectedPlan == null) {
            selectedPlan = new ParallelPlan();
        }
        if (selectedValidation == null) {
            selectedValidation = ParallelPlanValidator.Result.builder().valid(false).reason("validation_missing").build();
        }

        boolean valid = selectedValidation.isValid();
        runMapper.updatePlan(runId, userId, AgentRunStatus.EXECUTING, selectedPlanJson);
        stateStore.recordPlan(runId, selectedPlanJson, valid);
        stateStore.markRunStatus(runId, AgentRunStatus.EXECUTING.name());
        observabilityService.addNodeCount(runId, selectedPlan.getTasks() == null ? 0 : selectedPlan.getTasks().size());

        if (valid) {
            eventService.append(runId, userId, "PLAN_CREATED", Map.of(
                    "plan", selectedPlanJson,
                    "strategy", safe(selectedPlan.getStrategy())
            ));
        } else {
            eventService.append(runId, userId, "PLAN_INVALID", Map.of(
                    "reason", safe(selectedValidation.getReason()),
                    "raw_plan", selectedPlanJson
            ));
        }

        if (usedStoredPlan) {
            eventService.append(runId, userId, override ? "PLAN_OVERRIDE_USED" : "PLAN_REUSED", Map.of("plan", selectedPlanJson));
            if (override) {
                stateStore.clearPlanOverride(runId);
            }
        }

        DagAnalyzer.DagMetrics metrics = dagAnalyzer.analyze(selectedPlan);
        eventService.append(runId, userId, "PLAN_ANALYZED", Map.of(
                "task_count", metrics.getTaskCount(),
                "max_parallelism", metrics.getMaxParallelism(),
                "critical_path_length", metrics.getCriticalPathLength(),
                "has_cycle", metrics.isHasCycle(),
                "plan_valid", valid
        ));

        return PlanResult.builder()
                .plan(selectedPlan)
                .planJson(selectedPlanJson)
                .planValid(valid)
                .planScore(selectedScore)
                .judgeSummary(selectedJudgeSummary == null ? Map.of() : selectedJudgeSummary)
                .failureReason(valid ? "" : safe(selectedValidation.getReason()))
                .build();
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

    private ParallelPlan parsePlan(String json) {
        try {
            return objectMapper.readValue(json, ParallelPlan.class);
        } catch (Exception e) {
            return new ParallelPlan();
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
    private static class CandidatePlan {
        private int candidateIndex;
        private String planText;
        private ParallelPlan plan;
        private ParallelPlanValidator.Result validation;
        private PlanJudgeService.Evaluation evaluation;
    }

    @Data
    @Builder
    public static class PlanRequest {
        private AgentRun run;
        private String userId;
        private String userGoal;
        private ChatLanguageModel model;
        private Set<String> toolWhitelist;
        private String endpointName;
        private String endpointBaseUrl;
        private String modelName;
    }

    @Data
    @Builder
    public static class PlanResult {
        private ParallelPlan plan;
        private String planJson;
        private boolean planValid;
        private double planScore;
        private Map<String, Object> judgeSummary;
        private String failureReason;
    }
}
