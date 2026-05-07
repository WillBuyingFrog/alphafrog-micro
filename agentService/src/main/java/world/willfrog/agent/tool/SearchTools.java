package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.SearchEvidenceJudgeService;
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
    private final SearchEvidenceJudgeService searchEvidenceJudgeService;

    public SearchTools(ObjectMapper objectMapper, SearchEvidenceJudgeService searchEvidenceJudgeService) {
        this.objectMapper = objectMapper;
        this.searchEvidenceJudgeService = searchEvidenceJudgeService;
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
            AgentContext.WebSearchConfig runConfig = AgentContext.getWebSearchConfig();
            int limit = resolveMaxResults(runConfig.maxResults(), maxResults);
            boolean effectiveSkipHotCache = runConfig.skipHotCache() != null ? runConfig.skipHotCache() : skipHotCache;
            boolean effectiveSkipRagPrefetch = runConfig.skipRagPrefetch() != null ? runConfig.skipRagPrefetch() : skipRagPrefetch;
            WebSearchRequest req = WebSearchRequest.newBuilder()
                    .setQuery(nvl(query))
                    .setScene(nvl(scene))
                    .setBackend(firstText(runConfig.backend(), backend))
                    .setStrength(firstText(runConfig.strength(), strength))
                    .setSkipHotCache(effectiveSkipHotCache)
                    .setSkipRagPrefetch(effectiveSkipRagPrefetch)
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
            List<String> requestedEntities = AgentContext.getExtractedEntities();
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

            SearchEvidenceJudgeService.JudgeResult judgeResult = searchEvidenceJudgeService.judge(
                    query, requestedEntities, hits, citations);
            applyJudgeResult(hits, judgeResult.hits());
            applyJudgeResult(citations, judgeResult.citations());

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
            data.put("requested_entities", requestedEntities);
            data.put("relevanceJudged", judgeResult.relevanceJudged());
            data.put("relevanceJudgeError", judgeResult.relevanceJudgeError());

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

    private String firstText(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary.trim();
        }
        return nvl(fallback);
    }

    private int resolveMaxResults(Integer runConfigMaxResults, int toolMaxResults) {
        if (runConfigMaxResults != null && runConfigMaxResults > 0) {
            return runConfigMaxResults;
        }
        return toolMaxResults <= 0 ? 5 : toolMaxResults;
    }

    private void applyJudgeResult(List<Map<String, Object>> rows,
                                  List<SearchEvidenceJudgeService.ItemJudgement> judgements) {
        for (int i = 0; i < rows.size(); i++) {
            SearchEvidenceJudgeService.ItemJudgement judgement = judgements != null && i < judgements.size()
                    ? judgements.get(i)
                    : new SearchEvidenceJudgeService.ItemJudgement(
                    true, List.of(), List.of(), "搜索证据相关性 judge 未返回该条结果", false, "JUDGE_RESULT_MISSING");
            Map<String, Object> row = rows.get(i);
            row.put("entityMatch", judgement.entityMatch());
            row.put("matchedEntities", judgement.matchedEntities());
            row.put("outOfScopeEntities", judgement.outOfScopeEntities());
            row.put("relevanceWarning", judgement.relevanceWarning());
            row.put("relevanceJudged", judgement.relevanceJudged());
            row.put("relevanceJudgeError", judgement.relevanceJudgeError());
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{\"ok\":false,\"tool\":\"searchWeb\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\"failed to serialize tool result\"}}";
        }
    }
}
