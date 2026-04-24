package world.willfrog.externalinfo.search.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.externalinfo.config.SearchLlmProperties;
import world.willfrog.externalinfo.search.profile.GlobalUserProfileInjector;
import world.willfrog.externalinfo.search.profile.ProfileContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tavily 搜索后端实现。
 * 调用 Tavily /search API 获取搜索结果及 AI 综合答案。
 */
@Component
@Slf4j
public class TavilyBackend implements SearchBackend {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 45;
    private static final String API_PATH = "/search";
    private static final Set<String> SUPPORTED_SCENES = Set.of("general", "finance", "news");
    private static final Set<String> SUPPORTED_STRENGTHS = Set.of(
            "fast", "cheap", "standard", "default", "deep", "pro", "ultra-fast"
    );

    private final SearchLlmProperties properties;
    private final ObjectMapper objectMapper;
    private final GlobalUserProfileInjector globalUserProfileInjector;
    private final ProfileContext profileContext;

    public TavilyBackend(SearchLlmProperties properties, ObjectMapper objectMapper,
                          GlobalUserProfileInjector globalUserProfileInjector,
                          ProfileContext profileContext) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.globalUserProfileInjector = globalUserProfileInjector;
        this.profileContext = profileContext;
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public boolean supportsScene(String scene) {
        return scene != null && SUPPORTED_SCENES.contains(scene.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean supportsStrength(String strength) {
        return strength != null && SUPPORTED_STRENGTHS.contains(strength.toLowerCase(Locale.ROOT));
    }

    @Override
    public BackendSearchResult search(WebSearchRequest request) {
        long startMs = System.currentTimeMillis();
        ResolvedConfig config = resolveConfig(name());
        if (config == null || !hasText(config.baseUrl())) {
            log.error("Tavily backend 配置缺失");
            return BackendSearchResult.error(name(), "CONFIG_MISSING", "Tavily backend 配置缺失");
        }

        String url = resolveUrl(config.baseUrl(), API_PATH);
        String strength = normalize(request.getStrength());
        if (!hasText(strength)) {
            strength = "standard";
        }

        Map<String, Object> body = buildRequestBody(request, strength);
        String rawQuery = request.getQuery();

        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(resolveRequestTimeout(config)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
            applyAuthHeader(config, requestBuilder);
            applyExtraHeaders(config, requestBuilder);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(resolveConnectTimeout(config)))
                    .build();
            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 300) {
                log.error("Tavily 返回状态码 {}，响应体: {}", response.statusCode(), response.body());
                return BackendSearchResult.error(name(), "HTTP_" + response.statusCode(),
                        "Tavily 请求失败，状态码: " + response.statusCode());
            }

            return parseResponse(response.body(), strength, rawQuery, System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            log.error("Tavily 搜索请求异常", e);
            return BackendSearchResult.error(name(), "REQUEST_EXCEPTION", e.getMessage());
        }
    }

    /**
     * 构建 Tavily /search 请求体
     */
    private Map<String, Object> buildRequestBody(WebSearchRequest request, String strength) {
        Map<String, Object> body = new LinkedHashMap<>();
        // 将全局画像注入到 query 中
        String injectedQuery = globalUserProfileInjector.injectIntoQuery(request.getQuery(), profileContext.getGlobalProfile());
        body.put("query", injectedQuery);
        body.put("search_depth", resolveSearchDepth(strength));
        body.put("topic", resolveTopic(request.getScene()));
        body.put("include_answer", true);

        int maxResults = request.getMaxResults();
        if (maxResults > 0) {
            body.put("max_results", maxResults);
        }

        // 时间范围过滤
        String timeRange = resolveTimeRange(request.getTimeRangeStart(), request.getTimeRangeEnd());
        if (hasText(timeRange)) {
            body.put("time_range", timeRange);
        } else {
            String startDate = resolveIsoDate(request.getTimeRangeStart());
            String endDate = resolveIsoDate(request.getTimeRangeEnd());
            if (hasText(startDate)) {
                body.put("start_date", startDate);
            }
            if (hasText(endDate)) {
                body.put("end_date", endDate);
            }
        }

        return body;
    }

    /**
     * 按强度档位映射 search_depth
     */
    private String resolveSearchDepth(String strength) {
        String s = strength.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "fast", "cheap" -> "fast";
            case "standard", "default" -> "basic";
            case "deep", "pro" -> "advanced";
            case "ultra-fast" -> "ultra-fast";
            default -> "basic";
        };
    }

    /**
     * 按场景映射 topic
     */
    private String resolveTopic(String scene) {
        String s = normalize(scene);
        return switch (s) {
            case "finance" -> "finance";
            case "news" -> "news";
            default -> "general";
        };
    }

    /**
     * 将时间范围映射为 Tavily time_range（粗略过滤）
     */
    private String resolveTimeRange(String timeRangeStart, String timeRangeEnd) {
        OffsetDateTime start = parseDateTime(timeRangeStart);
        OffsetDateTime end = parseDateTime(timeRangeEnd);
        if (start == null && end == null) {
            return null;
        }
        OffsetDateTime s = start != null ? start : end.minusDays(1);
        OffsetDateTime e = end != null ? end : OffsetDateTime.now(ZoneOffset.UTC);
        if (s.isAfter(e)) {
            OffsetDateTime tmp = s;
            s = e;
            e = tmp;
        }
        long days = java.time.Duration.between(s, e).toDays();
        if (days <= 1) {
            return "day";
        }
        if (days <= 7) {
            return "week";
        }
        if (days <= 31) {
            return "month";
        }
        if (days <= 365) {
            return "year";
        }
        return null;
    }

