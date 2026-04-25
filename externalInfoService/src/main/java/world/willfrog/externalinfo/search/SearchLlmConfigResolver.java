package world.willfrog.externalinfo.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.externalinfo.config.SearchLlmProperties;
import world.willfrog.externalinfo.service.SearchLlmLocalConfigLoader;

import java.util.HashMap;
import java.util.Map;

/**
 * Search LLM 配置解析器。
 * 本地 JSON 配置优先，未配置时回退到 Spring application.yml / 环境变量。
 */
@Component
@RequiredArgsConstructor
public class SearchLlmConfigResolver {

    private final SearchLlmProperties springProperties;
    private final SearchLlmLocalConfigLoader localConfigLoader;

    public SearchLlmProperties current() {
        return localConfigLoader.current().orElse(springProperties);
    }

    public SearchLlmProperties.WebSearchFeature webSearchFeature() {
        SearchLlmProperties cfg = current();
        if (cfg == null || cfg.getFeatures() == null || cfg.getFeatures().getWebSearch() == null) {
            return new SearchLlmProperties.WebSearchFeature();
        }
        return cfg.getFeatures().getWebSearch();
    }

    public ResolvedBackendConfig resolveBackendConfig(String backendName) {
        SearchLlmProperties cfg = current();
        if (cfg == null || backendName == null || backendName.isBlank()) {
            return null;
        }
        String key = backendName.trim().toLowerCase();
        SearchLlmProperties.Provider provider = cfg.getProviders().get(key);
        SearchLlmProperties.BackendConfig override = null;
        SearchLlmProperties.WebSearchFeature webSearch = cfg.getFeatures() == null ? null : cfg.getFeatures().getWebSearch();
        if (webSearch != null && webSearch.getBackends() != null) {
            override = webSearch.getBackends().get(key);
        }
        if (provider == null && override == null) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        if (provider != null && provider.getHeaders() != null) {
            headers.putAll(provider.getHeaders());
        }
        if (override != null && override.getHeaders() != null) {
            headers.putAll(override.getHeaders());
        }
        return new ResolvedBackendConfig(
                firstText(override == null ? null : override.getBaseUrl(), provider == null ? null : provider.getBaseUrl()),
                firstText(override == null ? null : override.getApiKey(), provider == null ? null : provider.getApiKey()),
                firstText(override == null ? null : override.getAuthHeader(), provider == null ? null : provider.getAuthHeader()),
                firstNullable(override == null ? null : override.getAuthPrefix(), provider == null ? null : provider.getAuthPrefix()),
                headers,
                firstPositive(override == null ? null : override.getConnectTimeoutSeconds(), provider == null ? null : provider.getConnectTimeoutSeconds()),
                firstPositive(override == null ? null : override.getRequestTimeoutSeconds(), provider == null ? null : provider.getRequestTimeoutSeconds())
        );
    }

    private String firstText(String primary, String fallback) {
        return hasText(primary) ? primary : fallback;
    }

    private String firstNullable(String primary, String fallback) {
        return primary != null ? primary : fallback;
    }

    private Integer firstPositive(Integer primary, Integer fallback) {
        return primary != null && primary > 0 ? primary : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record ResolvedBackendConfig(String baseUrl,
                                        String apiKey,
                                        String authHeader,
                                        String authPrefix,
                                        Map<String, String> headers,
                                        Integer connectTimeoutSeconds,
                                        Integer requestTimeoutSeconds) {
    }
}
