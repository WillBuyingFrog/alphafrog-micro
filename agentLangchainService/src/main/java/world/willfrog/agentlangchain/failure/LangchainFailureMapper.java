package world.willfrog.agentlangchain.failure;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 失败分类器 —— 把 agent run 中捕获的异常/错误信息映射为结构化的失败决策。
 *
 * <h2>为什么需要这个类</h2>
 * agent 执行过程中会产生各种各样的失败：LLM 调用超时、工具参数错误、HTTP 5xx、
 * 预算耗尽、空输出等。如果所有失败都笼统地归类为 WORKFLOW_FAILED，排障时
 * 只能靠肉眼扫日志，无法做自动重试、针对性告警或 observability 聚合。
 *
 * <h2>六种失败分类</h2>
 * <ol>
 *   <li>{@code BUDGET_EXCEEDED} — wall clock / tokens / tool calls 超限，不可重试</li>
 *   <li>{@code REPEATED_TOOL_CALL} — 同 tool 同参数重复调用被拦截，可重试</li>
 *   <li>{@code PARAM_RETRY_WITH_HINT} — 参数错误（缺 dataset_id、schema 校验失败等），
 *       可重试并提示修正方向</li>
 *   <li>{@code INFRA_RETRY} — 基础设施错误（HTTP 5xx、连接重置、超时），可重试</li>
 *   <li>{@code TOOL_ERROR} — 工具执行失败（非参数原因），不可重试</li>
 *   <li>{@code EMPTY_OUTPUT} — LLM 返回空内容，不可重试</li>
 * </ol>
 * 兜底分类为 {@code UNKNOWN}。
 *
 * <h2>分类规则</h2>
 * 按顺序匹配失败文本中的关键词（大小写不敏感），命中即返回，不继续尝试后续规则。
 * 这种"首匹配"策略简单但要求规则顺序设计合理：预算类排最前
 * （一旦命中应该立刻归类为 BUDGET，不应被后续 TOOL_ERROR 覆盖），
 * 然后是具体的错误特征（重复调用 → 参数错误 → 基础设施错误 → 通用工具错误）。
 *
 * <h2>与 observability 的关系</h2>
 * 每个失败决策都包含 {@code observabilityFailureType} 字段（如 "RunBudgetExceeded"、
 * "ParameterRetryWithHint"），这些值会被 {@code AgentObservabilityService} 写入
 * timeline/trace，用于失败分布统计和面试复盘。
 *
 * <h2>面试典型追问</h2>
 * "你们怎么区分不同失败类型？什么时候重试、什么时候直接失败？"
 * 答案在本类的分类规则和 {@code retryable} 标记。
 */
@Component
public class LangchainFailureMapper {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 主分类入口。将失败原因文本（含 failureReason、toolOutput、异常信息）
     * 合并后按关键词规则匹配，输出结构化的 {@link LangchainFailureDecision}。
     *
     * <p>匹配顺序即为优先级：BUDGET > REPEATED > PARAM > INFRA > TOOL > EMPTY > UNKNOWN。
     * 这个顺序是刻意设计的——预算耗尽必须第一时间识别并禁止重试，
     * 否则后续的 INFRA_RETRY 或 TOOL_ERROR 规则可能误匹配并触发无意义的重试。
     *
     * @param phase         失败发生的阶段（planning/execution/summarizing 等）
     * @param todoId        当前 todo ID（可为 null）
     * @param toolName      当前 tool 名称（可为 null）
     * @param failureReason 业务层填充的失败原因
     * @param toolOutput    工具执行输出（可能包含更多错误细节）
     * @param throwable     JVM 异常（可提取异常类名和消息）
     * @param toolCallsUsed 失败时已消耗的 tool calls 数（可为 null）
     * @return 包含 runStatus、eventType、category、retryable 等字段的决策对象
     */
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
