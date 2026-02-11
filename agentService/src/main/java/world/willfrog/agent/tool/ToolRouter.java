package world.willfrog.agent.tool;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentObservabilityService;

import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ToolRouter {

    private final MarketDataTools marketDataTools;
    private final PythonSandboxTools pythonSandboxTools;
    private final ToolResultCacheService toolResultCacheService;
    private final AgentObservabilityService observabilityService;

    public String invoke(String toolName, Map<String, Object> params) {
        return invokeWithMeta(toolName, params).getOutput();
    }

    public ToolInvocationResult invokeWithMeta(String toolName, Map<String, Object> params) {
        ToolResultCacheService.CachedToolCallResult cached = toolResultCacheService.executeWithCache(
                toolName,
                params,
                resolveScope(),
                () -> executeDirect(toolName, params)
        );
        String result = nvl(cached.getResult());
        boolean success = cached.isSuccess();
        long durationMs = Math.max(0L, cached.getDurationMs());
        ToolResultCacheService.CacheMeta cacheMeta = cached.getCacheMeta();
        recordObservability(toolName, result, durationMs, success, cacheMeta);
        return ToolInvocationResult.builder()
                .output(result)
                .success(success)
                .durationMs(durationMs)
                .cacheMeta(cacheMeta)
                .build();
    }

    public Map<String, Object> toEventCachePayload(ToolInvocationResult invocationResult) {
        return toolResultCacheService.toPayload(invocationResult == null ? null : invocationResult.getCacheMeta());
    }

    public Set<String> supportedTools() {
        return Set.of(
                "getStockInfo",
                "getStockDaily",
                "searchStock",
                "searchFund",
                "getIndexInfo",
                "getIndexDaily",
                "searchIndex",
                "executePython"
        );
    }

    private ToolResultCacheService.ToolExecutionOutcome executeDirect(String toolName, Map<String, Object> params) {
        long startedAt = System.currentTimeMillis();
        String result;
        try {
            result = switch (toolName) {
                case "getStockInfo" -> marketDataTools.getStockInfo(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("stock_code"), params.get("arg0"))
                );
                case "getStockDaily" -> marketDataTools.getStockDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("stock_code"), params.get("arg0")),
                        dateStr(params.get("startDateStr"), params.get("startDate"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDateStr"), params.get("endDate"), params.get("end_date"), params.get("arg2"))
                );
                case "searchStock" -> marketDataTools.searchStock(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "searchFund" -> marketDataTools.searchFund(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "getIndexInfo" -> marketDataTools.getIndexInfo(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("index_code"), params.get("arg0"))
                );
                case "getIndexDaily" -> marketDataTools.getIndexDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("index_code"), params.get("arg0")),
                        dateStr(params.get("startDateStr"), params.get("startDate"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDateStr"), params.get("endDate"), params.get("end_date"), params.get("arg2"))
                );
                case "searchIndex" -> marketDataTools.searchIndex(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "executePython" -> pythonSandboxTools.executePython(
                        str(params.get("code"), params.get("arg0")),
                        str(params.get("dataset_id"), params.get("datasetId"), params.get("arg1")),
                        str(params.get("dataset_ids"), params.get("datasetIds"), params.get("arg2")),
                        str(params.get("libraries"), params.get("arg3")),
                        toNullableInt(params.get("timeout_seconds"), params.get("timeoutSeconds"), params.get("arg4"))
                );
                default -> "Unsupported tool: " + toolName;
            };
        } catch (Exception e) {
            result = "Tool invocation error: " + e.getMessage();
        }
        return ToolResultCacheService.ToolExecutionOutcome.builder()
                .result(result)
                .durationMs(Math.max(0L, System.currentTimeMillis() - startedAt))
                .success(isToolSuccess(result))
                .build();
    }

    private String str(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) {
                continue;
            }
            String s = String.valueOf(c).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    private Integer toNullableInt(Object... candidates) {
        String value = str(candidates);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String dateStr(Object... candidates) {
        String raw = str(candidates);
        if (raw.isEmpty()) {
            return "";
        }
        // 兼容 yyyy-MM-dd / yyyy/MM/dd / yyyyMMdd 三类常见入参
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 8 || digits.length() == 13) {
            return digits;
        }
        return raw;
    }

    private void recordObservability(String toolName,
                                     String result,
                                     long durationMs,
                                     boolean success,
                                     ToolResultCacheService.CacheMeta cacheMeta) {
        String runId = AgentContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        String phase = AgentContext.getPhase();
        observabilityService.recordToolCall(
                runId,
                phase,
                toolName,
                durationMs,
                success,
                cacheMeta != null && cacheMeta.isEligible(),
                cacheMeta != null && cacheMeta.isHit(),
                cacheMeta == null ? "" : cacheMeta.getKey(),
                cacheMeta == null ? "" : cacheMeta.getSource(),
                cacheMeta == null ? -1L : cacheMeta.getTtlRemainingMs(),
                cacheMeta == null ? 0L : cacheMeta.getEstimatedSavedDurationMs(),
                success ? null : result
        );
    }

    private boolean isToolSuccess(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        Integer exitCode = parseLeadingExitCode(result);
        if (exitCode != null) {
            return exitCode == 0;
        }
        if (result.startsWith("Tool invocation error")) {
            return false;
        }
        if (result.startsWith("Unsupported tool")) {
            return false;
        }
        if (result.startsWith("Tool Execution Error")) {
            return false;
        }
        if (result.startsWith("Failed to create task")) {
            return false;
        }
        if (result.startsWith("Task FAILED")) {
            return false;
        }
        if (result.startsWith("Task PENDING")) {
            return false;
        }
        return !result.startsWith("Task CANCELED");
    }

    private Integer parseLeadingExitCode(String result) {
        String trimmed = result == null ? "" : result.trim();
        if (!trimmed.startsWith("Exit Code:")) {
            return null;
        }
        int lineEnd = trimmed.indexOf('\n');
        String firstLine = lineEnd >= 0 ? trimmed.substring(0, lineEnd) : trimmed;
        String value = firstLine.substring("Exit Code:".length()).trim();
        if (value.isEmpty()) {
            return null;
        }
        int spaceIdx = value.indexOf(' ');
        String candidate = spaceIdx > 0 ? value.substring(0, spaceIdx) : value;
        try {
            return Integer.parseInt(candidate);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveScope() {
        String userId = AgentContext.getUserId();
        if (userId == null || userId.isBlank()) {
            return "global";
        }
        return "user:" + userId.trim();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    @Data
    @Builder
    public static class ToolInvocationResult {
        private String output;
        private boolean success;
        private long durationMs;
        private ToolResultCacheService.CacheMeta cacheMeta;
    }
}
