package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.StressTestProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentObservabilityService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ToolRouter {

    private final MarketDataTools marketDataTools;
    private final PythonSandboxTools pythonSandboxTools;
    private final ToolResultCacheService toolResultCacheService;
    private final AgentObservabilityService observabilityService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final StressTestProperties stressTestProperties;
    private final ConcurrentHashMap<String, Timer> toolCallTimers = new ConcurrentHashMap<>();

    public String invoke(String toolName, Map<String, Object> params) {
        return invokeWithMeta(toolName, params).getOutput();
    }

    public ToolInvocationResult invokeWithMeta(String toolName, Map<String, Object> params) {
        debugLog("tool invoke request: runId={}, tool={}, params={}",
                AgentContext.getRunId(), nvl(toolName), safeJson(params));

        // Fault injection: simulated latency
        if (stressTestProperties.isToolLatencyEnabled() && stressTestProperties.getToolLatencyMs() > 0) {
            try {
                Thread.sleep(stressTestProperties.getToolLatencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Fault injection: simulated failure
        if (stressTestProperties.getToolFailureRate() > 0 && Math.random() < stressTestProperties.getToolFailureRate()) {
            String errorResult = invocationError(toolName, "Simulated failure for stress test");
            recordObservability(toolName, params, errorResult, 0, false, null);
            getOrCreateToolCallTimer(nvl(toolName)).record(0, TimeUnit.MILLISECONDS);
            return ToolInvocationResult.builder()
                    .output(errorResult)
                    .success(false)
                    .durationMs(0)
                    .cacheMeta(null)
                    .build();
        }

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
        recordObservability(toolName, params, result, durationMs, success, cacheMeta);

        getOrCreateToolCallTimer(nvl(toolName)).record(durationMs, TimeUnit.MILLISECONDS);

        debugLog("tool invoke response: runId={}, tool={}, success={}, durationMs={}, cache={}, resultPreview={}",
                AgentContext.getRunId(),
                nvl(toolName),
                success,
                durationMs,
                toolResultCacheService.toPayload(cacheMeta),
                preview(result));
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

    private Timer getOrCreateToolCallTimer(String toolName) {
        return toolCallTimers.computeIfAbsent(toolName, name ->
                Timer.builder("tool.call")
                        .tag("toolName", name)
                        .register(meterRegistry));
    }

    private ToolResultCacheService.ToolExecutionOutcome executeDirect(String toolName, Map<String, Object> params) {
        long startedAt = System.currentTimeMillis();
        String result;
        try {
            // 统一入口负责兼容参数别名（ts_code/code 等），工具实现层只接收标准参数。
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
                        collectExecutePythonDatasetIds(params),
                        str(params.get("libraries"), params.get("arg3")),
                        toNullableInt(params.get("timeout_seconds"), params.get("timeoutSeconds"), params.get("arg4"))
                );
                default -> unsupported(toolName);
            };
        } catch (Exception e) {
            debugLog("tool invoke exception: runId={}, tool={}, error={}",
                    AgentContext.getRunId(), nvl(toolName), nvl(e.getMessage()));
            result = invocationError(toolName, e.getMessage());
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

    private String collectExecutePythonDatasetIds(Map<String, Object> params) {
        LinkedHashSet<String> datasetIds = new LinkedHashSet<>();
        addDatasetIds(datasetIds,
                params.get("dataset_ids"),
                params.get("datasetIds"),
                params.get("datasets"),
                params.get("dataset_refs"),
                params.get("datasetRefs"),
                params.get("arg2"),
                params.get("dataset_id"),
                params.get("datasetId"),
                params.get("arg1")
        );
        return String.join(",", datasetIds);
    }

    private void addDatasetIds(LinkedHashSet<String> collector, Object... candidates) {
        if (collector == null || candidates == null || candidates.length == 0) {
            return;
        }
        for (Object candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            List<String> parsed = parseDatasetIds(String.valueOf(candidate));
            collector.addAll(parsed);
        }
    }

    private List<String> parseDatasetIds(String datasetIds) {
        if (datasetIds == null || datasetIds.isBlank()) {
            return List.of();
        }
        String raw = datasetIds.trim();
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        List<String> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = nvl(part).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (!value.isBlank() && !ids.contains(value)) {
                ids.add(value);
            }
        }
        return ids;
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
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 8 || digits.length() == 13) {
            return digits;
        }
        return raw;
    }

    private void recordObservability(String toolName,
                                     Map<String, Object> params,
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
                params,
                result,
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
        try {
            JsonNode node = objectMapper.readTree(result);
            return node.path("ok").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private String unsupported(String toolName) {
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", "UNSUPPORTED_TOOL",
                        "message", "Unsupported tool",
                        "details", Map.of("tool", nvl(toolName))
                )
        ));
    }

    private String invocationError(String toolName, String message) {
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", "TOOL_INVOCATION_ERROR",
                        "message", nvl(message),
                        "details", Map.of()
                )
        ));
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("ok", false);
            fallback.put("tool", "unknown");
            fallback.put("data", Map.of());
            fallback.put("error", Map.of(
                    "code", "JSON_SERIALIZE_ERROR",
                    "message", nvl(e.getMessage()),
                    "details", Map.of()
            ));
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{\"ok\":false}";
            }
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

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private void debugLog(String pattern, Object... args) {
        if (!AgentContext.isDebugMode()) {
            return;
        }
        log.info("[agent-debug] " + pattern, args);
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
