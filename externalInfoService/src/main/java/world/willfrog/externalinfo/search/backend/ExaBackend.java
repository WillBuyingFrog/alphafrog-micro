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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exa 搜索后端实现。
 * 调用 Exa /search API 获取搜索结果及摘要。
 */
@Component
@Slf4j
public class ExaBackend implements SearchBackend {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 45;
    private static final String API_PATH = "/search";
    private static final Set<String> SUPPORTED_SCENES = Set.of("general", "finance", "news");
    private static final Set<String> SUPPORTED_STRENGTHS = Set.of(
            "fast", "cheap", "standard", "default", "deep", "pro", "deep-research"
    );

    private final SearchLlmProperties properties;
    private final ObjectMapper objectMapper;
    private final GlobalUserProfileInjector globalUserProfileInjector;
    private final ProfileContext profileContext;

    public ExaBackend(SearchLlmProperties properties, ObjectMapper objectMapper,
                       GlobalUserProfileInjector globalUserProfileInjector,
                       ProfileContext profileContext) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.globalUserProfileInjector = globalUserProfileInjector;
        this.profileContext = profileContext;
    }

    @Override
    public String name() {
        return "exa";
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
            log.error("Exa backend 配置缺失");
            return BackendSearchResult.error(name(), "CONFIG_MISSING", "Exa backend 配置缺失");
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
                log.error("Exa 返回状态码 {}，响应体: {}", response.statusCode(), response.body());
                return BackendSearchResult.error(name(), "HTTP_" + response.statusCode(),
                        "Exa 请求失败，状态码: " + response.statusCode());
            }

            return parseResponse(response.body(), strength, rawQuery, System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            log.error("Exa 搜索请求异常", e);
            return BackendSearchResult.error(name(), "REQUEST_EXCEPTION", e.getMessage());
        }
    }

    /**
     * 构建 Exa /search 请求体
     */
    private Map<String, Object> buildRequestBody(WebSearchRequest request, String strength) {
        Map<String, Object> body = new LinkedHashMap<>();
        // Exa 没有独立的 systemPrompt 字段，将全局画像注入到 query 前缀中
        String injectedQuery = globalUserProfileInjector.injectIntoQuery(request.getQuery(), profileContext.getGlobalProfile());
        body.put("query", injectedQuery);
        body.put("type", resolveSearchType(strength));

        int maxResults = request.getMaxResults();
        if (maxResults > 0) {
            body.put("numResults", maxResults);
        }

        // 时间范围过滤
        String startDate = resolveIsoDateTime(request.getTimeRangeStart());
        String endDate = resolveIsoDateTime(request.getTimeRangeEnd());
        if (hasText(startDate)) {
            body.put("startPublishedDate", startDate);
        }
        if (hasText(endDate)) {
            body.put("endPublishedDate", endDate);
        }

        // contents 配置：text、highlights、summary
        Map<String, Object> contents = new LinkedHashMap<>();
        Map<String, Object> textConfig = new LinkedHashMap<>();
        textConfig.put("maxCharacters", 4000);
        contents.put("text", textConfig);

        Map<String, Object> highlightsConfig = new LinkedHashMap<>();
        highlightsConfig.put("maxCharacters", 1000);
        contents.put("highlights", highlightsConfig);

        contents.put("summary", true);
        body.put("contents", contents);

        return body;
    }

    /**
     * 按强度档位映射搜索类型
     */
    private String resolveSearchType(String strength) {
        String s = strength.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "fast", "cheap" -> "fast";
            case "standard", "default" -> "auto";
            case "deep", "pro" -> "deep";
            case "deep-research" -> "deep-reasoning";
            default -> "auto";
        };
    }

    /**
     * 将 ISO 时间字符串解析为 Exa 日期过滤格式（ISO 8601）
     */
    private String resolveIsoDateTime(String isoDateTime) {
        OffsetDateTime dt = parseDateTime(isoDateTime);
        if (dt == null) {
            return null;
        }
        return dt.toInstant().toString();
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
            List<BackendHit> hits = new ArrayList<>();
            List<BackendCitation> citations = new ArrayList<>();
            StringBuilder answerBuilder = new StringBuilder();

            // 优先从 output.content 获取合成答案
            JsonNode outputNode = root.path("output");
            if (!outputNode.isMissingNode()) {
                String outputContent = outputNode.path("content").asText("");
                if (hasText(outputContent)) {
                    answerBuilder.append(outputContent);
                }
            }

            JsonNode results = root.path("results");
            if (results.isArray()) {
                int index = 1;
                for (JsonNode result : results) {
                    String title = result.path("title").asText("");
                    String url = result.path("url").asText("");
                    String text = result.path("text").asText("");
                    String publishedDate = result.path("publishedDate").asText("");
                    String author = result.path("author").asText("");
                    double score = result.path("score").asDouble(-1);

                    // 从 highlights 中取第一段作为 snippet
                    String snippet = text;
                    JsonNode highlights = result.path("highlights");
                    if (highlights.isArray() && !highlights.isEmpty()) {
                        snippet = highlights.get(0).asText(text);
                    }

                    hits.add(new BackendHit(title, url, snippet, deriveSource(url, author), publishedDate,
                            score >= 0 ? (float) score : null));

                    if (hasText(url)) {
                        citations.add(new BackendCitation(index, url, title));
                    }

                    // 如果 output 没有提供答案，尝试从 results.summary 拼接
                    String summary = result.path("summary").asText("");
                    if (!hasText(answerBuilder.toString()) && hasText(summary)) {
                        if (answerBuilder.length() > 0) {
                            answerBuilder.append("\n\n");
                        }
                        answerBuilder.append(summary);
                    }

                    index++;
                }
            }

            String answer = answerBuilder.toString().trim();
            BackendMeta meta = new BackendMeta(name(), resolveSearchType(strength), (int) costMs, rawQuery);
            return new BackendSearchResult(hits, answer.isEmpty() ? null : answer, citations, meta, true, null, null);
        } catch (Exception e) {
            log.error("Exa 响应解析失败", e);
            return BackendSearchResult.error(name(), "PARSE_ERROR", "响应解析失败: " + e.getMessage());
        }
    }

    private String deriveSource(String url, String author) {
        if (hasText(author)) {
            return author.trim();
        }
        if (!hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
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
        // Exa 固定使用 x-api-key: {apiKey}
        String apiKey = config.apiKey();
        if (hasText(apiKey)) {
            builder.header("x-api-key", apiKey);
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