    /**
     * 将 ISO 时间字符串解析为 Tavily 日期过滤格式（YYYY-MM-DD）
     */
    private String resolveIsoDate(String isoDateTime) {
        OffsetDateTime dt = parseDateTime(isoDateTime);
        if (dt == null) {
            return null;
        }
        return dt.toLocalDate().toString();
    }

    private OffsetDateTime parseDateTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        String raw = value.trim();
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e1) {
            try {
                return OffsetDateTime.parse(raw + "Z");
            } catch (DateTimeParseException e2) {
                log.debug("无法解析时间字符串: {}", raw);
                return null;
            }
        }
    }

    private BackendSearchResult parseResponse(String responseBody, String strength,
                                               String rawQuery, long costMs) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String answer = root.path("answer").asText("");
            List<BackendHit> hits = new ArrayList<>();
            List<BackendCitation> citations = new ArrayList<>();

            JsonNode results = root.path("results");
            if (results.isArray()) {
                int index = 1;
                for (JsonNode result : results) {
                    String title = result.path("title").asText("");
                    String url = result.path("url").asText("");
                    String snippet = result.path("content").asText("");
                    String publishedDate = result.path("published_date").asText("");
                    float score = result.path("score").floatValue();

                    hits.add(new BackendHit(title, url, snippet, deriveSource(url), publishedDate,
                            score > 0 ? score : null));

                    if (hasText(url)) {
                        citations.add(new BackendCitation(index, url, title));
                    }
                    index++;
                }
            }

            BackendMeta meta = new BackendMeta(name(), resolveSearchDepth(strength), (int) costMs, rawQuery);
            return new BackendSearchResult(hits, answer, citations, meta, true, null, null);
        } catch (Exception e) {
            log.error("Tavily 响应解析失败", e);
            return BackendSearchResult.error(name(), "PARSE_ERROR", "响应解析失败: " + e.getMessage());
        }
    }

    private String deriveSource(String url) {
        if (!hasText(url)) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 配置解析 ====================

    private ResolvedConfig resolveConfig(String backendName) {
        SearchLlmProperties.WebSearchFeature webSearch = properties.getFeatures().getWebSearch();
        if (webSearch != null) {
            SearchLlmProperties.BackendConfig bc = webSearch.getBackends().get(backendName);
            if (bc != null) {
                return new ResolvedConfig(
                        bc.getBaseUrl(), bc.getApiKey(), bc.getAuthHeader(), bc.getAuthPrefix(),
                        bc.getHeaders(), bc.getConnectTimeoutSeconds(), bc.getRequestTimeoutSeconds()
                );
            }
        }
        SearchLlmProperties.Provider p = properties.getProviders().get(backendName);
        if (p != null) {
            return new ResolvedConfig(
                    p.getBaseUrl(), p.getApiKey(), p.getAuthHeader(), p.getAuthPrefix(),
                    p.getHeaders(), p.getConnectTimeoutSeconds(), p.getRequestTimeoutSeconds()
            );
        }
        return null;
    }

    // ==================== HTTP 工具 ====================

    private void applyAuthHeader(ResolvedConfig config, HttpRequest.Builder builder) {
        if (config == null) {
            return;
        }
        // Tavily 固定使用 Authorization: Bearer {apiKey}
        String apiKey = config.apiKey();
        if (hasText(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    private void applyExtraHeaders(ResolvedConfig config, HttpRequest.Builder builder) {
        if (config == null || config.headers() == null) {
            return;
        }
        for (Map.Entry<String, String> entry : config.headers().entrySet()) {
            if (hasText(entry.getKey()) && entry.getValue() != null) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
    }

    private String resolveUrl(String baseUrl, String path) {
        if (!hasText(baseUrl)) {
            return "";
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = hasText(path) ? path.trim() : "";
        if (!normalizedPath.isEmpty() && !normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return normalizedBase + normalizedPath;
    }

    private int resolveConnectTimeout(ResolvedConfig config) {
        if (config == null || config.connectTimeoutSeconds() == null || config.connectTimeoutSeconds() <= 0) {
            return DEFAULT_CONNECT_TIMEOUT_SECONDS;
        }
        return config.connectTimeoutSeconds();
    }

    private int resolveRequestTimeout(ResolvedConfig config) {
        if (config == null || config.requestTimeoutSeconds() == null || config.requestTimeoutSeconds() <= 0) {
            return DEFAULT_REQUEST_TIMEOUT_SECONDS;
        }
        return config.requestTimeoutSeconds();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record ResolvedConfig(String baseUrl, String apiKey, String authHeader, String authPrefix,
                                   Map<String, String> headers, Integer connectTimeoutSeconds,
                                   Integer requestTimeoutSeconds) {
    }
}
