package world.willfrog.externalinfo.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketNewsService {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 45;
    private static final int LANGUAGE_DETECTION_SAMPLE_LENGTH = 120;
    private static final int CJK_EXT_A_START = 0x3400;
    private static final int CJK_EXT_A_END = 0x4DBF;
    private static final int CJK_UNIFIED_START = 0x4E00;
    private static final int CJK_UNIFIED_END = 0x9FFF;

    private final ObjectMapper objectMapper;
    private final SearchLlmProperties properties;
    private final SearchLlmLocalConfigLoader localConfigLoader;

    public MarketNewsResult getTodayMarketNews(MarketNewsQuery query) {
        SearchLlmProperties cfg = localConfigLoader.current().orElse(properties);
        SearchLlmProperties.MarketNewsFeature feature = requireMarketNewsFeature(cfg);

        int finalLimit = resolveFinalLimit(query.limit(), feature);
        List<MarketNewsItem> allItems = new ArrayList<>();
        Set<String> usedProviders = new LinkedHashSet<>();

        for (SearchLlmProperties.MarketNewsProfile profile : feature.getProfiles()) {
            ProfileSearchOptions options = resolveProfileOptions(query, feature, profile, cfg.getProviders());
            usedProviders.add(options.providerName());
            MarketNewsResponse response = executeProfileSearch(feature, profile, options);
            List<MarketNewsItem> profileItems = filterItems(
                    response.items(),
                    options.profileLanguages(),
                    options.startTime(),
                    options.endTime(),
                    options.fetchLimit()
            );
            allItems.addAll(profileItems);
        }

        List<MarketNewsItem> merged = deduplicateAndSort(allItems);
        List<MarketNewsItem> filteredByRequest = filterItems(
                merged,
                query.languages(),
                parseOffsetDateTime(query.startPublishedDate()),
                parseOffsetDateTime(query.endPublishedDate()),
                finalLimit
        );
        String updatedAt = resolveUpdatedAt("", filteredByRequest);
        return new MarketNewsResult(filteredByRequest, updatedAt, resolveProviderLabel(query.provider(), usedProviders));
    }

    MarketNewsResponse executeProfileSearch(SearchLlmProperties.MarketNewsFeature feature,
                                            SearchLlmProperties.MarketNewsProfile profile,
                                            ProfileSearchOptions options) {
        if ("exa".equalsIgnoreCase(options.providerName())) {
            return fetchFromExa(feature, profile, options);
        }
        if ("perplexity".equalsIgnoreCase(options.providerName())) {
            return fetchFromPerplexity(profile, options);
        }
        throw new IllegalArgumentException("unsupported provider: " + options.providerName());
    }

    MarketNewsResponse fetchFromPerplexity(SearchLlmProperties.MarketNewsProfile profile,
                                           ProfileSearchOptions options) {
        String responseBody = postJson(options.provider(), buildPerplexityRequestBody(profile, options));
        try {
            PerplexitySearchResponse response = objectMapper.readValue(responseBody, PerplexitySearchResponse.class);
            List<MarketNewsItem> items = mapPerplexityResults(
                    response == null ? List.of() : response.results(),
                    profile.getCategoryHint()
            );
            return new MarketNewsResponse(items, response == null ? null : response.serverTime());
        } catch (Exception e) {
            throw new IllegalStateException("perplexity response parse failed", e);
        }
    }

    Map<String, Object> buildPerplexityRequestBody(SearchLlmProperties.MarketNewsProfile profile,
                                                   ProfileSearchOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", options.queryText());
        body.put("max_results", Math.min(options.fetchLimit(), 20));

        String recency = resolveRecencyFilter(options.startTime(), options.endTime());
        if (hasText(recency)) {
            body.put("search_recency_filter", recency);
        }
        if (!options.domainFilter().isEmpty()) {
            body.put("search_domain_filter", options.domainFilter());
        }
        if (!options.profileLanguages().isEmpty()) {
            body.put("search_language_filter", options.profileLanguages());
        }
        if (hasText(profile.getCountry())) {
            body.put("country", profile.getCountry());
        }
        return body;
    }

    MarketNewsResponse fetchFromExa(SearchLlmProperties.MarketNewsFeature feature,
                                    SearchLlmProperties.MarketNewsProfile profile,
                                    ProfileSearchOptions options) {
        String responseBody = postJson(options.provider(), buildExaRequestBody(feature, profile, options));
        try {
            ExaSearchResponse response = objectMapper.readValue(responseBody, ExaSearchResponse.class);
            List<MarketNewsItem> items = mapExaResults(
                    response == null ? List.of() : response.results(),
                    profile.getCategoryHint()
            );
            return new MarketNewsResponse(items, null);
        } catch (Exception e) {
            throw new IllegalStateException("exa response parse failed", e);
        }
    }

    Map<String, Object> buildExaRequestBody(SearchLlmProperties.MarketNewsFeature feature,
                                            SearchLlmProperties.MarketNewsProfile profile,
                                            ProfileSearchOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", options.queryText());
        body.put("type", hasText(feature.getExaSearchType()) ? feature.getExaSearchType() : "auto");
        if (hasText(feature.getExaCategory())) {
            body.put("category", feature.getExaCategory());
        }
        body.put("numResults", Math.min(options.fetchLimit(), 100));
        if (options.startTime() != null) {
            body.put("startPublishedDate", options.startTime().toInstant().toString());
        }
        if (options.endTime() != null) {
            body.put("endPublishedDate", options.endTime().toInstant().toString());
        }
        if (!profile.getIncludeDomains().isEmpty()) {
            body.put("includeDomains", profile.getIncludeDomains());
        }
        if (!profile.getExcludeDomains().isEmpty()) {
            body.put("excludeDomains", profile.getExcludeDomains());
        }
        if (hasText(profile.getCountry())) {
            body.put("userLocation", profile.getCountry());
        }
        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("text", false);
        body.put("contents", contents);
        return body;
    }

    List<MarketNewsItem> mapPerplexityResults(List<PerplexitySearchResult> results,
                                              String categoryHint) {
        List<MarketNewsItem> items = new ArrayList<>();
        if (results == null) {
            return items;
        }
        int index = 0;
        for (PerplexitySearchResult result : results) {
            if (result == null) {
                continue;
            }
            String title = nvl(result.title());
            String url = nvl(result.url());
            String timestamp = firstNonBlank(result.lastUpdated(), result.date());
            String source = deriveSource(url, null);
            String category = hasText(categoryHint) ? categoryHint.trim() : "market";
            String id = generateId(url, title, index++);
            items.add(new MarketNewsItem(id, timestamp, title, source, category, url));
        }
        return items;
    }

    List<MarketNewsItem> mapExaResults(List<ExaSearchResult> results,
                                       String categoryHint) {
        List<MarketNewsItem> items = new ArrayList<>();
        if (results == null) {
            return items;
        }
        int index = 0;
        for (ExaSearchResult result : results) {
            if (result == null) {
                continue;
            }
            String title = nvl(result.title());
            String url = nvl(result.url());
            String timestamp = nvl(result.publishedDate());
            String source = deriveSource(url, result.author());
            String category = hasText(categoryHint) ? categoryHint.trim() : "market";
            String id = firstNonBlank(result.id(), generateId(url, title, index++));
            items.add(new MarketNewsItem(id, timestamp, title, source, category, url));
        }
        return items;
    }

    List<MarketNewsItem> filterItems(List<MarketNewsItem> items,
                                     List<String> languages,
                                     OffsetDateTime startTime,
                                     OffsetDateTime endTime,
                                     int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? items.size() : limit;
        List<MarketNewsItem> filtered = new ArrayList<>();
        for (MarketNewsItem item : items) {
            if (item == null) {
                continue;
            }
            if (!matchesLanguage(item.title(), languages)) {
                continue;
            }
            OffsetDateTime ts = parseOffsetDateTime(item.timestamp());
            if (!matchesTimeRange(ts, startTime, endTime)) {
                continue;
            }
            filtered.add(item);
            if (filtered.size() >= safeLimit) {
                break;
            }
        }
        return filtered;
    }

    List<MarketNewsItem> deduplicateAndSort(List<MarketNewsItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, MarketNewsItem> urlMap = new LinkedHashMap<>();
        List<MarketNewsItem> noUrl = new ArrayList<>();
        for (MarketNewsItem item : items) {
            if (item == null) {
                continue;
            }
            String key = normalizeKey(item.url());
            if (!hasText(key)) {
                noUrl.add(item);
                continue;
            }
            upsertByFreshness(urlMap, key, item);
        }

        Map<String, MarketNewsItem> titleMap = new LinkedHashMap<>();
        for (MarketNewsItem item : urlMap.values()) {
            upsertByFreshness(titleMap, normalizeKey(item.title()), item);
        }
        for (MarketNewsItem item : noUrl) {
            upsertByFreshness(titleMap, normalizeKey(item.title()), item);
        }

        List<MarketNewsItem> merged = new ArrayList<>(titleMap.values());
        merged.sort(Comparator.comparing(this::timestampOrMin).reversed());
        return merged;
    }

    private void upsertByFreshness(Map<String, MarketNewsItem> map, String key, MarketNewsItem candidate) {
        if (!hasText(key) || candidate == null) {
            return;
        }
        MarketNewsItem existing = map.get(key);
        OffsetDateTime candidateTs = timestampOrMin(candidate);
        OffsetDateTime existingTs = existing == null ? OffsetDateTime.MIN : timestampOrMin(existing);
        if (existing == null || candidateTs.isAfter(existingTs)) {
            map.put(key, candidate);
        }
    }

    private OffsetDateTime timestampOrMin(MarketNewsItem item) {
        OffsetDateTime ts = item == null ? null : parseOffsetDateTime(item.timestamp());
        return ts == null ? OffsetDateTime.MIN : ts;
    }

    private ProfileSearchOptions resolveProfileOptions(MarketNewsQuery query,
                                                       SearchLlmProperties.MarketNewsFeature feature,
                                                       SearchLlmProperties.MarketNewsProfile profile,
                                                       Map<String, SearchLlmProperties.Provider> providers) {
        String providerName = firstNonBlank(query.provider(), profile.getProvider(), feature.getDefaultProvider());
        if (!hasText(providerName)) {
            throw new IllegalStateException("marketNews profile '" + profile.getName() + "' does not specify provider and feature.defaultProvider is not configured");
        }
        SearchLlmProperties.Provider provider = providers.get(providerName);
        if (provider == null || !hasText(provider.getBaseUrl())) {
            throw new IllegalArgumentException("search provider not configured: " + providerName);
        }

        String queryText = resolveProfileQuery(profile);
        int requestedFinalLimit = resolveFinalLimit(query.limit(), feature);
        int profileLimit = profile.getLimit() != null && profile.getLimit() > 0 ? profile.getLimit() : requestedFinalLimit;
        int maxResults = feature.getMaxResults() != null && feature.getMaxResults() > 0 ? feature.getMaxResults() : profileLimit;
        int fetchLimit = Math.min(profileLimit, maxResults);

        OffsetDateTime startTime = parseOffsetDateTime(firstNonBlank(query.startPublishedDate(), profile.getStartPublishedDate()));
        OffsetDateTime endTime = parseOffsetDateTime(firstNonBlank(query.endPublishedDate(), profile.getEndPublishedDate()));

        List<String> profileLanguages = trimList(profile.getLanguages());
        List<String> domainFilter = resolveProfileDomainFilter(profile);
        return new ProfileSearchOptions(
                providerName,
                provider,
                queryText,
                fetchLimit,
                startTime,
                endTime,
                profileLanguages,
                domainFilter
        );
    }

    private SearchLlmProperties.MarketNewsFeature requireMarketNewsFeature(SearchLlmProperties cfg) {
        if (cfg == null || cfg.getFeatures() == null || cfg.getFeatures().getMarketNews() == null) {
            throw new IllegalStateException("search-llm features.marketNews config is required");
        }
        SearchLlmProperties.MarketNewsFeature feature = cfg.getFeatures().getMarketNews();
        if (feature.getProfiles() == null || feature.getProfiles().isEmpty()) {
            throw new IllegalStateException("search-llm features.marketNews.profiles must not be empty");
        }
        return feature;
    }

    private int resolveFinalLimit(int requestLimit, SearchLlmProperties.MarketNewsFeature feature) {
        if (requestLimit > 0) {
            return requestLimit;
        }
        if (feature.getDefaultLimit() != null && feature.getDefaultLimit() > 0) {
            return feature.getDefaultLimit();
        }
        return 8;
    }

    private String resolveProviderLabel(String requestProvider, Set<String> usedProviders) {
        if (hasText(requestProvider)) {
            return requestProvider.trim();
        }
        if (usedProviders == null || usedProviders.isEmpty()) {
            return "";
        }
        if (usedProviders.size() == 1) {
            return usedProviders.iterator().next();
        }
        return "mixed";
    }

    private String resolveProfileQuery(SearchLlmProperties.MarketNewsProfile profile) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (hasText(profile.getQuery())) {
            unique.add(profile.getQuery().trim());
        }
        if (profile.getQueries() != null) {
            for (String q : profile.getQueries()) {
                if (hasText(q)) {
                    unique.add(q.trim());
                }
            }
        }
        if (unique.isEmpty()) {
            throw new IllegalStateException("marketNews profile " + profile.getName() + " must specify either query or queries with non-empty value");
        }
        return String.join(" OR ", unique);
    }

    private List<String> resolveProfileDomainFilter(SearchLlmProperties.MarketNewsProfile profile) {
        List<String> domains = new ArrayList<>();
        if (profile.getIncludeDomains() != null) {
            for (String domain : profile.getIncludeDomains()) {
                if (hasText(domain)) {
                    domains.add(domain.trim());
                }
            }
        }
        if (profile.getExcludeDomains() != null) {
            for (String domain : profile.getExcludeDomains()) {
                if (hasText(domain)) {
                    domains.add("-" + domain.trim());
                }
            }
        }
        return domains;
    }

    private List<String> trimList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : raw) {
            if (hasText(value)) {
                out.add(value.trim());
            }
        }
        return out;
    }

    private String resolveUpdatedAt(String serverTime, List<MarketNewsItem> items) {
        OffsetDateTime serverTs = parseOffsetDateTime(serverTime);
        if (serverTs != null) {
            return serverTs.toString();
        }
        OffsetDateTime max = null;
        if (items != null) {
            for (MarketNewsItem item : items) {
                OffsetDateTime ts = parseOffsetDateTime(item.timestamp());
                if (ts != null && (max == null || ts.isAfter(max))) {
                    max = ts;
                }
            }
        }
        if (max != null) {
            return max.toString();
        }
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    private String resolveRecencyFilter(OffsetDateTime startTime, OffsetDateTime endTime) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start = startTime == null ? now.minusDays(1) : startTime;
        OffsetDateTime end = endTime == null ? now : endTime;
        if (start.isAfter(end)) {
            OffsetDateTime tmp = start;
            start = end;
            end = tmp;
        }
        long days = Duration.between(start, end).toDays();
        if (days <= 1) {
            return "day";
        }
        if (days <= 7) {
            return "week";
        }
        if (days <= 31) {
            return "month";
        }
        return "year";
    }

    private boolean matchesLanguage(String title, List<String> languages) {
        if (languages == null || languages.isEmpty()) {
            return true;
        }
        if (title == null || title.isBlank()) {
            return false;
        }
        String sample = title.length() > LANGUAGE_DETECTION_SAMPLE_LENGTH
                ? title.substring(0, LANGUAGE_DETECTION_SAMPLE_LENGTH)
                : title;
        boolean hasChinese = sample.codePoints().anyMatch(ch ->
                (ch >= CJK_EXT_A_START && ch <= CJK_EXT_A_END)
                        || (ch >= CJK_UNIFIED_START && ch <= CJK_UNIFIED_END)
        );
        boolean hasLatin = sample.codePoints().anyMatch(ch -> (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'));
        for (String lang : languages) {
            if (!hasText(lang)) {
                continue;
            }
            String normalized = lang.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("zh")) {
                if (hasChinese) {
                    return true;
                }
            } else if (normalized.startsWith("en")) {
                if (hasLatin) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private boolean matchesTimeRange(OffsetDateTime timestamp, OffsetDateTime start, OffsetDateTime end) {
        if (timestamp == null) {
            return start == null && end == null;
        }
        if (start != null && timestamp.isBefore(start)) {
            return false;
        }
        if (end != null && timestamp.isAfter(end)) {
            return false;
        }
        return true;
    }

    private OffsetDateTime parseOffsetDateTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        String raw = value.trim();
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception ignore) {
            log.debug("Failed to parse as OffsetDateTime: {}", raw, ignore);
        }
        try {
            LocalDateTime local = LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return local.atOffset(ZoneOffset.UTC);
        } catch (Exception ignore) {
            log.debug("Failed to parse as LocalDateTime: {}", raw, ignore);
        }
        try {
            LocalDate date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (Exception ignore) {
            log.debug("Failed to parse as LocalDate: {}", raw, ignore);
            return null;
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

    private String generateId(String url, String title, int index) {
        String seed = firstNonBlank(url, title, "news-" + index);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizeKey(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String postJson(SearchLlmProperties.Provider provider, Map<String, Object> body) {
        String baseUrl = provider.getBaseUrl();
        String path = provider.getSearchPath();
        String url = resolveUrl(baseUrl, path);
        if (!hasText(url)) {
            throw new IllegalArgumentException("search provider url missing");
        }
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(requestTimeout(provider)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
            applyAuthHeader(provider, requestBuilder);
            if (provider.getHeaders() != null) {
                for (Map.Entry<String, String> entry : provider.getHeaders().entrySet()) {
                    if (hasText(entry.getKey()) && entry.getValue() != null) {
                        requestBuilder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(connectTimeout(provider)))
                    .build();
            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.error("Search provider {} returned status {} body {}", url, response.statusCode(), response.body());
                throw new IllegalStateException("search provider error: " + response.statusCode());
            }
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("search provider request failed", e);
        }
    }

    int connectTimeout(SearchLlmProperties.Provider provider) {
        if (provider == null || provider.getConnectTimeoutSeconds() == null || provider.getConnectTimeoutSeconds() <= 0) {
            return DEFAULT_CONNECT_TIMEOUT_SECONDS;
        }
        return provider.getConnectTimeoutSeconds();
    }

    int requestTimeout(SearchLlmProperties.Provider provider) {
        if (provider == null || provider.getRequestTimeoutSeconds() == null || provider.getRequestTimeoutSeconds() <= 0) {
            return DEFAULT_REQUEST_TIMEOUT_SECONDS;
        }
        return provider.getRequestTimeoutSeconds();
    }

    private void applyAuthHeader(SearchLlmProperties.Provider provider, HttpRequest.Builder builder) {
        if (provider == null) {
            return;
        }
        String header = provider.getAuthHeader();
        String apiKey = provider.getApiKey();
        if (!hasText(header) || !hasText(apiKey)) {
            return;
        }
        String prefix = provider.getAuthPrefix();
        builder.header(header, (prefix == null ? "" : prefix) + apiKey);
    }

    private String resolveUrl(String baseUrl, String path) {
        if (!hasText(baseUrl)) {
            return "";
        }
        if (hasText(path) && path.startsWith("http")) {
            return path;
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = hasText(path) ? path.trim() : "";
        if (!normalizedPath.isEmpty() && !normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return normalizedBase + normalizedPath;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    public record MarketNewsQuery(String provider,
                                  List<String> languages,
                                  int limit,
                                  String startPublishedDate,
                                  String endPublishedDate) {
    }

    public record MarketNewsResult(List<MarketNewsItem> items, String updatedAt, String provider) {
    }

    public record MarketNewsItem(String id,
                                 String timestamp,
                                 String title,
                                 String source,
                                 String category,
                                 String url) {
    }

    public record MarketNewsResponse(List<MarketNewsItem> items, String updatedAt) {
    }

    record ProfileSearchOptions(String providerName,
                                SearchLlmProperties.Provider provider,
                                String queryText,
                                int fetchLimit,
                                OffsetDateTime startTime,
                                OffsetDateTime endTime,
                                List<String> profileLanguages,
                                List<String> domainFilter) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PerplexitySearchResponse(@JsonProperty("results") List<PerplexitySearchResult> results,
                                           @JsonProperty("server_time") String serverTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PerplexitySearchResult(String title,
                                         String url,
                                         String snippet,
                                         String date,
                                         @JsonProperty("last_updated") String lastUpdated) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExaSearchResponse(@JsonProperty("results") List<ExaSearchResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExaSearchResult(String title,
                                  String url,
                                  String author,
                                  String id,
                                  @JsonProperty("publishedDate") String publishedDate) {
    }
}
