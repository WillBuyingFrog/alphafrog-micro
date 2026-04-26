package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.alphafrogmicro.externalinfo.idl.ExternalInfoDubboService;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchResponse;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchHit;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchCitation;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchBackendMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRagPrefetch;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchAnswerMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SearchTools {

    @DubboReference(timeout = 60000, retries = 0)
    private ExternalInfoDubboService externalInfoDubboService;

    private final ObjectMapper objectMapper;

    public SearchTools(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool("""
        通用网络搜索工具，用于查询互联网上的实时信息、新闻、资料等。

        适用场景：
          - 查询最新市场动态、财经新闻、政策变化
          - 获取某家公司或行业的最新公开信息
          - 查询实时数据、排行榜、事件进展等时效性强的内容
          - 需要联网获取当前时间节点的信息，而本地知识库无法覆盖时

        与 ragSearch 的区别：
          - searchWeb：面向互联网实时信息，返回结构化搜索结果和 AI 综合答案
          - ragSearch：面向本地知识库（公告/研报原文），返回向量检索片段

        参数说明：
          query            - 搜索查询文本，必填
          scene            - 搜索场景："general"（通用）| "finance"（财经）| "news"（新闻），可选，默认"general"
          backend          - 后端覆盖："perplexity" | "tavily" | "exa" | ""（自动选择），可选
          strength         - 搜索强度档位，与 backend 相关，可选
          skipHotCache     - 是否跳过热点缓存，默认 false，可选
          skipRagPrefetch  - 是否跳过 RAG 预检，默认 false，可选
          timeRangeStart   - 时间范围起始（ISO 8601），可选
          timeRangeEnd     - 时间范围结束（ISO 8601），可选
          maxResults       - 最大返回结果数（默认 5），可选
        """)
    public String searchWeb(@P(value = "搜索查询文本，必填", required = true) String query,
                            @P(value = "搜索场景：general、finance 或 news，可选", required = false) String scene,
                            @P(value = "后端或 preset 覆盖：perplexity、tavily、exa 或 preset 名，可选", required = false) String backend,
                            @P(value = "搜索强度档位，可选", required = false) String strength,
                            @P(value = "是否跳过热点缓存，可选，默认 false", required = false) boolean skipHotCache,
                            @P(value = "是否跳过 RAG 预检，可选，默认 false", required = false) boolean skipRagPrefetch,
                            @P(value = "时间范围起始 ISO 8601，可选", required = false) String timeRangeStart,
                            @P(value = "时间范围结束 ISO 8601，可选", required = false) String timeRangeEnd,
                            @P(value = "最大返回结果数，可选，默认 5", required = false) int maxResults) {
        try {
            int limit = maxResults <= 0 ? 5 : maxResults;
            WebSearchRequest req = WebSearchRequest.newBuilder()
                    .setQuery(nvl(query))
                    .setScene(nvl(scene))
                    .setBackend(nvl(backend))
                    .setStrength(nvl(strength))
                    .setSkipHotCache(skipHotCache)
                    .setSkipRagPrefetch(skipRagPrefetch)
                    .setTimeRangeStart(nvl(timeRangeStart))
                    .setTimeRangeEnd(nvl(timeRangeEnd))
                    .setMaxResults(limit)
                    .setRunId(nvl(AgentContext.getRunId()))
                    .setUserId(nvl(AgentContext.getUserId()))
                    .build();

            WebSearchResponse resp = externalInfoDubboService.webSearch(req);

            // 如果 Dubbo 层返回 ok=false，则按错误处理
            if (!resp.getOk()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ok", false);
                payload.put("tool", "searchWeb");
                payload.put("error", Map.of(
                        "code", nvl(resp.getErrorCode()),
                        "message", nvl(resp.getErrorMessage())
                ));
                return objectMapper.writeValueAsString(payload);
            }

            // 组装 hits
            List<Map<String, Object>> hits = new ArrayList<>();
            for (WebSearchHit hit : resp.getHitsList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("title", hit.getTitle());
                row.put("url", hit.getUrl());
                row.put("snippet", hit.getSnippet());
                row.put("source", hit.getSource());
                row.put("published_date", hit.getPublishedDate());
                row.put("score", hit.getScore());
                hits.add(row);
            }

            // 组装 citations
            List<Map<String, Object>> citations = new ArrayList<>();
            for (WebSearchCitation citation : resp.getCitationsList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", citation.getIndex());
                row.put("url", citation.getUrl());
                row.put("title", citation.getTitle());
                citations.add(row);
            }

            // 组装 backend_meta
            Map<String, Object> backendMeta = new LinkedHashMap<>();
            WebSearchBackendMeta bm = resp.getBackendMeta();
            if (bm != null) {
                backendMeta.put("backend", bm.getBackend());
                backendMeta.put("model_or_strength", bm.getModelOrStrength());
                backendMeta.put("cost_estimate_ms", bm.getCostEstimateMs());
                backendMeta.put("raw_query_sent", bm.getRawQuerySent());
            }

            // 组装 rag_prefetch
            Map<String, Object> ragPrefetch = new LinkedHashMap<>();
            WebSearchRagPrefetch rp = resp.getRagPrefetch();
            if (rp != null) {
                ragPrefetch.put("used", rp.getUsed());
                ragPrefetch.put("relevance_score", rp.getRelevanceScore());
                ragPrefetch.put("rag_summary", rp.getRagSummary());
            }

            Map<String, Object> answerMeta = new LinkedHashMap<>();
            WebSearchAnswerMeta am = resp.getAnswerMeta();
            if (am != null) {
                answerMeta.put("answer_type", am.getAnswerType());
                answerMeta.put("model_used", am.getModelUsed());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", nvl(query));
            data.put("scene", nvl(scene));
            data.put("hits", hits);
            data.put("answer", resp.getAnswer());
            data.put("citations", citations);
            data.put("answer_meta", answerMeta);
            data.put("backend_meta", backendMeta);
            data.put("rag_prefetch", ragPrefetch);
            data.put("canonical_query", resp.getCanonicalQuery());
            data.put("slot_signature", resp.getSlotSignature());
            data.put("result_hash", resp.getResultHash());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("tool", "searchWeb");
            payload.put("data", data);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return writeJson(Map.of(
                    "ok", false,
                    "tool", "searchWeb",
                    "error", Map.of(
                            "code", "TOOL_ERROR",
                            "message", nvl(e.getMessage())
                    )
            ));
        }
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{\"ok\":false,\"tool\":\"searchWeb\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\"failed to serialize tool result\"}}";
        }
    }
}
