package world.willfrog.externalinfo.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.externalinfo.config.SearchLlmProperties;
import world.willfrog.externalinfo.search.backend.SearchBackend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 搜索后端路由。
 * 按优先级将 scene + strength 映射到具体的 backend 实例和参数 preset。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackendRouter {

    private static final int DEFAULT_MAX_RESULTS = 5;

    private final SearchLlmConfigResolver configResolver;
    private final List<SearchBackend> backends;

    /**
     * 解析并返回匹配的 backend 及 preset。
     * 优先级：显式 backend / preset > scene 匹配 preset > defaultPreset。
     *
     * @param request WebSearchRequest
     * @return ResolvedBackend 包含 backend 实例和匹配的 preset
     * @throws IllegalStateException 找不到可用 backend 时抛出
     */
    public ResolvedBackend resolve(WebSearchRequest request) {
        SearchLlmProperties.WebSearchFeature feature = getWebSearchFeature();
        SearchLlmProperties.WebSearchPreset matchedPreset = null;
        String targetBackend = "";

        String reqBackendOrPreset = request.getBackend();
        if (hasText(reqBackendOrPreset)) {
            String candidate = reqBackendOrPreset.trim();
            matchedPreset = findPresetByName(candidate);
            targetBackend = matchedPreset != null && hasText(matchedPreset.getBackend())
                    ? matchedPreset.getBackend().trim().toLowerCase()
                    : candidate.toLowerCase();
        }

        if (!hasText(targetBackend)) {
            matchedPreset = findPresetByScene(request.getScene(), feature.getDefaultPreset());
            if (matchedPreset != null && hasText(matchedPreset.getBackend())) {
                targetBackend = matchedPreset.getBackend().trim().toLowerCase();
            }
        }

        if (!hasText(targetBackend)) {
            String defaultPresetName = feature.getDefaultPreset();
            if (hasText(defaultPresetName)) {
                matchedPreset = findPresetByName(defaultPresetName);
                if (matchedPreset != null && hasText(matchedPreset.getBackend())) {
                    targetBackend = matchedPreset.getBackend().trim().toLowerCase();
                }
            }
        }

        if (!hasText(targetBackend)) {
            throw new IllegalStateException(
                    "无法解析可用的搜索后端，request backend=" + request.getBackend()
                            + ", scene=" + request.getScene());
        }

        SearchBackend backend = findBackendByName(targetBackend);
        if (backend == null) {
            throw new IllegalStateException("未找到名为 '" + targetBackend + "' 的搜索后端实现");
        }

        String scene = firstText(request.getScene(), matchedPreset == null ? null : matchedPreset.getScene(), "general");
        String strength = firstText(request.getStrength(), matchedPreset == null ? null : matchedPreset.getStrength(), "standard");

        if (hasText(scene) && !backend.supportsScene(scene)) {
            log.warn("后端 '{}' 不支持场景 '{}'", targetBackend, scene);
        }
        if (hasText(strength) && !backend.supportsStrength(strength)) {
            log.warn("后端 '{}' 不支持强度档位 '{}'", targetBackend, strength);
        }

        SearchLlmConfigResolver.ResolvedBackendConfig backendConfig = configResolver.resolveBackendConfig(targetBackend);
        WebSearchExecutionContext context = new WebSearchExecutionContext(
                request,
                targetBackend,
                scene,
                strength,
                resolveMaxResults(request, matchedPreset),
                firstText(null, matchedPreset == null ? null : matchedPreset.getTimeRange(), ""),
                mergeList(matchedPreset == null ? null : matchedPreset.getIncludeDomains()),
                mergeList(matchedPreset == null ? null : matchedPreset.getExcludeDomains()),
                matchedPreset,
                backendConfig
        );
        return new ResolvedBackend(backend, matchedPreset, context);
    }

    private SearchBackend findBackendByName(String name) {
        if (backends == null || name == null) {
            return null;
        }
        for (SearchBackend backend : backends) {
            if (backend != null && name.equalsIgnoreCase(backend.name())) {
                return backend;
            }
        }
        return null;
    }

    private SearchLlmProperties.WebSearchPreset findPresetByName(String name) {
        SearchLlmProperties.WebSearchFeature feature = getWebSearchFeature();
        if (feature == null || feature.getPresets() == null) {
            return null;
        }
        SearchLlmProperties.WebSearchPreset preset = feature.getPresets().get(name);
        if (preset != null) {
            return preset;
        }
        return feature.getPresets().get(name.trim().toLowerCase());
    }

    private SearchLlmProperties.WebSearchPreset findPresetByScene(String scene, String defaultPresetName) {
        if (!hasText(scene)) {
            return null;
        }
        SearchLlmProperties.WebSearchFeature feature = getWebSearchFeature();
        if (feature == null || feature.getPresets() == null || feature.getPresets().isEmpty()) {
            return null;
        }
        String normalizedScene = scene.trim();
        SearchLlmProperties.WebSearchPreset defaultPreset = findPresetByName(defaultPresetName);
        if (defaultPreset != null && normalizedScene.equalsIgnoreCase(defaultPreset.getScene())) {
            return defaultPreset;
        }
        return feature.getPresets().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .filter(preset -> preset != null && normalizedScene.equalsIgnoreCase(preset.getScene()))
                .findFirst()
                .orElse(null);
    }

    private SearchLlmProperties.WebSearchFeature getWebSearchFeature() {
        return configResolver.webSearchFeature();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String firstText(String primary, String fallback, String defaultValue) {
        if (hasText(primary)) {
            return primary.trim();
        }
        if (hasText(fallback)) {
            return fallback.trim();
        }
        return defaultValue;
    }

    private int resolveMaxResults(WebSearchRequest request, SearchLlmProperties.WebSearchPreset preset) {
        if (request.getMaxResults() > 0) {
            return request.getMaxResults();
        }
        if (preset != null && preset.getMaxResults() != null && preset.getMaxResults() > 0) {
            return preset.getMaxResults();
        }
        return DEFAULT_MAX_RESULTS;
    }

    private List<String> mergeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (hasText(value)) {
                result.add(value.trim());
            }
        }
        return List.copyOf(result);
    }

    /**
     * 路由解析结果：包含选中的 backend 实例及对应 preset。
     */
    public record ResolvedBackend(SearchBackend backend,
                                  SearchLlmProperties.WebSearchPreset preset,
                                  WebSearchExecutionContext context) {
    }
}
