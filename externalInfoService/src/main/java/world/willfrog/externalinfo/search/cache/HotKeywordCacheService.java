package world.willfrog.externalinfo.search.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import world.willfrog.externalinfo.search.backend.BackendCitation;
import world.willfrog.externalinfo.search.backend.BackendSearchResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    private static final String CLUSTER_KEY_PREFIX = "externalinfo:hot-cluster:";
    private static final String QUERY_IDX_PREFIX = "externalinfo:hot-query-idx:";
    private static final String WINDOW_COUNT_PREFIX = "externalinfo:hot-window:";
    private static final String GLOBAL_WINDOW_COUNT_KEY = "externalinfo:hot-window:global:count";

    private static final int MIN_QUERY_COUNT = 3;
    private static final int MIN_UNIQUE_RUNS = 2;
    private static final int MIN_UNIQUE_USERS = 1;
    private static final double MIN_STABILITY = 0.6;
    private static final int WINDOW_SECONDS = 300; // 5 分钟窗口

    /**
     * 按槽位签名查 Redis 热点簇。
     * 若命中，返回缓存的答案和元数据；未命中返回 null。
     *
     * @param slotSignature 槽位签名
     * @param query         原始查询（用于更新 query 索引映射）
     * @return 缓存结果，未命中返回 null
     */
    public HotKeywordCacheResult findCluster(String slotSignature, String query) {
        try {
            String sigHash = sha256(slotSignature);
            String clusterKey = CLUSTER_KEY_PREFIX + sigHash;

            String payload = (String) redisTemplate.opsForHash().get(clusterKey, "payload");
            if (payload == null || payload.isBlank()) {
                return null;
            }

            ClusterData cluster = objectMapper.readValue(payload, ClusterData.class);
            if (cluster == null) {
                return null;
            }

            // 更新最后访问时间
            cluster.setLastAccessedAt(Instant.now().toString());
            redisTemplate.opsForHash().put(clusterKey, "payload", objectMapper.writeValueAsString(cluster));

            // 可选：更新 query 索引
            if (query != null && !query.isBlank()) {
                try {
                    String queryHash = sha256(query);
                    String queryIdxKey = QUERY_IDX_PREFIX + queryHash;
                    Long ttl = redisTemplate.getExpire(clusterKey, TimeUnit.SECONDS);
                    if (ttl != null && ttl > 0) {
                        redisTemplate.opsForValue().set(queryIdxKey, sigHash, ttl, TimeUnit.SECONDS);
                    }
                } catch (Exception ignored) {
                    // query 索引更新失败不影响主流程
                }
            }

            // 计算剩余 TTL
            Long ttlSeconds = redisTemplate.getExpire(clusterKey, TimeUnit.SECONDS);
            long ttlRemaining = ttlSeconds == null ? -1 : Math.max(0, ttlSeconds);

            // 优先取第一个 backend 的答案与引用
            String answer = null;
            List<BackendCitation> citations = List.of();
            if (cluster.getAnswers() != null && !cluster.getAnswers().isEmpty()) {
                AnswerAggregator.BackendAnswer first = cluster.getAnswers().get(0);
                answer = first.answer();
                citations = first.citations() != null ? first.citations() : List.of();
            }

            return new HotKeywordCacheResult(
                    true,
                    cluster.getCanonicalQuery(),
                    answer,
                    citations,
                    cluster.getAggregatedAnswer(),
                    ttlRemaining
            );
        } catch (Exception e) {
            log.warn("查找热点簇失败, slotSignature={}", slotSignature, e);
            return null;
        }
    }

    /**
     * 多维度联合判定是否应形成热点簇。
     * 四个维度同时满足返回 true：
     * 1. 短时间窗口查询量 >= MIN_QUERY_COUNT
     * 2. 不同 run 数 >= MIN_UNIQUE_RUNS
     * 3. 不同 user 数 >= MIN_UNIQUE_USERS
     * 4. 槽位稳定度（同一 signature 查询占比）>= MIN_STABILITY
     *
     * @param slotSignature 槽位签名
     * @param query         原始查询
     * @param runId         本次运行 ID
     * @param userId        用户 ID
     * @return 是否满足热点簇条件
     */
    public boolean shouldFormHotCluster(String slotSignature, String query, String runId, String userId) {
        try {
            String sigHash = sha256(slotSignature);

            // 1. 短时间窗口查询量
            String countKey = WINDOW_COUNT_PREFIX + sigHash + ":count";
            String countStr = redisTemplate.opsForValue().get(countKey);
            int queryCount = parseInt(countStr);
            if (queryCount < MIN_QUERY_COUNT) {
                return false;
            }

            // 2. 不同 run 数
            String runsKey = WINDOW_COUNT_PREFIX + sigHash + ":runs";
            Long runSize = redisTemplate.opsForSet().size(runsKey);
            int uniqueRuns = runSize == null ? 0 : runSize.intValue();
            if (uniqueRuns < MIN_UNIQUE_RUNS) {
                return false;
            }

            // 3. 不同 user 数
            String usersKey = WINDOW_COUNT_PREFIX + sigHash + ":users";
            Long userSize = redisTemplate.opsForSet().size(usersKey);
            int uniqueUsers = userSize == null ? 0 : userSize.intValue();
            if (uniqueUsers < MIN_UNIQUE_USERS) {
                return false;
            }

            // 4. 槽位稳定度 = 该 signature 窗口查询量 / 全局窗口查询量
            String globalCountStr = redisTemplate.opsForValue().get(GLOBAL_WINDOW_COUNT_KEY);
            int globalCount = parseInt(globalCountStr);
            if (globalCount <= 0) {
                return false;
            }
            double stability = (double) queryCount / globalCount;
            if (stability < MIN_STABILITY) {
                return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("判定热点簇失败, slotSignature={}", slotSignature, e);
            return false;
        }
    }

    /**
     * 将新热点簇写入 Redis。
     *
     * @param canonicalQuery    规范化查询
     * @param intentTemplate    意图模板
     * @param slotSignature     槽位签名
     * @param scene             场景
     * @param answer            首个 backend 答案
     * @param aggregatedAnswer  聚合答案（可为 null）
     * @param ttlSeconds        TTL（秒）
     */
    public void writeCluster(String canonicalQuery,
                             String intentTemplate,
                             String slotSignature,
                             String scene,
                             AnswerAggregator.BackendAnswer answer,
                             String aggregatedAnswer,
                             long ttlSeconds) {
        try {
            String sigHash = sha256(slotSignature);
            String clusterKey = CLUSTER_KEY_PREFIX + sigHash;
            String queryHash = sha256(canonicalQuery);
            String queryIdxKey = QUERY_IDX_PREFIX + queryHash;

            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(ttlSeconds);

            ClusterData cluster = new ClusterData();
            cluster.setCanonicalQuery(canonicalQuery);
            cluster.setIntentTemplate(intentTemplate);
            cluster.setSlotSignature(slotSignature);
            cluster.setScene(scene);
            cluster.setCreatedAt(now.toString());
            cluster.setExpiresAt(expiresAt.toString());
            cluster.setQueryCount(1);
            cluster.setUniqueRuns(new ArrayList<>());
            cluster.setUniqueUsers(new ArrayList<>());
            cluster.setAnswers(new ArrayList<>());
            if (answer != null) {
                cluster.getAnswers().add(answer);
            }
            cluster.setAggregatedAnswer(aggregatedAnswer);
            cluster.setLastAccessedAt(now.toString());

            String payload = objectMapper.writeValueAsString(cluster);
            redisTemplate.opsForHash().put(clusterKey, "payload", payload);
            redisTemplate.expire(clusterKey, ttlSeconds, TimeUnit.SECONDS);

            // 写入 query 索引，TTL 与簇保持一致
            redisTemplate.opsForValue().set(queryIdxKey, sigHash, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入热点簇失败, slotSignature={}", slotSignature, e);
        }
    }

    /**
     * 每次查询时更新统计信息。
     * 同时更新 Redis 窗口计数器与已有的热点簇内部统计。
     *
     * @param slotSignature 槽位签名
     * @param runId         本次运行 ID
     * @param userId        用户 ID
     */
    public void updateClusterStats(String slotSignature, String runId, String userId) {
        try {
            String sigHash = sha256(slotSignature);
            String countKey = WINDOW_COUNT_PREFIX + sigHash + ":count";
            String runsKey = WINDOW_COUNT_PREFIX + sigHash + ":runs";
            String usersKey = WINDOW_COUNT_PREFIX + sigHash + ":users";

            // 增加 signature 窗口计数
            Long count = redisTemplate.opsForValue().increment(countKey);
            if (count != null && count == 1) {
                redisTemplate.expire(countKey, WINDOW_SECONDS, TimeUnit.SECONDS);
            }

            // 增加全局窗口计数
            Long globalCount = redisTemplate.opsForValue().increment(GLOBAL_WINDOW_COUNT_KEY);
            if (globalCount != null && globalCount == 1) {
                redisTemplate.expire(GLOBAL_WINDOW_COUNT_KEY, WINDOW_SECONDS, TimeUnit.SECONDS);
            }

            // 记录 runId
            Long addedRun = redisTemplate.opsForSet().add(runsKey, nvl(runId));
            if (addedRun != null && addedRun == 1) {
                redisTemplate.expire(runsKey, WINDOW_SECONDS, TimeUnit.SECONDS);
            }

            // 记录 userId
            Long addedUser = redisTemplate.opsForSet().add(usersKey, nvl(userId));
            if (addedUser != null && addedUser == 1) {
                redisTemplate.expire(usersKey, WINDOW_SECONDS, TimeUnit.SECONDS);
            }

            // 如果已有热点簇，同步更新簇内统计
            String clusterKey = CLUSTER_KEY_PREFIX + sigHash;
            String payload = (String) redisTemplate.opsForHash().get(clusterKey, "payload");
            if (payload != null && !payload.isBlank()) {
                ClusterData cluster = objectMapper.readValue(payload, ClusterData.class);
                if (cluster != null) {
                    cluster.setQueryCount(cluster.getQueryCount() + 1);
                    if (runId != null && !runId.isBlank()
                            && cluster.getUniqueRuns() != null
                            && !cluster.getUniqueRuns().contains(runId)) {
                        cluster.getUniqueRuns().add(runId);
                    }
                    if (userId != null && !userId.isBlank()
                            && cluster.getUniqueUsers() != null
                            && !cluster.getUniqueUsers().contains(userId)) {
                        cluster.getUniqueUsers().add(userId);
                    }
                    cluster.setLastAccessedAt(Instant.now().toString());
                    redisTemplate.opsForHash().put(clusterKey, "payload", objectMapper.writeValueAsString(cluster));
                }
            }
        } catch (Exception e) {
            log.warn("更新热点簇统计失败, slotSignature={}", slotSignature, e);
        }
    }

    /**
     * 为已有簇添加新答案（来自不同 backend）。
     * 添加后会重新调用 AnswerAggregator 生成聚合答案。
     *
     * @param slotSignature 槽位签名
     * @param result        backend 搜索结果
     */
    public void addAnswer(String slotSignature, BackendSearchResult result) {
        try {
            String sigHash = sha256(slotSignature);
            String clusterKey = CLUSTER_KEY_PREFIX + sigHash;

            String payload = (String) redisTemplate.opsForHash().get(clusterKey, "payload");
            if (payload == null || payload.isBlank()) {
                log.warn("添加答案失败，热点簇不存在, slotSignature={}", slotSignature);
                return;
            }

            ClusterData cluster = objectMapper.readValue(payload, ClusterData.class);
            if (cluster == null) {
                return;
            }

            String backend = result.meta() != null ? result.meta().backend() : "unknown";
            String citationsJson = safeWrite(result.citations());
            String resultHash = sha256(nvl(result.answer()) + citationsJson);
            String now = Instant.now().toString();

            AnswerAggregator.BackendAnswer answer = new AnswerAggregator.BackendAnswer(
                    backend,
                    result.answer(),
                    result.citations(),
                    resultHash,
                    now
            );

            if (cluster.getAnswers() == null) {
                cluster.setAnswers(new ArrayList<>());
            }
            cluster.getAnswers().add(answer);

            // 重新聚合答案
            String aggregated = answerAggregator.aggregate(cluster.getCanonicalQuery(), cluster.getAnswers());
            cluster.setAggregatedAnswer(aggregated);
            cluster.setLastAccessedAt(now);

            redisTemplate.opsForHash().put(clusterKey, "payload", objectMapper.writeValueAsString(cluster));
        } catch (Exception e) {
            log.warn("为热点簇添加答案失败, slotSignature={}", slotSignature, e);
        }
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

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 热点簇缓存查询结果
     */
    public record HotKeywordCacheResult(
            boolean hit,
            String canonicalQuery,
            String answer,
            List<BackendCitation> citations,
            String aggregatedAnswer,
            long ttlRemainingSeconds
    ) {}

    /**
     * 热点簇内部数据结构（JSON 序列化后存储于 Redis Hash 的 payload 字段）
     */
    @Data
    private static class ClusterData {
        private String canonicalQuery;
        private String intentTemplate;
        private String slotSignature;
        private String scene;
        private String createdAt;
        private String expiresAt;
        private int queryCount;
        private List<String> uniqueRuns;
        private List<String> uniqueUsers;
        private List<AnswerAggregator.BackendAnswer> answers;
        private String aggregatedAnswer;
        private String lastAccessedAt;
    }
}
