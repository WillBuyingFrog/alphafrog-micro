package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.ExternalInfoDubboService;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResponse;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResultItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RagTools {

    private static final int MAX_TOP_K = 10;

    @DubboReference(timeout = 60000, retries = 0)
    private ExternalInfoDubboService externalInfoDubboService;

    private final ObjectMapper objectMapper;

    public RagTools(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool("""
        【首选工具】查询公告、年报、研报原文内容。
        
        适用场景：
          - 查询公司公告原文（如"募集资金变更"、"股权质押"、"重大合同"等）
          - 查询年报/半年报特定章节内容（如"风险提示"、"业务展望"）
          - 查询研报观点和数据
        
        与 getFinancialReport 的区别：
          - ragSearch：查公告/研报原文、非结构化文本、事件描述
          - getFinancialReport：查结构化财务数据（利润、资产负债等）
        
        参数说明：
          queryText  - 查询内容（如"贵州茅台募集资金变更公告"），必填
          docType    - 文档类型："announcement"（公告）| "research_report"（研报）| ""（不限），可选
          tsCode     - 股票代码过滤（如"600519.SH"），可选，建议填写以提高准确度
          indName    - 行业过滤（如"电子"、"电新"），可选，仅对研报有效
          topK       - 返回条数（默认5，最大10），可选
        """)
    public String ragSearch(String queryText, String docType, String tsCode, String indName, int topK) {
        try {
            int k = (topK <= 0 || topK > MAX_TOP_K) ? 5 : topK;
            RagSearchRequest req = RagSearchRequest.newBuilder()
                    .setQueryText(nvl(queryText))
                    .setDocType(nvl(docType))
                    .setTsCode(nvl(tsCode))
                    .setIndName(nvl(indName))
                    .setTopK(k)
                    .build();
            RagSearchResponse resp = externalInfoDubboService.ragSearch(req);

            List<Map<String, Object>> items = new ArrayList<>();
            for (RagSearchResultItem item : resp.getItemsList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("score", item.getScore());
                row.put("doc_type", item.getDocType());
                row.put("ts_code", item.getTsCode());
                row.put("ind_name", item.getIndName());
                row.put("title", item.getTitle());
                row.put("date", item.getDate());
                row.put("chunk_text", item.getChunkText());
                row.put("oss_url", item.getOssUrl());
                items.add(row);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("tool", "ragSearch");
            payload.put("data", Map.of(
                    "query", nvl(queryText),
                    "doc_type", nvl(docType),
                    "total", resp.getTotal(),
                    "items", items
            ));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"ragSearch\",\"error\":{\"code\":\"TOOL_ERROR\",\"message\":\""
                    + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    @Tool("""
        根据 OSS URL 获取文档全文（Markdown 格式）。
        适用场景：ragSearch 找到相关片段后，若需要阅读完整原文（如完整年报章节、完整研报），
        将 ragSearch 返回结果中的 oss_url 字段传入本工具即可。
        注意：全文可能较长，建议只在必要时调用，优先使用 ragSearch 返回的 chunk_text 片段。
        """)
    public String loadDocument(String ossUrl) {
        try {
            if (ossUrl == null || ossUrl.isBlank()) {
                return "{\"ok\":false,\"tool\":\"loadDocument\",\"error\":{\"code\":\"INVALID_ARGUMENT\",\"message\":\"ossUrl is empty\"}}";
            }
            var httpClient = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(ossUrl))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "{\"ok\":false,\"tool\":\"loadDocument\",\"error\":{\"code\":\"HTTP_ERROR\",\"message\":\"Status "
                        + response.statusCode() + "\"}}";
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("oss_url", ossUrl);
            data.put("content_length", response.body().length());
            data.put("content", response.body());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("tool", "loadDocument");
            result.put("data", data);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"loadDocument\",\"error\":{\"code\":\"TOOL_ERROR\",\"message\":\""
                    + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    private String nvl(String s) { return s == null ? "" : s; }

    private String escapeJson(String text) {
        return nvl(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
