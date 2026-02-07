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
        private String initialCode;
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

    public Result execute(Request request, ChatLanguageModel model) {
        int maxAttempts = resolveMaxAttempts();
        List<AttemptTrace> traces = new ArrayList<>();
        String currentCode = safe(request.getInitialCode());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (currentCode.isBlank()) {
                currentCode = generatePythonCode(request, traces, model);
                if (currentCode.isBlank()) {
                    String output = "Python code generation failed: empty code";
                    traces.add(AttemptTrace.builder()
                            .attempt(attempt)
                            .code("")
                            .output(output)
                            .success(false)
                            .build());
                    continue;
                }
            }

            String output = toolRouter.invoke("executePython", buildExecuteArgs(request, currentCode));
            boolean success = isExecutionSuccess(output);
            traces.add(AttemptTrace.builder()
                    .attempt(attempt)
                    .code(currentCode)
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

    private Map<String, Object> buildExecuteArgs(Request request, String code) {
        Map<String, Object> args = new HashMap<>();
        args.put("code", code);
        args.put("dataset_id", safe(request.getDatasetId()));
        if (!safe(request.getDatasetIds()).isBlank()) {
            args.put("dataset_ids", request.getDatasetIds().trim());
        }
        if (!safe(request.getLibraries()).isBlank()) {
            args.put("libraries", request.getLibraries().trim());
        }
        if (request.getTimeoutSeconds() != null && request.getTimeoutSeconds() > 0) {
            args.put("timeout_seconds", request.getTimeoutSeconds());
        }
        return args;
    }

    private String generatePythonCode(Request request,
                                      List<AttemptTrace> traces,
                                      ChatLanguageModel model) {
        String systemPrompt = "你是代码执行代理。请基于目标与执行反馈，输出可直接运行的 Python 代码。"
                + "必须只输出 JSON，格式为 {\"code\":\"...\",\"reason\":\"...\"}，其中 code 必须为完整可执行脚本。";
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("任务目标:\n").append(safe(request.getGoal())).append("\n");
        userPrompt.append("上下文:\n").append(safe(request.getContext())).append("\n");
        userPrompt.append("输入文件:\n");
        userPrompt.append("- dataset_id=").append(safe(request.getDatasetId())).append("\n");
        if (!safe(request.getDatasetIds()).isBlank()) {
            userPrompt.append("- dataset_ids=").append(request.getDatasetIds().trim()).append("\n");
        }
        userPrompt.append("约束:\n");
        userPrompt.append("- 必须读取 /sandbox/input/<dataset_id>/<dataset_id>.csv\n");
        userPrompt.append("- 输出可解析结果，优先 JSON（print(json.dumps(..., ensure_ascii=False))）\n");

        if (!traces.isEmpty()) {
            AttemptTrace last = traces.get(traces.size() - 1);
            userPrompt.append("上一轮失败代码:\n```python\n")
                    .append(safe(last.getCode()))
                    .append("\n```\n");
            userPrompt.append("上一轮执行反馈:\n")
                    .append(safe(last.getOutput()))
                    .append("\n");
        }

        try {
            Response<dev.langchain4j.data.message.AiMessage> resp = model.generate(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt.toString())
            ));
            return extractCode(resp.content().text());
        } catch (Exception e) {
            log.warn("Generate python code failed", e);
            return "";
        }
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
