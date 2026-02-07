package world.willfrog.agent.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ToolRouter {

    private final MarketDataTools marketDataTools;
    private final PythonSandboxTools pythonSandboxTools;

    public String invoke(String toolName, Map<String, Object> params) {
        try {
            return switch (toolName) {
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
            return "Tool invocation error: " + e.getMessage();
        }
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
}
