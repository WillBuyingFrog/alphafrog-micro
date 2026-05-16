package world.willfrog.agent.workflow;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对 workflow 失败做轻量规则分类，避免所有失败都进入 LLM PlanJudge。
 */
@Component
public class WorkflowFailureClassifier {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");

    public FailureClassification classify(ReactTodoExecutor.TodoExecutionRecord record) {
        String text = collectFailureText(record).toLowerCase(Locale.ROOT);
        String errorCode = extractErrorCode(collectFailureText(record));

        if (containsAny(text, "run_budget_exceeded", "capability_disabled", "unauthorized", "forbidden",
                "permission", "run was canceled", "run was failed", "run was canceling")) {
            return new FailureClassification(FailureCategory.FATAL_FAIL, RecoveryAction.FAIL_FAST, errorCode);
        }
        if (containsAny(text, "http_error_5", "http 500", "http 502", "http 503", "http 504",
                "timeout", "timed out", "connection reset", "connection refused", "upstream unavailable",
                "internal server error")) {
            return new FailureClassification(FailureCategory.INFRA_RETRY, RecoveryAction.RETRY_CURRENT, errorCode);
        }
        if (containsAny(text, "missing_dataset_ids", "dataset_ids", "schema", "validation",
                "missing required", "required parameter", "invalid parameter", "参数名", "keyword",
                "repeated_tool_call")) {
            return new FailureClassification(FailureCategory.PARAM_RETRY_WITH_HINT, RecoveryAction.RETRY_CURRENT, errorCode);
        }
        if (containsAny(text, "no_data", "not found", "empty", "无结果", "没有找到", "实体不匹配",
                "低相关", "relevance")) {
            return new FailureClassification(FailureCategory.BUSINESS_JUDGE, RecoveryAction.USE_PLAN_JUDGE, errorCode);
        }
        return new FailureClassification(FailureCategory.BUSINESS_JUDGE, RecoveryAction.USE_PLAN_JUDGE, errorCode);
    }

    private String collectFailureText(ReactTodoExecutor.TodoExecutionRecord record) {
        if (record == null) {
            return "";
        }
        return nvl(record.getSummary()) + "\n" + nvl(record.getOutput());
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

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    public enum FailureCategory {
        INFRA_RETRY,
        PARAM_RETRY_WITH_HINT,
        BUSINESS_JUDGE,
        FATAL_FAIL
    }

    public enum RecoveryAction {
        RETRY_CURRENT,
        USE_PLAN_JUDGE,
        FAIL_FAST
    }

    public record FailureClassification(FailureCategory category, RecoveryAction action, String errorCode) {
    }
}
