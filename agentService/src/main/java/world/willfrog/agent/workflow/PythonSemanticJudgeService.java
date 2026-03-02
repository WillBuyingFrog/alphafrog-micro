package world.willfrog.agent.workflow;

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
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentAiServiceFactory;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.JudgeModelSelectorService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class PythonSemanticJudgeService {

    private static final List<String> CATEGORY_ENUM = List.of(
            "OK",
            "NUMERIC_ANOMALY",
            "TIME_RANGE_MISMATCH",
            "DOMAIN_INVARIANT_VIOLATION",
            "INSUFFICIENT_EVIDENCE"
    );
    private static final List<String> SEVERITY_ENUM = List.of("LOW", "MEDIUM", "HIGH");

    private final ObjectMapper objectMapper;
    private final AgentPromptService promptService;
    private final AgentAiServiceFactory aiServiceFactory;
    private final JudgeModelSelectorService judgeModelSelectorService;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentLlmProperties llmProperties;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    private final AgentObservabilityService observabilityService;

    public Result judge(Request request) {
        Config config = resolveConfig();
        if (!config.semanticEnabled()) {
            return Result.pass("OK", "semantic judge disabled", Map.of(
                    "enabled", false,
                    "reason", "semantic_enabled_false"
            ));
        }

        SelectionAndModel selected = selectModel(request, config);
        if (selected == null) {
            if (config.failOpen()) {
                return Result.pass("INSUFFICIENT_EVIDENCE", "未找到可用 judge 模型，按 failOpen 放行", Map.of(
                        "judge_model_selected", false,
                        "fail_open", true
                ));
            }
            return Result.reject("INSUFFICIENT_EVIDENCE", "HIGH", "未找到可用 judge 模型", Map.of(
                    "judge_model_selected", false,
                    "fail_open", false
            ));
        }

        List<Map<String, Object>> failures = new ArrayList<>();
        int maxAttempts = Math.max(1, config.maxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> payload = buildPayload(request);
                payload.put("judge_attempt", attempt);
                List<ChatMessage> messages = List.of(
                        new SystemMessage(promptService.semanticJudgeSystemPrompt()),
                        new UserMessage(writeJson(payload))
                );
                String previousStage = AgentContext.getStage();
                AgentContext.setPhase(AgentObservabilityService.PHASE_SUMMARIZING);
                AgentContext.setStage("workflow_semantic_judge");
                long llmStartedAt = System.currentTimeMillis();
                Response<AiMessage> response;
                try {
                    response = selected.model.generate(messages);
                } finally {
                    if (previousStage == null || previousStage.isBlank()) {
                        AgentContext.clearStage();
                    } else {
                        AgentContext.setStage(previousStage);
                    }
                }
                long llmCompletedAt = System.currentTimeMillis();
                String text = response.content() == null ? "" : nvl(response.content().text());

                Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                        selected.selection.getEndpointName(),
                        selected.selection.getEndpointBaseUrl(),
                        selected.selection.getModelName(),
                        messages,
                        null,
                        Map.of("stage", "workflow_semantic_judge", "attempt", attempt)
                );
                observabilityService.recordLlmCall(
                        request.getRunId(),
                        AgentObservabilityService.PHASE_SUMMARIZING,
                        response.tokenUsage(),
                        llmCompletedAt - llmStartedAt,
                        llmStartedAt,
                        llmCompletedAt,
                        selected.selection.getEndpointName(),
                        selected.selection.getModelName(),
                        null,
                        llmRequestSnapshot,
                        text
                );

                JsonNode parsed = parseJson(text);
                Validation validation = validate(parsed);
                if (!validation.valid()) {
                    failures.add(Map.of(
                            "attempt", attempt,
                            "reason", validation.message(),
                            "raw_preview", preview(text, 800)
                    ));
                    continue;
                }

                boolean pass = parsed.path("pass").asBoolean(false);
                String category = parsed.path("category").asText("INSUFFICIENT_EVIDENCE").trim();
                String severity = parsed.path("severity").asText("MEDIUM").trim();
                String reasonCn = parsed.path("reason_cn").asText("").trim();
                String fixHint = parsed.path("fix_hint").asText("").trim();

                Map<String, Object> report = new LinkedHashMap<>();
                report.put("attempt", attempt);
                report.put("max_attempts", maxAttempts);
                report.put("judge_model", selected.selection.getModelName());
                report.put("judge_endpoint", selected.selection.getEndpointName());
                report.put("reason_cn", reasonCn);
                report.put("fix_hint", fixHint);
                report.put("raw", objectMapper.convertValue(parsed, Map.class));
                report.put("failures", failures);

                if (!pass && "INSUFFICIENT_EVIDENCE".equals(category) && !config.blockOnInsufficientEvidence()) {
                    report.put("insufficient_evidence_blocked", false);
                    return Result.pass(category, reasonCn.isBlank() ? "INSUFFICIENT_EVIDENCE but configured to pass" : reasonCn, report);
                }
                if (pass) {
                    return Result.pass(category, reasonCn, report);
                }
                return Result.reject(category, severity, reasonCn, report);
            } catch (Exception e) {
                failures.add(Map.of(
                        "attempt", attempt,
                        "reason", nvl(e.getMessage())
                ));
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("max_attempts", maxAttempts);
        report.put("judge_model", selected.selection.getModelName());
        report.put("judge_endpoint", selected.selection.getEndpointName());
        report.put("failures", failures);
        if (config.failOpen()) {
            return Result.pass("INSUFFICIENT_EVIDENCE", "judge 解析失败，按 failOpen 放行", report);
        }
        return Result.reject("INSUFFICIENT_EVIDENCE", "HIGH", "judge 解析失败", report);
    }

    private SelectionAndModel selectModel(Request request, Config config) {
        List<JudgeModelSelectorService.Selection> candidates = judgeModelSelectorService.selectCandidates();
        if (!candidates.isEmpty()) {
            for (JudgeModelSelectorService.Selection candidate : candidates) {
                try {
                    var resolved = aiServiceFactory.resolveLlm(candidate.getEndpointName(), candidate.getModelName());
                    ChatLanguageModel model = aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                            resolved,
                            List.of(),
                            config.temperature()
                    );
                    return new SelectionAndModel(candidate, model);
                } catch (Exception e) {
                    log.warn("Init semantic judge model failed: endpoint={}, model={}, err={}",
                            candidate.getEndpointName(), candidate.getModelName(), e.getMessage());
                }
            }
        }
        if (request.getFallbackModel() != null) {
            return new SelectionAndModel(
                    JudgeModelSelectorService.Selection.builder()
                            .endpointName(request.getFallbackEndpointName())
                            .endpointBaseUrl(request.getFallbackEndpointBaseUrl())
                            .modelName(request.getFallbackModelName())
                            .build(),
                    request.getFallbackModel()
            );
        }
        return null;
    }

    private Map<String, Object> buildPayload(Request request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_goal", nvl(request.getUserGoal()));
        payload.put("todo", Map.of(
                "id", nvl(request.getTodoId()),
                "tool", nvl(request.getToolName()),
                "reasoning", nvl(request.getTodoReasoning())
        ));
        payload.put("run_args", request.getRunArgs() == null ? Map.of() : request.getRunArgs());
        payload.put("code", nvl(request.getCode()));
        payload.put("tool_output", request.getToolOutput() == null ? Map.of() : request.getToolOutput());
        return payload;
    }

    private Validation validate(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Validation.ofInvalid("judge_output_not_object");
        }
        if (!root.has("pass") || !root.get("pass").isBoolean()) {
            return Validation.ofInvalid("judge_output_missing_pass");
        }
        String category = root.path("category").asText("").trim();
        if (!CATEGORY_ENUM.contains(category)) {
            return Validation.ofInvalid("judge_output_invalid_category");
        }
        String severity = root.path("severity").asText("").trim();
        if (!SEVERITY_ENUM.contains(severity)) {
            return Validation.ofInvalid("judge_output_invalid_severity");
        }
        if (!root.has("reason_cn") || !root.get("reason_cn").isTextual()) {
            return Validation.ofInvalid("judge_output_missing_reason_cn");
        }
        if (!root.has("fix_hint") || !root.get("fix_hint").isTextual()) {
            return Validation.ofInvalid("judge_output_missing_fix_hint");
        }
        return Validation.ofValid();
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        String json = (start >= 0 && end > start) ? trimmed.substring(start, end + 1) : trimmed;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Config resolveConfig() {
        AgentLlmProperties.Runtime runtime = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .orElse(Optional.ofNullable(llmProperties.getRuntime()).orElse(new AgentLlmProperties.Runtime()));
        AgentLlmProperties.Judge judge = runtime == null ? null : runtime.getJudge();
        boolean semanticEnabled = judge != null && judge.getSemanticEnabled() != null && judge.getSemanticEnabled();
        int maxAttempts = judge != null && judge.getMaxAttempts() != null && judge.getMaxAttempts() > 0
                ? judge.getMaxAttempts()
                : 2;
        boolean failOpen = judge == null || judge.getFailOpen() == null || judge.getFailOpen();
        boolean blockOnInsufficientEvidence = judge != null
                && judge.getBlockOnInsufficientEvidence() != null
                && judge.getBlockOnInsufficientEvidence();
        Double temperature = judge != null && judge.getTemperature() != null
                ? judge.getTemperature()
                : 0.0D;
        return new Config(semanticEnabled, maxAttempts, failOpen, blockOnInsufficientEvidence, temperature);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    @Data
    @Builder
    public static class Request {
        private String runId;
        private String userGoal;
        private String todoId;
        private String toolName;
        private String todoReasoning;
        private Map<String, Object> runArgs;
        private String code;
        private Map<String, Object> toolOutput;
        private ChatLanguageModel fallbackModel;
        private String fallbackEndpointName;
        private String fallbackEndpointBaseUrl;
        private String fallbackModelName;
    }

    @Data
    @Builder
    public static class Result {
        private boolean pass;
        private String category;
        private String severity;
        private String reasonCn;
        private Map<String, Object> report;

        public static Result pass(String category, String reasonCn, Map<String, Object> report) {
            return Result.builder()
                    .pass(true)
                    .category(category)
                    .severity("LOW")
                    .reasonCn(reasonCn == null ? "" : reasonCn)
                    .report(report == null ? Map.of() : report)
                    .build();
        }

        public static Result reject(String category, String severity, String reasonCn, Map<String, Object> report) {
            return Result.builder()
                    .pass(false)
                    .category(category)
                    .severity(severity)
                    .reasonCn(reasonCn == null ? "" : reasonCn)
                    .report(report == null ? Map.of() : report)
                    .build();
        }
    }

    private record Config(boolean semanticEnabled,
                          int maxAttempts,
                          boolean failOpen,
                          boolean blockOnInsufficientEvidence,
                          Double temperature) {
    }

    private record SelectionAndModel(JudgeModelSelectorService.Selection selection,
                                     ChatLanguageModel model) {
    }

    private record Validation(boolean valid, String message) {
        static Validation ofValid() {
            return new Validation(true, "");
        }

        static Validation ofInvalid(String message) {
            return new Validation(false, message == null ? "" : message);
        }
    }
}
