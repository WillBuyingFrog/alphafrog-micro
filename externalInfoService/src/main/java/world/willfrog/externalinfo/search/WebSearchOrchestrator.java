package world.willfrog.externalinfo.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRagPrefetch;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchResponse;
import world.willfrog.externalinfo.search.backend.BackendSearchResult;
import world.willfrog.externalinfo.search.backend.SearchBackend;
import world.willfrog.externalinfo.search.cache.HotKeywordCacheService;
import world.willfrog.externalinfo.search.cache.HotKeywordTtlStrategy;
import world.willfrog.externalinfo.search.slot.QueryCanonicalizer;
import world.willfrog.externalinfo.search.slot.SlotExtractor;

import java.util.Map;

/**
 * Web 搜索统一编排器。
 * P1 阶段接入 SlotExtractor、QueryCanonicalizer、RagPrefetcher。
 * P2 阶段接入 HotKeywordCacheService、HotKeywordTtlStrategy。
 * 编排流程：
 *   1. Query 规范化（槽位提取 + 意图模板）
 *   2. 热点缓存检查（P2）
 *   3. RAG 预检（P1）
 *   4. Backend 选择与参数映射
 *   5. 调用具体 Backend
 *   6. 结果标准化映射
 *   7. 回填 P0 预埋字段
 *   8. 热点缓存写入（P2）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSearchOrchestrator {

    private final BackendRouter backendRouter;
    private final ResultNormalizer resultNormalizer;
    private final SlotExtractor slotExtractor;
    private final QueryCanonicalizer queryCanonicalizer;
    private final RagPrefetcher ragPrefetcher;
    private final HotKeywordCacheService hotKeywordCacheService;
    private final HotKeywordTtlStrategy hotKeywordTtlStrategy;

    public WebSearchResponse search(WebSearchRequest request) {
        try {
            // 1. Query 规范化：提取槽位并生成意图模板
            SlotExtractor.SlotResult slots = slotExtractor.extract(request.getQuery());
            String canonicalQuery = queryCanonicalizer.canonicalize(request.getQuery(), slots);

            // 2. 热点缓存检查（P2）
            String slotSignature = slotExtractor.computeSlotSignature(slots);
            String cacheKey = buildCacheKey(canonicalQuery, slotSignature, slotExtractor.computeTimeBucket(slots));
            if (!request.getSkipHotCache()) {
                HotKeywordCacheService.HotKeywordCacheResult cached =
                        hotKeywordCacheService.findCluster(cacheKey, request.getQuery());
                if (cached != null && cached.hit()) {
                    log.info("热点缓存命中: query={}, cacheKey={}", request.getQuery(), cacheKey);
                    return cached.response();
                }
            }

            WebSearchRequest effectiveRequest = request;
            RagPrefetcher.RagPrefetchResult ragPrefetchResult = RagPrefetcher.RagPrefetchResult.noHit();

            // 3. RAG 预检（P1 实现）
            if (!request.getSkipRagPrefetch()) {
                ragPrefetchResult = ragPrefetcher.prefetch(
                        request.getQuery(), request.getScene(), request.getStrength());
                if (ragPrefetchResult.adjustedStrength() != null) {
                    effectiveRequest = request.toBuilder()
                            .setStrength(ragPrefetchResult.adjustedStrength())
                            .build();
                    log.debug("RAG 预检触发 strength 降级: {} -> {}",
                            request.getStrength(), ragPrefetchResult.adjustedStrength());
                }
            }

            // 4. Backend 选择与参数映射
            BackendRouter.ResolvedBackend resolved = backendRouter.resolve(effectiveRequest);
            SearchBackend backend = resolved.backend();
            WebSearchExecutionContext context = resolved.context();

            // 5. 调用具体 Backend
            BackendSearchResult rawResult = backend.search(context);

            // 6. 结果标准化映射
            WebSearchResponse response = resultNormalizer.normalize(rawResult, effectiveRequest);

            // 7. 回填 P0 预埋字段
            response = response.toBuilder()
                    .setCanonicalQuery(canonicalQuery)
                    .setSlotSignature(slotSignature)
                    .setRagPrefetch(WebSearchRagPrefetch.newBuilder()
                            .setUsed(ragPrefetchResult.used())
                            .setRelevanceScore(ragPrefetchResult.relevanceScore())
                            .setRagSummary(nvl(ragPrefetchResult.ragSummary()))
                            .build())
                    .build();

            // 8. 热点缓存写入（P2）
            try {
                hotKeywordCacheService.updateClusterStats(
                        cacheKey, request.getRunId(), request.getUserId());

                boolean shouldForm = hotKeywordCacheService.shouldFormHotCluster(
                        cacheKey, request.getQuery(), request.getRunId(), request.getUserId());
                if (shouldForm && response.getOk()) {
                    long ttlSeconds = hotKeywordTtlStrategy.computeTtl(
                            canonicalQuery, request.getScene(),
                            new HotKeywordTtlStrategy.SlotResult(Map.of(
                                    "assetCode", nvl(slots.assetCode()),
                                    "assetName", nvl(slots.assetName()),
                                    "timeRange", nvl(slots.timeRange()),
                                    "marketScope", nvl(slots.marketScope())
                            )));
                    hotKeywordCacheService.writeCluster(
                            cacheKey,
                            canonicalQuery,
                            canonicalQuery,
                            request.getQuery(),
                            request.getScene(),
                            response,
                            ttlSeconds
                    );
                    log.info("写入热点缓存: query={}, cacheKey={}, ttl={}s",
                            canonicalQuery, cacheKey, ttlSeconds);
                }
            } catch (Exception cacheEx) {
                log.warn("热点缓存写入失败，不影响主流程", cacheEx);
            }

            return response;
        } catch (Exception e) {
            log.error("WebSearch 编排失败", e);
            return WebSearchResponse.newBuilder()
                    .setOk(false)
                    .setErrorCode("ORCHESTRATION_ERROR")
                    .setErrorMessage(e.getMessage())
                    .build();
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String buildCacheKey(String intentTemplate, String slotSignature, String timeBucket) {
        return "intent=" + nvl(intentTemplate)
                + "|slots=" + nvl(slotSignature)
                + "|time_bucket=" + nvl(timeBucket);
    }
}
