package world.willfrog.agentlangchain.failure;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LangchainFailureMapper {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");

    public LangchainFailureDecision map(String phase,
                                        String todoId,
                                        String toolName,
                                        String failureReason,
                                        String toolOutput,
                                        Throwable throwable,
                                        Integer toolCallsUsed) {
        String text = collect(failureReason, toolOutput, throwable);
        String lower = text.toLowerCase(Locale.ROOT);
        String errorCode = extractErrorCode(text);

        if (containsAny(lower, "run_budget_exceeded", "wall_clock_ms", "budget_exceeded", "max tokens",
                "max_llm_calls", "max_tool_calls")) {
            return decision("RUN_BUDGET_EXCEEDED", LangchainFailureCategory.BUDGET_EXCEEDED, false,
                    "RunBudgetExceeded", text, phase, todoId, toolName, errorCode, toolCallsUsed);
        }
        if (containsAny(lower, "repeated_tool_call", "repeated tool")) {
            return decision("TOOL_ERROR", LangchainFailureCategory.REPEATED_TOOL_CALL, true,
                    "RepeatedToolCall", text, phase, todoId, toolName, errorCode, toolCallsUsed);
        }
        if (containsAny(lower, "missing_dataset_ids", "dataset_id directory not found", "dataset_ids",
                "schema", "validation", "missing required", "required parameter", "invalid parameter",
                "参数名", "keyword")) {
            return decision("TOOL_ERROR", LangchainFailureCategory.PARAM_RETRY_WITH_HINT, true,
                    "ParameterRetryWithHint", text, phase, todoId, toolName, errorCode, toolCallsUsed);
        }
        if (containsAny(lower, "http_error_5", "http 500", "http 502", "http 503", "http 504",
                "timeout", "timed out", "connection reset", "connection refused", "upstream unavailable",
                "internal server error")) {
            return decision("TOOL_ERROR", LangchainFailureCategory.INFRA_RETRY, true,
                    "InfrastructureRetry", text, phase, todoId, toolName, errorCode, toolCallsUsed);
        }
        if (containsAny(lower, "tool_error", "tool execution", "failed to execute tool")) {
            return decision("TOOL_ERROR", LangchainFailureCategory.TOOL_ERROR, false,
                    "ToolError", text, phase, todoId, toolName, errorCode, toolCallsUsed);
        }
        if (containsAny(lower, "empty_final_answer", "empty_todo_output", "blank output")) {
            return decision("WORKFLOW_FAILED", LangchainFailureCategory.EMPTY_OUTPUT, false,
                    "EmptyOutput", text, phase, todoId, toolName, errorCode, toolCallsUsed);
        }
        return decision("WORKFLOW_FAILED", LangchainFailureCategory.UNKNOWN, false,
                "WorkflowFailed", text, phase, todoId, toolName, errorCode, toolCallsUsed);
    }

    public LangchainFailureDecision map(String failureReason) {
        return map(null, null, null, failureReason, null, null, null);
    }

    private LangchainFailureDecision decision(String eventType,
                                              LangchainFailureCategory category,
                                              boolean retryable,
                                              String observabilityFailureType,
                                              String reason,
                                              String phase,
                                              String todoId,
                                              String toolName,
                                              String errorCode,
                                              Integer toolCallsUsed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", nvl(reason));
        payload.put("category", category.name());
        payload.put("retryable", retryable);
        putIfNotBlank(payload, "phase", phase);
        putIfNotBlank(payload, "todo_id", todoId);
        putIfNotBlank(payload, "tool_name", toolName);
        putIfNotBlank(payload, "error_code", errorCode);
        if (toolCallsUsed != null) {
            payload.put("tool_calls_used", Math.max(0, toolCallsUsed));
        }
        return LangchainFailureDecision.builder()
                .runStatus(AgentRunStatus.FAILED)
                .eventType(eventType)
                .reason(nvl(reason))
                .category(category)
                .retryable(retryable)
                .observabilityFailureType(observabilityFailureType)
                .eventPayload(payload)
                .build();
    }

    private String collect(String failureReason, String toolOutput, Throwable throwable) {
        StringBuilder text = new StringBuilder();
        append(text, failureReason);
        append(text, toolOutput);
        if (throwable != null) {
            append(text, throwable.getClass().getSimpleName());
            append(text, throwable.getMessage());
        }
        return text.toString().trim();
    }

    private void append(StringBuilder text, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(value);
    }

    private String extractErrorCode(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = ERROR_CODE_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
