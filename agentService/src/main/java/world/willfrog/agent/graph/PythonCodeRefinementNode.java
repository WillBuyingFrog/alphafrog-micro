package world.willfrog.agent.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.config.CodeRefineProperties;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.CodeRefineLocalConfigLoader;
import world.willfrog.agent.tool.ToolRouter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class PythonCodeRefinementNode {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_TIMEOUT_SECONDS = 90;
    private static final int MIN_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final Pattern PYTHON_BLOCK_PATTERN = Pattern.compile("```(?:python)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATASET_ID_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper;
    private final CodeRefineProperties codeRefineProperties;
    private final CodeRefineLocalConfigLoader localConfigLoader;
    private final AgentPromptService promptService;
    private final AgentObservabilityService observabilityService;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;

    @Data
    @Builder
    public static class Request {
        private String goal;
        private String context;
        private String codingContext;
        private String initialCode;
        private Map<String, Object> initialRunArgs;
        private String datasetId;
        private String datasetIds;
        private String libraries;
        private Integer timeoutSeconds;
        private String endpointName;
        private String endpointBaseUrl;
        private String modelName;
        private String decisionLlmTraceId;
        private String decisionStage;
        private String decisionExcerpt;
    }

    @Data
    @Builder
    public static class AttemptTrace {
        private int attempt;
        private String code;
        private Map<String, Object> runArgs;
        private Map<String, Object> llmSnapshot;
        private String output;
        private boolean success;
    }

    @Data
    @Builder
    public static class Result {
        private boolean success;
        private int attemptsUsed;
        private String output;
        private List<AttemptTrace> traces;
    }

    @Data
    @Builder
    private static class GeneratedPlan {
        private String code;
        private Map<String, Object> runArgs;
        private Map<String, Object> llmSnapshot;
        private String decisionLlmTraceId;
        private String decisionStage;
        private String decisionExcerpt;
    }

    public Result execute(Request request, ChatLanguageModel model) {
        int maxAttempts = resolveMaxAttempts();
        List<AttemptTrace> traces = new ArrayList<>();
        String currentCode = safe(request.getInitialCode());
        Map<String, Object> currentRunArgs = buildInitialRunArgs(request);
        String currentDecisionTraceId = safe(request.getDecisionLlmTraceId());
        String currentDecisionStage = safe(request.getDecisionStage());
        String currentDecisionExcerpt = safe(request.getDecisionExcerpt());
        String previousStage = AgentContext.getStage();
        Integer previousAttempt = AgentContext.getPythonRefineAttempt();
        String previousDecisionTraceId = AgentContext.getDecisionTraceId();
        String previousDecisionStage = AgentContext.getDecisionStage();
        String previousDecisionExcerpt = AgentContext.getDecisionExcerpt();

        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                Map<String, Object> llmSnapshot = null;
                if (currentCode.isBlank()) {
                    GeneratedPlan generated = generatePythonPlan(request, traces, currentRunArgs, model);
                    currentCode = safe(generated.getCode());
                    currentRunArgs = mergeRunArgs(currentRunArgs, generated.getRunArgs());
                    llmSnapshot = copySnapshot(generated.getLlmSnapshot());
                    currentDecisionTraceId = safe(generated.getDecisionLlmTraceId());
                    currentDecisionStage = safe(generated.getDecisionStage());
                    currentDecisionExcerpt = safe(generated.getDecisionExcerpt());
                    if (currentCode.isBlank()) {
                        String output = "Python code generation failed: empty code";
                        traces.add(AttemptTrace.builder()
                                .attempt(attempt)
                                .code("")
                                .runArgs(copyForTrace(currentRunArgs))
                                .llmSnapshot(copySnapshot(llmSnapshot))
                                .output(output)
                                .success(false)
                                .build());
                        continue;
                    }
                }

                AgentContext.setStage("python_refine_execute");
                AgentContext.setPythonRefineAttempt(attempt);
                AgentContext.setDecisionContext(currentDecisionTraceId, currentDecisionStage, currentDecisionExcerpt);
                String output;
                try {
                    output = toolRouter.invoke("executePython", buildExecuteArgs(currentRunArgs, currentCode));
                } finally {
                    AgentContext.clearPythonRefineAttempt();
                }
                boolean success = isExecutionSuccess(output);
                traces.add(AttemptTrace.builder()
                        .attempt(attempt)
                        .code(currentCode)
                        .runArgs(copyForTrace(currentRunArgs))
                        .llmSnapshot(copySnapshot(llmSnapshot))
                        .output(output)
                        .success(success)
                        .build());
                if (success) {
                    return Result.builder()
                            .success(true)
                            .attemptsUsed(attempt)
                            .output(output)
                            .traces(traces)
                            .build();
                }
                currentCode = "";
            }

            String lastError = traces.isEmpty() ? "" : preview(traces.get(traces.size() - 1).getOutput(), 500);
            return Result.builder()
                    .success(false)
                    .attemptsUsed(traces.size())
                    .output("Python execution failed after " + maxAttempts + " attempts. last_error=" + lastError)
                    .traces(traces)
                    .build();
        } finally {
            if (previousStage == null || previousStage.isBlank()) {
                AgentContext.clearStage();
            } else {
                AgentContext.setStage(previousStage);
            }
            AgentContext.setPythonRefineAttempt(previousAttempt);
            if (previousDecisionTraceId == null || previousDecisionTraceId.isBlank()) {
                AgentContext.clearDecisionContext();
            } else {
                AgentContext.setDecisionContext(previousDecisionTraceId, previousDecisionStage, previousDecisionExcerpt);
            }
        }
    }

    private Map<String, Object> buildExecuteArgs(Map<String, Object> runArgs, String code) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.putAll(runArgs == null ? Map.of() : runArgs);
        args.put("code", code);
        return args;
    }

    private GeneratedPlan generatePythonPlan(Request request,
                                             List<AttemptTrace> traces,
                                             Map<String, Object> currentRunArgs,
                                             ChatLanguageModel model) {
        String systemPrompt = promptService.pythonRefineSystemPrompt();
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("任务目标:\n").append(safe(request.getGoal())).append("\n");
        userPrompt.append("任务上下文:\n").append(safe(request.getContext())).append("\n");
        userPrompt.append("编码相关上下文(由上游模型筛选):\n").append(safe(request.getCodingContext())).append("\n");
        userPrompt.append("当前运行参数:\n").append(writeJson(currentRunArgs)).append("\n");
        userPrompt.append("要求:\n");
        List<String> requirements = promptService.pythonRefineRequirements();
        for (String requirement : requirements) {
            if (requirement != null && !requirement.isBlank()) {
                userPrompt.append("- ").append(requirement).append("\n");
            }
        }
        String fieldGuide = promptService.pythonRefineDatasetFieldGuide();
        if (!fieldGuide.isBlank()) {
            userPrompt.append("可用数据字段说明:\n").append(fieldGuide).append("\n");
        }

        if (!traces.isEmpty()) {
            userPrompt.append("失败历史（按倒序，最近一次在前，包含全部失败尝试）:\n");
            for (int i = traces.size() - 1; i >= 0; i--) {
                AttemptTrace trace = traces.get(i);
                if (trace == null) {
                    continue;
                }
                userPrompt.append("第").append(trace.getAttempt()).append("次:\n");
                userPrompt.append("运行参数:\n").append(writeJson(trace.getRunArgs())).append("\n");
                String code = safe(trace.getCode());
                if (code.isBlank()) {
                    userPrompt.append("失败代码: <empty>\n");
                } else {
                    userPrompt.append("失败代码:\n```python\n")
                            .append(code)
                            .append("\n```\n");
                }
                String output = safe(trace.getOutput());
                if (output.isBlank()) {
                    userPrompt.append("执行反馈: <empty>\n");
                } else {
                    userPrompt.append("执行反馈:\n")
                            .append(preview(output, 5000))
                            .append("\n");
                }
            }
        }

        userPrompt.append(promptService.pythonRefineOutputInstruction());

        try {
            List<dev.langchain4j.data.message.ChatMessage> llmMessages = List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt.toString())
            );
            // 设置当前 phase 并记录开始时间
            AgentContext.setPhase(AgentObservabilityService.PHASE_SUB_AGENT);
            AgentContext.setStage("python_refine_plan");
            long llmStartedAt = System.currentTimeMillis();
            Response<dev.langchain4j.data.message.AiMessage> resp = model.generate(llmMessages);
            long llmCompletedAt = System.currentTimeMillis();
            long llmDurationMs = llmCompletedAt - llmStartedAt;
            String runId = AgentContext.getRunId();
            String llmText = resp.content().text();
            String traceId = "";
            if (runId != null && !runId.isBlank()) {
                Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                        request.getEndpointName(),
                        request.getEndpointBaseUrl(),
                        request.getModelName(),
                        llmMessages,
                        null,
                        Map.of("stage", "python_refine_plan")
                );
                traceId = observabilityService.recordLlmCall(
                        runId,
                        AgentObservabilityService.PHASE_SUB_AGENT,
                        resp.tokenUsage(),
                        llmDurationMs,
                        llmStartedAt,
                        llmCompletedAt,
                        request.getEndpointName(),
                        request.getModelName(),
                        null,
                        llmRequestSnapshot,
                        llmText
                );
            }
            return extractPlan(llmText, currentRunArgs, systemPrompt, userPrompt.toString(), null,
                    traceId, "python_refine_plan", preview(userPrompt.toString(), 800));
        } catch (Exception e) {
            log.warn("Generate python code failed", e);
            return GeneratedPlan.builder()
                    .code("")
                    .runArgs(currentRunArgs)
                    .llmSnapshot(buildLlmSnapshot(systemPrompt, userPrompt.toString(), currentRunArgs, "", e.getMessage()))
                    .decisionLlmTraceId(safe(request.getDecisionLlmTraceId()))
                    .decisionStage(safe(request.getDecisionStage()))
                    .decisionExcerpt(safe(request.getDecisionExcerpt()))
                    .build();
        }
    }

    private GeneratedPlan extractPlan(String text,
                                      Map<String, Object> fallbackRunArgs,
                                      String systemPrompt,
                                      String userPrompt,
                                      String error,
                                      String traceId,
                                      String stage,
                                      String excerpt) {
        String code = extractCode(text);
        Map<String, Object> runArgs = extractRunArgs(text);
        if (runArgs.isEmpty()) {
            runArgs = fallbackRunArgs == null ? Map.of() : fallbackRunArgs;
        }
        return GeneratedPlan.builder()
                .code(code)
                .runArgs(mergeRunArgs(fallbackRunArgs, runArgs))
                .llmSnapshot(buildLlmSnapshot(systemPrompt, userPrompt, fallbackRunArgs, text, error))
                .decisionLlmTraceId(safe(traceId))
                .decisionStage(safe(stage))
                .decisionExcerpt(safe(excerpt))
                .build();
    }

    private String extractCode(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            String json = extractJson(text);
            JsonNode node = objectMapper.readTree(json);
            String code = node.path("code").asText("");
            if (!code.isBlank()) {
                return code.trim();
            }
        } catch (Exception ignored) {
            // ignore and fallback below
        }

        Matcher matcher = PYTHON_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text.trim();
    }

    private Map<String, Object> extractRunArgs(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            String json = extractJson(text);
            JsonNode node = objectMapper.readTree(json);
            JsonNode runArgsNode = node.path("run_args");
            if (runArgsNode.isObject()) {
                Map<String, Object> parsed = objectMapper.convertValue(runArgsNode, new TypeReference<Map<String, Object>>() {});
                return sanitizeRunArgs(parsed);
            }
            return Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> buildInitialRunArgs(Request request) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (request.getInitialRunArgs() != null) {
            merged.putAll(request.getInitialRunArgs());
        }
        // 优先使用 dataset_ids（复数），兼容 dataset_id（单数）
        String datasetIds = firstNonBlank(request.getDatasetIds(), request.getDatasetId());
        if (!safe(datasetIds).isBlank()) {
            merged.putIfAbsent("dataset_ids", datasetIds.trim());
        }
        if (!safe(request.getLibraries()).isBlank()) {
            merged.putIfAbsent("libraries", request.getLibraries().trim());
        }
        if (request.getTimeoutSeconds() != null && request.getTimeoutSeconds() > 0) {
            merged.putIfAbsent("timeout_seconds", request.getTimeoutSeconds());
        }
        merged.putIfAbsent("timeout_seconds", DEFAULT_TIMEOUT_SECONDS);
        return sanitizeRunArgs(merged);
    }

    private Map<String, Object> mergeRunArgs(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(base == null ? Map.of() : sanitizeRunArgs(base));
        merged.putAll(overrides == null ? Map.of() : sanitizeRunArgs(overrides));
        return sanitizeRunArgs(merged);
    }

    private Map<String, Object> sanitizeRunArgs(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        // 优先使用 dataset_ids（复数），兼容 dataset_id（单数）
        String datasetIds = firstNonBlank(raw.get("dataset_ids"), raw.get("datasetIds"), raw.get("dataset_id"), raw.get("datasetId"));
        datasetIds = normalizeDatasetIds(datasetIds);
        if (!datasetIds.isBlank()) {
            out.put("dataset_ids", datasetIds);
        }
        String libraries = firstNonBlank(raw.get("libraries"));
        if (!libraries.isBlank()) {
            out.put("libraries", libraries);
        }
        Integer timeout = toNullableInt(raw.get("timeout_seconds"), raw.get("timeoutSeconds"));
        timeout = clampTimeout(timeout);
        if (timeout != null && timeout > 0) {
            out.put("timeout_seconds", timeout);
        }
        return out;
    }

    private String normalizeDatasetId(String datasetId) {
        String id = safe(datasetId).trim();
        if (id.isBlank()) {
            return "";
        }
        if (!DATASET_ID_VALUE_PATTERN.matcher(id).matches()) {
            return "";
        }
        return id;
    }

    private String normalizeDatasetIds(String datasetIds) {
        if (datasetIds == null || datasetIds.isBlank()) {
            return "";
        }
        String raw = datasetIds.trim();
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        List<String> ids = new ArrayList<>();
        for (String item : raw.split(",")) {
            String normalized = normalizeDatasetId(item);
            if (!normalized.isBlank() && !ids.contains(normalized)) {
                ids.add(normalized);
            }
        }
        return String.join(",", ids);
    }

    private Integer clampTimeout(Integer timeout) {
        if (timeout == null || timeout <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        if (timeout < MIN_TIMEOUT_SECONDS) {
            return MIN_TIMEOUT_SECONDS;
        }
        return Math.min(timeout, MAX_TIMEOUT_SECONDS);
    }

    private Map<String, Object> copyForTrace(Map<String, Object> runArgs) {
        return new LinkedHashMap<>(runArgs == null ? Map.of() : runArgs);
    }

    private Map<String, Object> copySnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(snapshot);
    }

    private Map<String, Object> buildLlmSnapshot(String systemPrompt,
                                                 String userPrompt,
                                                 Map<String, Object> runArgs,
                                                 String rawResponse,
                                                 String error) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("system_prompt", safe(systemPrompt));
        snapshot.put("user_prompt", safe(userPrompt));
        snapshot.put("run_args", copyForTrace(runArgs));
        snapshot.put("raw_response", safe(rawResponse));
        if (!safe(error).isBlank()) {
            snapshot.put("error", error);
        }
        return snapshot;
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    private boolean isExecutionSuccess(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(output);
            return root.path("ok").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private int resolveMaxAttempts() {
        int fromLocal = localConfigLoader.current()
                .map(CodeRefineProperties::getMaxAttempts)
                .orElse(0);
        if (fromLocal > 0) {
            return fromLocal;
        }
        int fromYml = codeRefineProperties.getMaxAttempts();
        if (fromYml > 0) {
            return fromYml;
        }
        return DEFAULT_MAX_ATTEMPTS;
    }

    private Integer toNullableInt(Object... values) {
        String raw = firstNonBlank(values);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String s = String.valueOf(value).trim();
            if (!s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit);
    }
}
