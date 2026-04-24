package world.willfrog.externalinfo.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchResponse;
import world.willfrog.externalinfo.search.backend.BackendSearchResult;
import world.willfrog.externalinfo.search.backend.SearchBackend;
import world.willfrog.externalinfo.search.cache.HotKeywordCacheService;
import world.willfrog.externalinfo.search.cache.HotKeywordTtlStrategy;
import world.willfrog.externalinfo.search.slot.QueryCanonicalizer;
import world.willfrog.externalinfo.search.slot.SlotExtractor;

import java.util.List;
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
            if (!request.getSkipHotCache()) {
                HotKeywordCacheService.HotKeywordCacheResult cached =
                        hotKeywordCacheService.findCluster(slotSignature, request.getQuery());
                if (cached != null && cached.hit()) {
                    log.info("热点缓存命中: query={}, slotSignature={}", request.getQuery(), slotSignature);
                    return WebSearchResponse.newBuilder()
                            .setOk(true)
                            .setAnswer(cached.aggregatedAnswer() != null ? cached.aggregatedAnswer() : cached.answer())
                            .setCanonicalQuery(cached.canonicalQuery())
                            .setSlotSignature(slotSignature)
                            .setBackendMeta(WebSearchResponse.getDefaultInstance().getBackendMeta())
                            .build();
                }
            }

            WebSearchRequest effectiveRequest = request;

            // 3. RAG 预检（P1 实现）
            if (!request.getSkipRagPrefetch()) {
                RagPrefetcher.RagPrefetchResult ragPrefetchResult = ragPrefetcher.prefetch(
                        canonicalQuery, request.getScene(), request.getStrength());
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

            // 如果 request 中有 strength 覆盖，优先使用 request 的；否则使用 preset 的
            WebSearchRequest.Builder requestBuilder = effectiveRequest.toBuilder();
            if (!hasText(effectiveRequest.getStrength()) && resolved.preset() != null
                    && hasText(resolved.preset().getStrength())) {
                requestBuilder.setStrength(resolved.preset().getStrength());
            }
            WebSearchRequest finalRequest = requestBuilder.build();

            // 5. 调用具体 Backend
            BackendSearchResult rawResult = backend.search(finalRequest);

            // 6. 结果标准化映射
            WebSearchResponse response = resultNormalizer.normalize(rawResult, finalRequest);

            // 7. 回填 P0 预埋字段
            String resultHash = response.getResultHash();
            response = response.toBuilder()
                    .setCanonicalQuery(canonicalQuery)
                    .setSlotSignature(slotSignature)
                    .build();

            // 8. 热点缓存写入（P2）
            try {
                hotKeywordCacheService.updateClusterStats(
                        slotSignature, request.getRunId(), request.getUserId());

                boolean shouldForm = hotKeywordCacheService.shouldFormHotCluster(
                        slotSignature, request.getQuery(), request.getRunId(), request.getUserId());
                if (shouldForm && response.getOk()) {
                    long ttlSeconds = hotKeywordTtlStrategy.computeTtl(
                            canonicalQuery, request.getScene(),
                            new HotKeywordTtlStrategy.SlotResult(Map.of(
                                    "assetCode", nvl(slots.assetCode()),
                                    "assetName", nvl(slots.assetName()),
                                    "timeRange", nvl(slots.timeRange()),
                                    "marketScope", nvl(slots.marketScope())
                            )));
                    String intentTemplate = queryCanonicalizer.canonicalize(request.getQuery(), slots);
                    String answer = response.getAnswer();
                    String aggregatedAnswer = null;
                    if (answer != null && !answer.isBlank()) {
                        aggregatedAnswer = answer;
                    }
                    hotKeywordCacheService.writeCluster(
                            canonicalQuery,
                            intentTemplate,
                            slotSignature,
                            request.getScene(),
                            new world.willfrog.externalinfo.search.cache.AnswerAggregator.BackendAnswer(
                                    response.getBackendMeta() != null ? response.getBackendMeta().getBackend() : "unknown",
                                    answer,
                                    List.of(),
                                    resultHash,
                                    java.time.Instant.now().toString()
                            ),
                            aggregatedAnswer,
                            ttlSeconds
                    );
                    log.info("写入热点缓存: query={}, slotSignature={}, ttl={}s",
                            canonicalQuery, slotSignature, ttlSeconds);
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
