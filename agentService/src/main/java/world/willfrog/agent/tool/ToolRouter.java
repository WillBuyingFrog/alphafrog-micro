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
                        str(params.get("tsCode"), params.get("ts_code"), params.get("arg0"))
                );
                case "getStockDaily" -> marketDataTools.getStockDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("arg0")),
                        str(params.get("startDateStr"), params.get("start_date"), params.get("arg1")),
                        str(params.get("endDateStr"), params.get("end_date"), params.get("arg2"))
                );
                case "searchStock" -> marketDataTools.searchStock(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "searchFund" -> marketDataTools.searchFund(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "getIndexInfo" -> marketDataTools.getIndexInfo(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("arg0"))
                );
                case "getIndexDaily" -> marketDataTools.getIndexDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("arg0")),
                        str(params.get("startDateStr"), params.get("start_date"), params.get("arg1")),
                        str(params.get("endDateStr"), params.get("end_date"), params.get("arg2"))
                );
                case "searchIndex" -> marketDataTools.searchIndex(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "executePython" -> pythonSandboxTools.executePython(
                        str(params.get("code"), params.get("arg0")),
                        str(params.get("dataset_id"), params.get("datasetId"), params.get("arg1")),
                        str(params.get("dataset_ids"), params.get("datasetIds"), params.get("arg2")),
                        str(params.get("libraries"), params.get("arg3")),
                        params.get("timeout_seconds") != null ? Integer.parseInt(str(params.get("timeout_seconds"), params.get("arg4"))) : null
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
}
