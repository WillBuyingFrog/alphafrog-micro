package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentLlmLocalConfigLoader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolResultCacheService {

    private static final String CACHE_PREFIX = "agent:tool-cache:";
    private static final String SOURCE_REDIS = "redis_tool_cache";
    private static final String SOURCE_DATASET_REGISTRY = "dataset_registry";
    private static final String SOURCE_NONE = "none";
    private static final Set<String> SEARCH_TOOLS = Set.of("searchStock", "searchFund", "searchIndex");
    private static final Set<String> INFO_TOOLS = Set.of("getStockInfo", "getIndexInfo");
    private static final Set<String> DATASET_TOOLS = Set.of("getStockDaily", "getIndexDaily");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final MeterRegistry meterRegistry;

    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Timer cacheLookupTimer;

    @Value("${agent.tool-cache.version:v1}")
    private String defaultVersion;

    @Value("${agent.tool-cache.search-ttl-seconds:3600}")
    private int defaultSearchTtlSeconds;

    @Value("${agent.tool-cache.info-ttl-seconds:21600}")
    private int defaultInfoTtlSeconds;

    @Value("${agent.tool-cache.dataset-ttl-seconds:604800}")
    private int defaultDatasetTtlSeconds;

    @PostConstruct
    public void init() {
        this.cacheHitCounter = Counter.builder("cache.hit")
                .description("Cache hit count")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("cache.miss")
                .description("Cache miss count")
                .register(meterRegistry);
        this.cacheLookupTimer = Timer.builder("cache.lookup")
                .description("Cache lookup duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public CachedToolCallResult executeWithCache(String toolName,
                                                 Map<String, Object> params,
                                                 String scope,
                                                 Supplier<ToolExecutionOutcome> loader) {
        CachePlan plan = buildPlan(toolName, params, scope);
        debugLog("cache plan resolved: runId={}, tool={}, mode={}, key={}, ttlSeconds={}, scope={}",
                AgentContext.getRunId(), nvl(toolName), plan.getMode(), plan.getKey(), plan.getTtlSeconds(), nvl(scope));
        long lookupStartedAt = System.currentTimeMillis();

        if (plan.getMode() == CacheMode.REDIS && plan.getTtlSeconds() > 0) {
            CachePayload cached = readCache(plan.getKey());
            if (cached != null && cached.getResult() != null) {
                if (!isStructuredToolResult(cached.getResult())) {
                    redisTemplate.delete(plan.getKey());
                    log.info("Ignore legacy tool cache payload, key={}", plan.getKey());
                } else {
                    long durationMs = Math.max(0L, System.currentTimeMillis() - lookupStartedAt);
                    long ttlRemainingMs = ttlRemainingMs(plan.getKey());
                    long savedDurationMs = Math.max(0L, cached.getOriginalDurationMs() - durationMs);
                    CacheMeta meta = CacheMeta.builder()
                            .eligible(true)
                            .hit(true)
                            .key(plan.getKey())
                            .ttlRemainingMs(ttlRemainingMs)
                            .source(SOURCE_REDIS)
                            .estimatedSavedDurationMs(savedDurationMs)
                            .build();
                    debugLog("cache hit: runId={}, tool={}, key={}, ttlRemainingMs={}, savedMs={}",
                            AgentContext.getRunId(), nvl(toolName), plan.getKey(), ttlRemainingMs, savedDurationMs);
                    cacheHitCounter.increment();
                    cacheLookupTimer.record(durationMs, TimeUnit.MILLISECONDS);
                    return CachedToolCallResult.builder()
                            .result(cached.getResult())
                            .durationMs(durationMs)
                            .success(true)
                            .cacheMeta(meta)
                            .build();
                }
            }
        }

        // 未命中缓存时才执行真实工具调用（loader）。
        cacheMissCounter.increment();
        ToolExecutionOutcome loaded = loader.get();
        cacheLookupTimer.record(System.currentTimeMillis() - lookupStartedAt, TimeUnit.MILLISECONDS);
        if (loaded == null) {
            loaded = ToolExecutionOutcome.builder()
                    .result(fallbackToolErrorJson(toolName, "EMPTY_LOADER_RESULT", "Tool invocation error: empty loader result"))
                    .durationMs(0L)
                    .success(false)
                    .build();
        }

        CacheMeta meta;
        if (plan.getMode() == CacheMode.NONE) {
            meta = CacheMeta.builder()
                    .eligible(false)
                    .hit(false)
                    .key("")
                    .ttlRemainingMs(-1L)
                    .source(SOURCE_NONE)
                    .estimatedSavedDurationMs(0L)
                    .build();
        } else if (plan.getMode() == CacheMode.REDIS) {
            if (loaded.isSuccess() && plan.getTtlSeconds() > 0 && isStructuredToolResult(loaded.getResult())) {
                writeCache(plan.getKey(), loaded.getResult(), loaded.getDurationMs(), plan.getTtlSeconds());
                debugLog("cache write: runId={}, tool={}, key={}, ttlSeconds={}, durationMs={}",
                        AgentContext.getRunId(), nvl(toolName), plan.getKey(), plan.getTtlSeconds(), loaded.getDurationMs());
            }
            meta = CacheMeta.builder()
                    .eligible(true)
                    .hit(false)
                    .key(plan.getKey())
                    .ttlRemainingMs(plan.getTtlSeconds() > 0 ? plan.getTtlSeconds() * 1000L : -1L)
                    .source(SOURCE_REDIS)
                    .estimatedSavedDurationMs(0L)
                    .build();
        } else {
            boolean hit = isDatasetReuseResult(loaded.getResult());
            meta = CacheMeta.builder()
                    .eligible(true)
                    .hit(hit)
                    .key(plan.getKey())
                    .ttlRemainingMs(plan.getTtlSeconds() > 0 ? plan.getTtlSeconds() * 1000L : -1L)
                    .source(SOURCE_DATASET_REGISTRY)
                    .estimatedSavedDurationMs(0L)
                    .build();
        }

        return CachedToolCallResult.builder()
                .result(loaded.getResult())
                .durationMs(Math.max(0L, loaded.getDurationMs()))
                .success(loaded.isSuccess())
                .cacheMeta(meta)
                .build();
    }

    public Map<String, Object> toPayload(CacheMeta meta) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eligible", meta != null && meta.isEligible());
        payload.put("hit", meta != null && meta.isHit());
        payload.put("key", meta == null ? "" : nvl(meta.getKey()));
        payload.put("ttlRemainingMs", meta == null ? -1L : meta.getTtlRemainingMs());
        payload.put("source", meta == null ? SOURCE_NONE : nvl(meta.getSource()));
        payload.put("estimatedSavedDurationMs", meta == null ? 0L : Math.max(0L, meta.getEstimatedSavedDurationMs()));
        return payload;
    }

    private CachePlan buildPlan(String toolName, Map<String, Object> params, String scope) {
        CacheMode mode = resolveMode(toolName);
        if (mode == CacheMode.NONE) {
            return CachePlan.builder()
                    .mode(CacheMode.NONE)
                    .key("")
                    .ttlSeconds(0)
                    .build();
        }
        String key = buildCacheKey(toolName, params, scope);
        int ttlSeconds = resolveTtlSeconds(mode, toolName);
        return CachePlan.builder()
                .mode(mode)
                .key(key)
                .ttlSeconds(Math.max(0, ttlSeconds))
                .build();
    }

    private CacheMode resolveMode(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return CacheMode.NONE;
        }
        if (SEARCH_TOOLS.contains(toolName) || INFO_TOOLS.contains(toolName)) {
            return CacheMode.REDIS;
        }
        if (DATASET_TOOLS.contains(toolName)) {
            return CacheMode.DATASET_REGISTRY;
        }
        return CacheMode.NONE;
    }

    private int resolveTtlSeconds(CacheMode mode, String toolName) {
        AgentLlmProperties.Cache localCache = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getCache)
                .orElse(null);
        if (mode == CacheMode.REDIS) {
            Integer searchLocal = localCache == null ? null : localCache.getSearchTtlSeconds();
            Integer infoLocal = localCache == null ? null : localCache.getInfoTtlSeconds();
            int search = searchLocal != null && searchLocal > 0 ? searchLocal : defaultSearchTtlSeconds;
            int info = infoLocal != null && infoLocal > 0 ? infoLocal : defaultInfoTtlSeconds;
            if (SEARCH_TOOLS.contains(toolName)) {
                return Math.max(0, search);
            }
            return Math.max(0, info);
        }
        if (mode == CacheMode.DATASET_REGISTRY) {
            Integer local = localCache == null ? null : localCache.getDatasetTtlSeconds();
            if (local != null && local > 0) {
                return local;
            }
            return Math.max(0, defaultDatasetTtlSeconds);
        }
        return 0;
    }

    private String buildCacheKey(String toolName, Map<String, Object> params, String scope) {
        Map<String, String> normalizedArgs = normalizeArgs(toolName, params);
        String argsJson = safeWrite(normalizedArgs);
        String argsHash = sha256(argsJson);
        String resolvedScope = safeToken(blank(scope) ? "global" : scope);
        String version = safeToken(resolveVersion());
        return CACHE_PREFIX + safeToken(toolName) + ":" + argsHash + ":" + resolvedScope + ":" + version;
    }

    private String resolveVersion() {
        String local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getCache)
                .map(AgentLlmProperties.Cache::getVersion)
                .orElse("");
        if (!blank(local)) {
            return local.trim();
        }
        if (!blank(defaultVersion)) {
            return defaultVersion.trim();
        }
        return "v1";
    }

    private Map<String, String> normalizeArgs(String toolName, Map<String, Object> params) {
        Map<String, Object> source = params == null ? Map.of() : params;
        TreeMap<String, String> normalized = new TreeMap<>();
        switch (nvl(toolName)) {
            case "searchStock":
            case "searchFund":
            case "searchIndex":
                putIfPresent(normalized, "keyword", normalizeKeyword(firstNonBlank(
                        source.get("keyword"),
                        source.get("query"),
                        source.get("arg0")
                )));
                break;
            case "getStockInfo":
            case "getIndexInfo":
                putIfPresent(normalized, "tsCode", normalizeCode(firstNonBlank(
                        source.get("tsCode"),
                        source.get("ts_code"),
                        source.get("code"),
                        source.get("stock_code"),
                        source.get("index_code"),
                        source.get("arg0")
                )));
                break;
            case "getStockDaily":
            case "getIndexDaily":
                putIfPresent(normalized, "tsCode", normalizeCode(firstNonBlank(
                        source.get("tsCode"),
                        source.get("ts_code"),
                        source.get("code"),
                        source.get("stock_code"),
                        source.get("index_code"),
                        source.get("arg0")
                )));
                putIfPresent(normalized, "startDateStr", normalizeDate(firstNonBlank(
                        source.get("startDateStr"),
                        source.get("startDate"),
                        source.get("start_date"),
                        source.get("arg1")
                )));
                putIfPresent(normalized, "endDateStr", normalizeDate(firstNonBlank(
                        source.get("endDateStr"),
                        source.get("endDate"),
                        source.get("end_date"),
                        source.get("arg2")
                )));
                break;
            default:
                for (Map.Entry<String, Object> entry : source.entrySet()) {
                    putIfPresent(normalized, safeToken(entry.getKey()), normalizeGeneric(entry.getValue()));
                }
        }
        return normalized;
    }

    private void putIfPresent(Map<String, String> map, String key, String value) {
        if (blank(key) || blank(value)) {
            return;
        }
        map.put(key, value);
    }

    private String normalizeKeyword(Object value) {
        String text = normalizeGeneric(value);
        if (text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeCode(Object value) {
        String text = normalizeGeneric(value);
        if (text.isBlank()) {
            return "";
        }
        return text.toUpperCase(Locale.ROOT);
    }

    private String normalizeDate(Object value) {
        String text = normalizeGeneric(value);
        if (text.isBlank()) {
            return "";
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.length() == 13) {
            try {
                long ts = Long.parseLong(digits);
                return Instant.ofEpochMilli(ts)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toLocalDate()
                        .format(DATE_FORMATTER);
            } catch (Exception e) {
                return digits;
            }
        }
        if (digits.length() >= 8) {
            return digits.substring(0, 8);
        }
        return text;
    }

    private String normalizeGeneric(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return "";
        }
        return text;
    }

    private String firstNonBlank(Object... candidates) {
        if (candidates == null) {
            return "";
        }
        for (Object candidate : candidates) {
            String text = normalizeGeneric(candidate);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private CachePayload readCache(String key) {
        if (blank(key)) {
            return null;
        }
        String json = redisTemplate.opsForValue().get(key);
        if (blank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CachePayload.class);
        } catch (Exception e) {
            redisTemplate.delete(key);
            log.warn("Parse tool cache failed, key={}", key, e);
            return null;
        }
    }

    private void writeCache(String key, String result, long originalDurationMs, int ttlSeconds) {
        if (blank(key) || blank(result) || ttlSeconds <= 0) {
            return;
        }
        try {
            CachePayload payload = new CachePayload();
            payload.setResult(result);
            payload.setOriginalDurationMs(Math.max(0L, originalDurationMs));
            payload.setCachedAtMillis(System.currentTimeMillis());
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(payload), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Write tool cache failed, key={}", key, e);
        }
    }

    private boolean isDatasetReuseResult(String result) {
        if (blank(result)) {
            return false;
        }
        try {
            Map<?, ?> root = objectMapper.readValue(result, Map.class);
            Object ok = root.get("ok");
            if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                return false;
            }
            Object dataObj = root.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) {
                return false;
            }
            Object cacheHit = data.get("cache_hit");
            if (cacheHit instanceof Boolean b) {
                return b;
            }
            Object source = data.get("source");
            return source != null && "reused".equalsIgnoreCase(String.valueOf(source));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isStructuredToolResult(String result) {
        if (blank(result)) {
            return false;
        }
        try {
            // 统一工具输出协议：必须含 ok + (data|error) 结构。
            Map<?, ?> root = objectMapper.readValue(result, Map.class);
            Object ok = root.get("ok");
            if (!(ok instanceof Boolean)) {
                return false;
            }
            Object data = root.get("data");
            Object error = root.get("error");
            return data instanceof Map<?, ?> || error instanceof Map<?, ?>;
        } catch (Exception e) {
            return false;
        }
    }

    private long ttlRemainingMs(String key) {
        if (blank(key)) {
            return -1L;
        }
        try {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            return ttl == null ? -1L : ttl;
        } catch (Exception e) {
            return -1L;
        }
    }

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(nvl(input).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(nvl(input).hashCode());
        }
    }

    private String safeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^a-zA-Z0-9:_-]", "_");
    }

    private boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private String fallbackToolErrorJson(String toolName, String code, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", false);
        payload.put("tool", nvl(toolName));
        payload.put("data", Map.of());
        payload.put("error", Map.of(
                "code", nvl(code),
                "message", nvl(message),
                "details", Map.of()
        ));
        return safeWrite(payload);
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private void debugLog(String pattern, Object... args) {
        if (!AgentContext.isDebugMode()) {
            return;
        }
        log.info("[agent-debug] " + pattern, args);
    }

    private enum CacheMode {
        NONE,
        REDIS,
        DATASET_REGISTRY
    }

    @Data
    @Builder
    private static class CachePlan {
        private CacheMode mode;
        private String key;
        private int ttlSeconds;
    }

    @Data
    private static class CachePayload {
        private String result;
        private long originalDurationMs;
        private long cachedAtMillis;
    }

    @Data
    @Builder
    public static class ToolExecutionOutcome {
        private String result;
        private long durationMs;
        private boolean success;
    }

    @Data
    @Builder
    public static class CacheMeta {
        private boolean eligible;
        private boolean hit;
        private String key;
        private long ttlRemainingMs;
        private String source;
        private long estimatedSavedDurationMs;
    }

    @Data
    @Builder
    public static class CachedToolCallResult {
        private String result;
        private long durationMs;
        private boolean success;
        private CacheMeta cacheMeta;
    }
}
