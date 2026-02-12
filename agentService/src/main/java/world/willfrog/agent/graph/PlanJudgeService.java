package world.willfrog.agent.graph;

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
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.service.AgentAiServiceFactory;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentLlmResolver;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划评分器（Layer1 + Layer3）。
 * <p>
 * Layer1: 结构规则分。<br/>
 * Layer3: 可选 LLM 语义评审分。<br/>
 * Layer2: 历史执行先验预留，不在当前版本落地。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanJudgeService {

    private final AgentAiServiceFactory aiServiceFactory;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties properties;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    private final AgentObservabilityService observabilityService;
    private final AgentPromptService promptService;
    private final ObjectMapper objectMapper;

    public Evaluation evaluate(EvaluationRequest request) {
        ParallelPlanValidator.Result validation = request.getValidation();
        boolean valid = validation != null && validation.isValid();

        double structuralScore = valid ? 1.0D : 0.0D;
        double llmJudgeScore = 0D;
        String judgeSummary = valid ? "validation_passed" : "validation_failed:" + safe(validation == null ? "" : validation.getReason());
        String judgeEndpoint = "";
        String judgeModel = "";

        if (valid) {
            JudgeRuntime runtime = resolveJudgeRuntime();
            if (runtime.enabled() && !runtime.routes().isEmpty()) {
                List<Map<String, String>> failedRoutes = new ArrayList<>();
                for (JudgeRouteCandidate route : runtime.routes()) {
                    try {
                        AgentLlmResolver.ResolvedLlm resolved = aiServiceFactory.resolveLlm(route.endpointName(), route.modelName());
                        ChatLanguageModel judgeModelClient = aiServiceFactory.buildChatModelWithTemperature(resolved, runtime.temperature());
                        String planJson = safeWrite(request.getPlan());
                        String userPrompt = "Goal:\n" + safe(request.getUserGoal()) + "\n\nPlan JSON:\n" + planJson;
                        var messages = java.util.List.of(
                                new SystemMessage(buildJudgeSystemPrompt()),
                                new UserMessage(userPrompt)
                        );
                        long startedAt = System.currentTimeMillis();
                        Response<AiMessage> response = judgeModelClient.generate(messages);
                        long durationMs = System.currentTimeMillis() - startedAt;
                        String raw = response.content() == null ? "" : safe(response.content().text());
                        JudgeLlmResult judgeResult = parseJudgeResult(raw);
                        llmJudgeScore = clamp(judgeResult.getCoverageScore(), 0D, 1D) * 0.6
                                + clamp(judgeResult.getFeasibilityScore(), 0D, 1D) * 0.4
                                - clamp(judgeResult.getRedundancyPenalty(), 0D, 1D) * 0.3;
                        judgeSummary = safe(judgeResult.getSummary());
                        judgeEndpoint = resolved.endpointName();
                        judgeModel = resolved.modelName();

                        Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                                resolved.endpointName(),
                                resolved.baseUrl(),
                                resolved.modelName(),
                                messages,
                                request.getToolSpecifications(),
                                Map.of("stage", "parallel_plan_judge")
                        );
                        observabilityService.recordLlmCall(
                                request.getRunId(),
                                AgentObservabilityService.PHASE_PLANNING,
                                response.tokenUsage(),
                                durationMs,
                                resolved.endpointName(),
                                resolved.modelName(),
                                null,
                                llmRequestSnapshot,
                                raw
                        );
                        failedRoutes.clear();
                        break;
                    } catch (Exception e) {
                        log.warn("Plan judge route failed: runId={}, endpoint={}, model={}",
                                request.getRunId(), route.endpointName(), route.modelName(), e);
                        failedRoutes.add(Map.of(
                                "endpoint", route.endpointName(),
                                "model", route.modelName(),
                                "error", e.getClass().getSimpleName()
                        ));
                    }
                }
                if (judgeEndpoint.isBlank() || judgeModel.isBlank()) {
                    judgeSummary = failedRoutes.isEmpty()
                            ? "judge_disabled_or_unconfigured"
                            : "judge_llm_all_routes_failed:" + safeWrite(failedRoutes);
                    llmJudgeScore = 0D;
                }
            } else {
                judgeSummary = "judge_disabled_or_unconfigured";
            }
        }

        double finalScore = request.getStructuralScoreWeight() * structuralScore
                + request.getLlmJudgeScoreWeight() * llmJudgeScore
                - request.getComplexityPenalty();
        if (!valid) {
            finalScore -= 1000D;
        }

        Map<String, Object> summaryPayload = new LinkedHashMap<>();
        summaryPayload.put("valid", valid);
        summaryPayload.put("validation_reason", validation == null ? "" : safe(validation.getReason()));
        summaryPayload.put("judge_summary", judgeSummary);
        summaryPayload.put("judge_endpoint", judgeEndpoint);
        summaryPayload.put("judge_model", judgeModel);
        summaryPayload.put("layer2_reserved", true);

        return Evaluation.builder()
                .valid(valid)
                .structuralScore(structuralScore)
                .llmJudgeScore(llmJudgeScore)
                .complexityPenalty(request.getComplexityPenalty())
                .finalScore(finalScore)
                .summary(summaryPayload)
                .build();
    }

    private JudgeRuntime resolveJudgeRuntime() {
        AgentLlmProperties.Judge base = properties.getRuntime() == null ? null : properties.getRuntime().getJudge();
        AgentLlmProperties.Judge local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getJudge)
                .orElse(null);

        boolean enabled = firstNonNullBool(local == null ? null : local.getEnabled(), base == null ? null : base.getEnabled(), false);
        double temperature = firstNonNullDouble(local == null ? null : local.getTemperature(), base == null ? null : base.getTemperature(), 0D);
        List<JudgeRouteCandidate> routes = resolveJudgeRoutes(local, base);
        return new JudgeRuntime(enabled, temperature, routes);
    }

    private List<JudgeRouteCandidate> resolveJudgeRoutes(AgentLlmProperties.Judge local, AgentLlmProperties.Judge base) {
        List<AgentLlmProperties.JudgeRoute> rawRoutes = List.of();
        if (local != null && local.getRoutes() != null && !local.getRoutes().isEmpty()) {
            rawRoutes = local.getRoutes();
        } else if (base != null && base.getRoutes() != null && !base.getRoutes().isEmpty()) {
            rawRoutes = base.getRoutes();
        }
        List<JudgeRouteCandidate> candidates = new ArrayList<>();
        for (AgentLlmProperties.JudgeRoute route : rawRoutes) {
            if (route == null) {
                continue;
            }
            String endpointName = safe(route.getEndpointName());
            if (endpointName.isBlank()) {
                continue;
            }
            List<String> models = route.getModels();
            if (models == null || models.isEmpty()) {
                continue;
            }
            for (String model : models) {
                String modelName = safe(model);
                if (modelName.isBlank()) {
                    continue;
                }
                candidates.add(new JudgeRouteCandidate(endpointName, modelName));
            }
        }
        return candidates;
    }

    private String buildJudgeSystemPrompt() {
        String configured = promptService.planJudgeSystemPrompt();
        if (!configured.isBlank()) {
            return configured;
        }
        return """
            You are a strict plan judge for a tool-using AI agent.
            Score the plan for the given goal and output JSON only:
            {"coverage_score":0..1,"feasibility_score":0..1,"redundancy_penalty":0..1,"summary":"..."}
            Rules:
            1) coverage_score: whether plan covers all required sub-goals.
            2) feasibility_score: whether tasks and dependencies are executable and coherent.
            3) redundancy_penalty: penalize unnecessary tasks/branches.
            4) summary: short, concrete reason.
            Do not output markdown.
            """;
    }

    private JudgeLlmResult parseJudgeResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return JudgeLlmResult.empty();
        }
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            return JudgeLlmResult.builder()
                    .coverageScore(node.path("coverage_score").asDouble(0D))
                    .feasibilityScore(node.path("feasibility_score").asDouble(0D))
                    .redundancyPenalty(node.path("redundancy_penalty").asDouble(0D))
                    .summary(node.path("summary").asText(""))
                    .build();
        } catch (Exception e) {
            return JudgeLlmResult.empty();
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean firstNonNullBool(Boolean a, Boolean b, boolean fallback) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return fallback;
    }

    private double firstNonNullDouble(Double a, Double b, double fallback) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return fallback;
    }

    private record JudgeRuntime(boolean enabled, double temperature, List<JudgeRouteCandidate> routes) {
    }

    private record JudgeRouteCandidate(String endpointName, String modelName) {
    }

    @Data
    @Builder
    public static class EvaluationRequest {
        private String runId;
        private String userGoal;
        private ParallelPlan plan;
        private ParallelPlanValidator.Result validation;
        private String defaultEndpointName;
        private String defaultModelName;
        private double complexityPenalty;
        private double structuralScoreWeight;
        private double llmJudgeScoreWeight;
        private List<ToolSpecification> toolSpecifications;
    }

    @Data
    @Builder
    public static class Evaluation {
        private boolean valid;
        private double structuralScore;
        private double llmJudgeScore;
        private double complexityPenalty;
        private double finalScore;
        private Map<String, Object> summary;
    }

    @Data
    @Builder
    private static class JudgeLlmResult {
        private double coverageScore;
        private double feasibilityScore;
        private double redundancyPenalty;
        private String summary;

        public static JudgeLlmResult empty() {
            return JudgeLlmResult.builder()
                    .coverageScore(0D)
                    .feasibilityScore(0D)
                    .redundancyPenalty(0D)
                    .summary("")
                    .build();
        }
    }
}
