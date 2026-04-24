package world.willfrog.externalinfo.search.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Perplexity 搜索后端实现。
 * 使用 Perplexity Chat Completions API（OpenAI 兼容格式）。
 */
@Component
@Slf4j
public class PerplexityBackend implements SearchBackend {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 45;
    private static final String API_PATH = "/chat/completions";
    private static final Set<String> SUPPORTED_SCENES = Set.of("general", "finance", "news");
    private static final Set<String> SUPPORTED_STRENGTHS = Set.of(
            "fast", "cheap", "standard", "default", "deep", "pro", "reasoning", "deep-research"
    );

    private final SearchLlmProperties properties;
    private final ObjectMapper objectMapper;
    private final GlobalUserProfileInjector globalUserProfileInjector;
    private final ProfileContext profileContext;

    public PerplexityBackend(SearchLlmProperties properties, ObjectMapper objectMapper,
                              GlobalUserProfileInjector globalUserProfileInjector,
                              ProfileContext profileContext) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.globalUserProfileInjector = globalUserProfileInjector;
        this.profileContext = profileContext;
    }

    @Override
    public String name() {
        return "perplexity";
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
            log.error("Perplexity backend 配置缺失");
            return BackendSearchResult.error(name(), "CONFIG_MISSING", "Perplexity backend 配置缺失");
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
                log.error("Perplexity 返回状态码 {}，响应体: {}", response.statusCode(), response.body());
                return BackendSearchResult.error(name(), "HTTP_" + response.statusCode(),
                        "Perplexity 请求失败，状态码: " + response.statusCode());
            }

            return parseResponse(response.body(), strength, rawQuery, System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            log.error("Perplexity 搜索请求异常", e);
            return BackendSearchResult.error(name(), "REQUEST_EXCEPTION", e.getMessage());
        }
    }

    /**
     * 构建 Perplexity Chat Completions 请求体
     */
    private Map<String, Object> buildRequestBody(WebSearchRequest request, String strength) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolveModel(strength));

        List<Map<String, String>> messages = new ArrayList<>();

        // 注入全局画像到 system prompt
        String systemPrompt = globalUserProfileInjector.injectIntoSystemPrompt("", profileContext.getGlobalProfile());
        if (hasText(systemPrompt)) {
            Map<String, String> systemMessage = new LinkedHashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }

        Map<String, String> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", request.getQuery());
        messages.add(userMessage);
        body.put("messages", messages);

        Map<String, Object> webSearchOptions = new LinkedHashMap<>();
        webSearchOptions.put("search_context_size", resolveContextSize(strength));

        // 处理时间范围过滤
        String recencyFilter = resolveRecencyFilter(request.getTimeRangeStart(), request.getTimeRangeEnd());
        if (hasText(recencyFilter)) {
            webSearchOptions.put("search_recency_filter", recencyFilter);
        } else {
            String afterDate = resolveDateFilter(request.getTimeRangeStart());
            String beforeDate = resolveDateFilter(request.getTimeRangeEnd());
            if (hasText(afterDate)) {
                webSearchOptions.put("search_after_date_filter", afterDate);
            }
            if (hasText(beforeDate)) {
                webSearchOptions.put("search_before_date_filter", beforeDate);
            }
        }

        body.put("web_search_options", webSearchOptions);
        return body;
    }

    /**
     * 按强度档位映射模型名称
     */
    private String resolveModel(String strength) {
        String s = strength.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "fast", "cheap", "standard", "default" -> "sonar";
            case "deep", "pro" -> "sonar-pro";
            case "reasoning" -> "sonar-reasoning";
            case "deep-research" -> "sonar-deep-research";
            default -> "sonar";
        };
    }

    /**
     * 按强度档位映射搜索上下文大小
     */
    private String resolveContextSize(String strength) {
        String s = strength.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "fast", "cheap" -> "low";
            case "standard", "default" -> "medium";
            case "deep", "pro", "reasoning", "deep-research" -> "high";
            default -> "medium";
        };
    }

    /**
     * 将时间范围映射为 Perplexity recency filter（粗略过滤）
     */
    private String resolveRecencyFilter(String timeRangeStart, String timeRangeEnd) {
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
        long days = Duration.between(s, e).toDays();
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
     * 将 ISO 时间字符串解析为 Perplexity 日期过滤格式（YYYY-MM-DD）
     */
    private String resolveDateFilter(String isoDateTime) {
        OffsetDateTime dt = parseDateTime(isoDateTime);
        if (dt == null) {
            return null;
        }
        return dt.format(DateTimeFormatter.ISO_LOCAL_DATE);
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
            String answer = "";
            List<BackendCitation> citations = new ArrayList<>();
            List<BackendHit> hits = new ArrayList<>();

            // 解析 answer
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                if (!message.isMissingNode()) {
                    answer = message.path("content").asText("");
                }
            }

            // 解析 citations
            JsonNode citationsNode = root.path("citations");
            if (citationsNode.isArray()) {
                int index = 1;
                for (JsonNode citationNode : citationsNode) {
                    String url = citationNode.asText("");
                    if (hasText(url)) {
                        citations.add(new BackendCitation(index, url, ""));
                        // citations 也作为 hits（只有 URL，其余为空）
                        hits.add(new BackendHit("", url, "", "", null, null));
                        index++;
                    }
                }
            }

            BackendMeta meta = new BackendMeta(name(), resolveModel(strength), (int) costMs, rawQuery);
            return new BackendSearchResult(hits, answer, citations, meta, true, null, null);
        } catch (Exception e) {
            log.error("Perplexity 响应解析失败", e);
            return BackendSearchResult.error(name(), "PARSE_ERROR", "响应解析失败: " + e.getMessage());
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
        String header = config.authHeader();
        String apiKey = config.apiKey();
        if (!hasText(header) || !hasText(apiKey)) {
            return;
        }
        String prefix = config.authPrefix();
        builder.header(header, (prefix == null ? "" : prefix) + apiKey);
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
