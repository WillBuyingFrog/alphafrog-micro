package world.willfrog.externalinfo.search.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchAnswerMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchBackendMeta;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchCitation;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchHit;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRagPrefetch;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchResponse;
import world.willfrog.externalinfo.retrieval.EmbeddingApiClient;
import world.willfrog.externalinfo.search.backend.BackendCitation;
import world.willfrog.externalinfo.search.backend.BackendSearchResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 热点簇读写、聚类判定与统计更新服务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HotKeywordCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AnswerAggregator answerAggregator;
    private final EmbeddingApiClient embeddingApiClient;

    private static final String CLUSTER_KEY_PREFIX = "externalinfo:hot-cluster:";
    private static final String WINDOW_COUNT_PREFIX = "externalinfo:hot-window:";
    private static final String LOCK_PREFIX = "externalinfo:hot-lock:";
    private static final String GLOBAL_WINDOW_COUNT_KEY = "externalinfo:hot-window:global:count";

    private static final int MIN_QUERY_COUNT = 3;
    private static final int MIN_UNIQUE_RUNS = 2;
    private static final int MIN_UNIQUE_USERS = 1;
    private static final double MIN_STABILITY = 0.6;
    private static final double MIN_EMBEDDING_SIMILARITY = 0.86;
    private static final int WINDOW_SECONDS = 300;
    private static final int LOCK_SECONDS = 5;

    public HotKeywordCacheResult findCluster(String cacheKey, String query) {
        try {
            String clusterKey = clusterKey(cacheKey);
            String payload = (String) redisTemplate.opsForHash().get(clusterKey, "payload");
            if (payload == null || payload.isBlank()) {
                return null;
            }
            ClusterData cluster = objectMapper.readValue(payload, ClusterData.class);
            if (cluster == null || cluster.getResponse() == null || cluster.getQueryEmbedding() == null) {
                return null;
            }
            List<Float> queryEmbedding = embedForCache(query);
            if (queryEmbedding == null || cosineSimilarity(queryEmbedding, cluster.getQueryEmbedding()) < MIN_EMBEDDING_SIMILARITY) {
                return null;
            }
            cluster.setLastAccessedAt(Instant.now().toString());
            redisTemplate.opsForHash().put(clusterKey, "payload", objectMapper.writeValueAsString(cluster));
            Long ttlSeconds = redisTemplate.getExpire(clusterKey, TimeUnit.SECONDS);
            return new HotKeywordCacheResult(true, toResponse(cluster.getResponse()), ttlSeconds == null ? -1 : Math.max(0, ttlSeconds));
        } catch (Exception e) {
            log.warn("查找热点簇失败, cacheKey={}", cacheKey, e);
            return null;
        }
    }

    public boolean shouldFormHotCluster(String cacheKey, String query, String runId, String userId) {
        try {
            String keyHash = sha256(cacheKey);
            int queryCount = parseInt(redisTemplate.opsForValue().get(WINDOW_COUNT_PREFIX + keyHash + ":count"));
            if (queryCount < MIN_QUERY_COUNT) {
                return false;
            }
            Long runSize = redisTemplate.opsForSet().size(WINDOW_COUNT_PREFIX + keyHash + ":runs");
            if ((runSize == null ? 0 : runSize.intValue()) < MIN_UNIQUE_RUNS) {
                return false;
            }
            Long userSize = redisTemplate.opsForSet().size(WINDOW_COUNT_PREFIX + keyHash + ":users");
            if ((userSize == null ? 0 : userSize.intValue()) < MIN_UNIQUE_USERS) {
                return false;
            }
            int globalCount = parseInt(redisTemplate.opsForValue().get(GLOBAL_WINDOW_COUNT_KEY));
            return globalCount > 0 && ((double) queryCount / globalCount) >= MIN_STABILITY;
        } catch (Exception e) {
            log.warn("判定热点簇失败, cacheKey={}", cacheKey, e);
            return false;
        }
    }

    public void writeCluster(String cacheKey,
                             String canonicalQuery,
                             String intentTemplate,
                             String query,
                             String scene,
                             WebSearchResponse response,
                             long ttlSeconds) {
        if (response == null || ttlSeconds <= 0) {
            return;
        }
        List<Float> embedding = embedForCache(query);
        if (embedding == null) {
            log.debug("热点缓存写入跳过：embedding 不可用, cacheKey={}", cacheKey);
            return;
        }
        String clusterKey = clusterKey(cacheKey);
        String lockKey = LOCK_PREFIX + sha256(cacheKey);
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            Instant now = Instant.now();
            String payload = (String) redisTemplate.opsForHash().get(clusterKey, "payload");
            ClusterData cluster = payload == null || payload.isBlank()
                    ? new ClusterData()
                    : objectMapper.readValue(payload, ClusterData.class);
            if (cluster == null) {
                cluster = new ClusterData();
            }
            if (cluster.getCreatedAt() == null) {
                cluster.setCreatedAt(now.toString());
            }
            cluster.setCanonicalQuery(canonicalQuery);
            cluster.setIntentTemplate(intentTemplate);
            cluster.setCacheKey(cacheKey);
            cluster.setScene(scene);
            cluster.setExpiresAt(now.plusSeconds(ttlSeconds).toString());
            cluster.setLastAccessedAt(now.toString());
            cluster.setQueryEmbedding(embedding);
            cluster.setResponse(fromResponse(response));
            cluster.setQueryCount(Math.max(1, cluster.getQueryCount()));
            if (cluster.getUniqueRuns() == null) {
                cluster.setUniqueRuns(new ArrayList<>());
            }
            if (cluster.getUniqueUsers() == null) {
                cluster.setUniqueUsers(new ArrayList<>());
            }
            if (cluster.getAnswers() == null) {
                cluster.setAnswers(new ArrayList<>());
            }
            seedClusterStatsFromWindow(cluster, cacheKey);
            AnswerAggregator.BackendAnswer answer = new AnswerAggregator.BackendAnswer(
                    response.getBackendMeta().getBackend(),
                    response.getAnswer(),
                    response.getCitationsList().stream()
                            .map(c -> new BackendCitation(c.getIndex(), c.getUrl(), c.getTitle()))
                            .toList(),
                    response.getResultHash(),
                    now.toString()
            );
            if (cluster.getAnswers().stream().noneMatch(a -> response.getResultHash().equals(a.resultHash()))) {
                cluster.getAnswers().add(answer);
            }
            if (cluster.getAnswers().size() > 1) {
                CachedResponse cachedResponse = cluster.getResponse();
                cachedResponse.setAnswer(answerAggregator.aggregate(canonicalQuery, cluster.getAnswers()));
            }
            redisTemplate.opsForHash().put(clusterKey, "payload", objectMapper.writeValueAsString(cluster));
            redisTemplate.expire(clusterKey, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入热点簇失败, cacheKey={}", cacheKey, e);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    public void updateClusterStats(String cacheKey, String runId, String userId) {
        try {
            String keyHash = sha256(cacheKey);
            String countKey = WINDOW_COUNT_PREFIX + keyHash + ":count";
            String runsKey = WINDOW_COUNT_PREFIX + keyHash + ":runs";
            String usersKey = WINDOW_COUNT_PREFIX + keyHash + ":users";

            expireOnFirstIncrement(countKey, redisTemplate.opsForValue().increment(countKey));
            expireOnFirstIncrement(GLOBAL_WINDOW_COUNT_KEY, redisTemplate.opsForValue().increment(GLOBAL_WINDOW_COUNT_KEY));
            if (runId != null && !runId.isBlank()) {
                redisTemplate.opsForSet().add(runsKey, runId);
                redisTemplate.expire(runsKey, WINDOW_SECONDS, TimeUnit.SECONDS);
            }
            if (userId != null && !userId.isBlank()) {
                redisTemplate.opsForSet().add(usersKey, userId);
                redisTemplate.expire(usersKey, WINDOW_SECONDS, TimeUnit.SECONDS);
            }
            updateExistingClusterStats(cacheKey, runId, userId);
        } catch (Exception e) {
            log.warn("更新热点簇统计失败, cacheKey={}", cacheKey, e);
        }
    }

    public void addAnswer(String cacheKey, BackendSearchResult result) {
        // 当前主流程在 writeCluster 中写入完整响应；保留方法兼容旧调用点。
    }

    private void updateExistingClusterStats(String cacheKey, String runId, String userId) throws Exception {
        String clusterKey = clusterKey(cacheKey);
        String lockKey = LOCK_PREFIX + sha256(cacheKey);
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            String payload = (String) redisTemplate.opsForHash().get(clusterKey, "payload");
            if (payload == null || payload.isBlank()) {
                return;
            }
            ClusterData cluster = objectMapper.readValue(payload, ClusterData.class);
            if (cluster == null) {
                return;
            }
            cluster.setQueryCount(cluster.getQueryCount() + 1);
            addUnique(cluster.getUniqueRuns(), runId);
            addUnique(cluster.getUniqueUsers(), userId);
            cluster.setLastAccessedAt(Instant.now().toString());
            redisTemplate.opsForHash().put(clusterKey, "payload", objectMapper.writeValueAsString(cluster));
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private void expireOnFirstIncrement(String key, Long count) {
        if (count != null && count == 1) {
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void addUnique(List<String> values, String value) {
        if (values != null && value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private void seedClusterStatsFromWindow(ClusterData cluster, String cacheKey) {
        String keyHash = sha256(cacheKey);
        int windowCount = parseInt(redisTemplate.opsForValue().get(WINDOW_COUNT_PREFIX + keyHash + ":count"));
        cluster.setQueryCount(Math.max(cluster.getQueryCount(), Math.max(1, windowCount)));
        mergeUnique(cluster.getUniqueRuns(), redisTemplate.opsForSet().members(WINDOW_COUNT_PREFIX + keyHash + ":runs"));
        mergeUnique(cluster.getUniqueUsers(), redisTemplate.opsForSet().members(WINDOW_COUNT_PREFIX + keyHash + ":users"));
    }

    private void mergeUnique(List<String> target, Set<String> values) {
        if (target == null || values == null || values.isEmpty()) {
            return;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(target);
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                merged.add(value);
            }
        }
        target.clear();
        target.addAll(merged);
    }

    private List<Float> embedForCache(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return embeddingApiClient.embed(text);
        } catch (Exception e) {
            log.debug("热点缓存 embedding 不可用，跳过缓存路径: {}", e.getMessage());
            return null;
        }
    }

    private double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double av = a.get(i);
            double bv = b.get(i);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        if (normA <= 0 || normB <= 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private CachedResponse fromResponse(WebSearchResponse response) {
        CachedResponse cached = new CachedResponse();
        cached.setHits(response.getHitsList().stream().map(h -> new CachedHit(
                h.getTitle(), h.getUrl(), h.getSnippet(), h.getSource(), h.getPublishedDate(), h.getScore()
        )).toList());
        cached.setBackendMeta(new CachedBackendMeta(
                response.getBackendMeta().getBackend(),
                response.getBackendMeta().getModelOrStrength(),
                response.getBackendMeta().getCostEstimateMs(),
                response.getBackendMeta().getRawQuerySent()
        ));
        cached.setAnswer(response.getAnswer());
        cached.setCitations(response.getCitationsList().stream().map(c -> new CachedCitation(
                c.getIndex(), c.getUrl(), c.getTitle()
        )).toList());
        cached.setAnswerMeta(new CachedAnswerMeta(
                response.getAnswerMeta().getAnswerType(),
                response.getAnswerMeta().getModelUsed()
        ));
        cached.setRagPrefetch(new CachedRagPrefetch(
                response.getRagPrefetch().getUsed(),
                response.getRagPrefetch().getRelevanceScore(),
                response.getRagPrefetch().getRagSummary()
        ));
        cached.setCanonicalQuery(response.getCanonicalQuery());
        cached.setSlotSignature(response.getSlotSignature());
        cached.setResultHash(response.getResultHash());
        return cached;
    }

    private WebSearchResponse toResponse(CachedResponse cached) {
        WebSearchResponse.Builder builder = WebSearchResponse.newBuilder().setOk(true);
        if (cached.getHits() != null) {
            for (CachedHit hit : cached.getHits()) {
                builder.addHits(WebSearchHit.newBuilder()
                        .setTitle(nvl(hit.title()))
                        .setUrl(nvl(hit.url()))
                        .setSnippet(nvl(hit.snippet()))
                        .setSource(nvl(hit.source()))
                        .setPublishedDate(nvl(hit.publishedDate()))
                        .setScore(hit.score())
                        .build());
            }
        }
        CachedBackendMeta meta = cached.getBackendMeta();
        if (meta != null) {
            builder.setBackendMeta(WebSearchBackendMeta.newBuilder()
                    .setBackend(nvl(meta.backend()))
                    .setModelOrStrength(nvl(meta.modelOrStrength()))
                    .setCostEstimateMs(meta.costEstimateMs())
                    .setRawQuerySent(nvl(meta.rawQuerySent()))
                    .build());
        }
        builder.setAnswer(nvl(cached.getAnswer()));
        if (cached.getCitations() != null) {
            for (CachedCitation citation : cached.getCitations()) {
                builder.addCitations(WebSearchCitation.newBuilder()
                        .setIndex(citation.index())
                        .setUrl(nvl(citation.url()))
                        .setTitle(nvl(citation.title()))
                        .build());
            }
        }
        CachedAnswerMeta answerMeta = cached.getAnswerMeta();
        if (answerMeta != null) {
            builder.setAnswerMeta(WebSearchAnswerMeta.newBuilder()
                    .setAnswerType(nvl(answerMeta.answerType()))
                    .setModelUsed(nvl(answerMeta.modelUsed()))
                    .build());
        }
        CachedRagPrefetch rag = cached.getRagPrefetch();
        if (rag != null) {
            builder.setRagPrefetch(WebSearchRagPrefetch.newBuilder()
                    .setUsed(rag.used())
                    .setRelevanceScore(rag.relevanceScore())
                    .setRagSummary(nvl(rag.ragSummary()))
                    .build());
        }
        builder.setCanonicalQuery(nvl(cached.getCanonicalQuery()));
        builder.setSlotSignature(nvl(cached.getSlotSignature()));
        builder.setResultHash(nvl(cached.getResultHash()));
        return builder.build();
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String clusterKey(String cacheKey) {
        return CLUSTER_KEY_PREFIX + sha256(cacheKey);
    }

    private String sha256(String input) {
        String text = input == null ? "" : input;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    public record HotKeywordCacheResult(boolean hit, WebSearchResponse response, long ttlRemainingSeconds) {
    }

    @Data
    private static class ClusterData {
        private String canonicalQuery;
        private String intentTemplate;
        private String cacheKey;
        private String scene;
        private String createdAt;
        private String expiresAt;
        private int queryCount;
        private List<String> uniqueRuns = new ArrayList<>();
        private List<String> uniqueUsers = new ArrayList<>();
        private List<Float> queryEmbedding;
        private List<AnswerAggregator.BackendAnswer> answers = new ArrayList<>();
        private CachedResponse response;
        private String lastAccessedAt;
    }

    @Data
    private static class CachedResponse {
        private List<CachedHit> hits = new ArrayList<>();
        private CachedBackendMeta backendMeta;
        private String answer;
        private List<CachedCitation> citations = new ArrayList<>();
        private CachedAnswerMeta answerMeta;
        private CachedRagPrefetch ragPrefetch;
        private String canonicalQuery;
        private String slotSignature;
        private String resultHash;
    }

    private record CachedHit(String title, String url, String snippet, String source, String publishedDate, float score) {
    }

    private record CachedCitation(int index, String url, String title) {
    }

    private record CachedBackendMeta(String backend, String modelOrStrength, int costEstimateMs, String rawQuerySent) {
    }

    private record CachedAnswerMeta(String answerType, String modelUsed) {
    }

    private record CachedRagPrefetch(boolean used, float relevanceScore, String ragSummary) {
    }
}
