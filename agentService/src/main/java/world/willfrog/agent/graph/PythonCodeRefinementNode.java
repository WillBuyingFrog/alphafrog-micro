package world.willfrog.agent.graph;

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
import world.willfrog.agent.config.CodeRefineProperties;
import world.willfrog.agent.service.CodeRefineLocalConfigLoader;
import world.willfrog.agent.tool.ToolRouter;

import java.util.ArrayList;
import java.util.HashMap;
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
    private static final Pattern PYTHON_BLOCK_PATTERN = Pattern.compile("```(?:python)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper;
    private final CodeRefineProperties codeRefineProperties;
    private final CodeRefineLocalConfigLoader localConfigLoader;

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
    }

    @Data
    @Builder
    public static class AttemptTrace {
        private int attempt;
        private String code;
        private Map<String, Object> runArgs;
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
    }

    public Result execute(Request request, ChatLanguageModel model) {
        int maxAttempts = resolveMaxAttempts();
        List<AttemptTrace> traces = new ArrayList<>();
        String currentCode = safe(request.getInitialCode());
        Map<String, Object> currentRunArgs = buildInitialRunArgs(request);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (currentCode.isBlank()) {
                GeneratedPlan generated = generatePythonPlan(request, traces, currentRunArgs, model);
                currentCode = safe(generated.getCode());
                currentRunArgs = mergeRunArgs(currentRunArgs, generated.getRunArgs());
                if (currentCode.isBlank()) {
                    String output = "Python code generation failed: empty code";
                    traces.add(AttemptTrace.builder()
                            .attempt(attempt)
                            .code("")
                            .runArgs(copyForTrace(currentRunArgs))
                            .output(output)
                            .success(false)
                            .build());
                    continue;
                }
            }

            String output = toolRouter.invoke("executePython", buildExecuteArgs(currentRunArgs, currentCode));
            boolean success = isExecutionSuccess(output);
            traces.add(AttemptTrace.builder()
                    .attempt(attempt)
                    .code(currentCode)
                    .runArgs(copyForTrace(currentRunArgs))
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
        String systemPrompt = "你是代码执行代理。请基于目标、相关上下文和执行反馈，输出可直接运行的 Python 代码和运行参数。"
                + "必须只输出 JSON，格式为 {\"code\":\"...\",\"run_args\":{\"dataset_id\":\"...\",\"dataset_ids\":\"...\",\"libraries\":\"...\",\"timeout_seconds\":30},\"reason\":\"...\"}。"
                + "run_args 中最关键是 dataset_id，必须指向真实可访问的数据目录。";
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("任务目标:\n").append(safe(request.getGoal())).append("\n");
        userPrompt.append("任务上下文:\n").append(safe(request.getContext())).append("\n");
        userPrompt.append("编码相关上下文(由上游模型筛选):\n").append(safe(request.getCodingContext())).append("\n");
        userPrompt.append("当前运行参数:\n").append(writeJson(currentRunArgs)).append("\n");
        userPrompt.append("要求:\n");
        userPrompt.append("- 你可以在本轮修正 run_args，尤其是 dataset_id/dataset_ids\n");
        userPrompt.append("- 代码中不要假设未定义变量，直接按可访问文件路径读取数据\n");
        userPrompt.append("- 优先输出 JSON 结果，便于后续流程消费\n");
        userPrompt.append("- 若上轮报 dataset_id 相关错误，必须优先修正 run_args 再生成代码\n");

        if (!traces.isEmpty()) {
            AttemptTrace last = traces.get(traces.size() - 1);
            userPrompt.append("上一轮运行参数:\n").append(writeJson(last.getRunArgs())).append("\n");
            userPrompt.append("上一轮失败代码:\n```python\n")
                    .append(safe(last.getCode()))
                    .append("\n```\n");
            userPrompt.append("上一轮执行反馈:\n")
                    .append(safe(last.getOutput()))
                    .append("\n");
        }

        userPrompt.append("请只返回 JSON，不要返回 markdown。");

        try {
            Response<dev.langchain4j.data.message.AiMessage> resp = model.generate(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt.toString())
            ));
            return extractPlan(resp.content().text(), currentRunArgs);
        } catch (Exception e) {
            log.warn("Generate python code failed", e);
            return GeneratedPlan.builder()
                    .code("")
                    .runArgs(currentRunArgs)
                    .build();
        }
    }

    private GeneratedPlan extractPlan(String text, Map<String, Object> fallbackRunArgs) {
        String code = extractCode(text);
        Map<String, Object> runArgs = extractRunArgs(text);
        if (runArgs.isEmpty()) {
            runArgs = fallbackRunArgs == null ? Map.of() : fallbackRunArgs;
        }
        return GeneratedPlan.builder()
                .code(code)
                .runArgs(mergeRunArgs(fallbackRunArgs, runArgs))
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
                Map<String, Object> parsed = objectMapper.convertValue(runArgsNode, Map.class);
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
        if (!safe(request.getDatasetId()).isBlank()) {
            merged.putIfAbsent("dataset_id", request.getDatasetId().trim());
        }
        if (!safe(request.getDatasetIds()).isBlank()) {
            merged.putIfAbsent("dataset_ids", request.getDatasetIds().trim());
        }
        if (!safe(request.getLibraries()).isBlank()) {
            merged.putIfAbsent("libraries", request.getLibraries().trim());
        }
        if (request.getTimeoutSeconds() != null && request.getTimeoutSeconds() > 0) {
            merged.putIfAbsent("timeout_seconds", request.getTimeoutSeconds());
        }
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
        String datasetId = firstNonBlank(raw.get("dataset_id"), raw.get("datasetId"), raw.get("arg1"));
        if (!datasetId.isBlank()) {
            out.put("dataset_id", datasetId);
        }
        String datasetIds = firstNonBlank(raw.get("dataset_ids"), raw.get("datasetIds"), raw.get("arg2"));
        if (!datasetIds.isBlank()) {
            out.put("dataset_ids", datasetIds);
        }
        String libraries = firstNonBlank(raw.get("libraries"), raw.get("arg3"));
        if (!libraries.isBlank()) {
            out.put("libraries", libraries);
        }
        Integer timeout = toNullableInt(raw.get("timeout_seconds"), raw.get("timeoutSeconds"), raw.get("arg4"));
        if (timeout != null && timeout > 0) {
            out.put("timeout_seconds", timeout);
        }
        return out;
    }

    private Map<String, Object> copyForTrace(Map<String, Object> runArgs) {
        return new LinkedHashMap<>(runArgs == null ? Map.of() : runArgs);
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
        if (output.startsWith("Tool invocation error")) {
            return false;
        }
        if (output.startsWith("Tool Execution Error")) {
            return false;
        }
        if (output.startsWith("Failed to create task")) {
            return false;
        }
        if (output.startsWith("Task FAILED")) {
            return false;
        }
        if (output.startsWith("Task CANCELED")) {
            return false;
        }
        if (output.startsWith("Task PENDING (Timeout)")) {
            return false;
        }
        if (output.startsWith("Exit Code:")) {
            return false;
        }
        return !output.contains("STDERR:");
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
