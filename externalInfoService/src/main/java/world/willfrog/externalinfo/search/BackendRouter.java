package world.willfrog.externalinfo.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.externalinfo.config.SearchLlmProperties;
import world.willfrog.externalinfo.search.backend.SearchBackend;

import java.util.List;

/**
 * 搜索后端路由。
 * 按优先级将 scene + strength 映射到具体的 backend 实例和参数 preset。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackendRouter {

    private final SearchLlmProperties properties;
    private final List<SearchBackend> backends;

    /**
     * 解析并返回匹配的 backend 及 preset。
     * 优先级：request.backend > scene 对应 preset > defaultPreset。
     *
     * @param request WebSearchRequest
     * @return ResolvedBackend 包含 backend 实例和匹配的 preset
     * @throws IllegalStateException 找不到可用 backend 时抛出
     */
    public ResolvedBackend resolve(WebSearchRequest request) {
        String targetBackend = null;
        SearchLlmProperties.WebSearchPreset matchedPreset = null;

        // 优先级1：如果 request.backend 非空且对应 backend 存在
        String reqBackend = request.getBackend();
        if (hasText(reqBackend)) {
            targetBackend = reqBackend.trim().toLowerCase();
            matchedPreset = findPresetByBackend(targetBackend);
        }

        // 优先级2：按 scene 查找匹配的 preset
        if (targetBackend == null) {
            String scene = request.getScene();
            if (hasText(scene)) {
                matchedPreset = findPresetByScene(scene.trim());
                if (matchedPreset != null && hasText(matchedPreset.getBackend())) {
                    targetBackend = matchedPreset.getBackend().trim().toLowerCase();
                }
            }
        }

        // 优先级3：fallback 到 defaultPreset
        if (targetBackend == null) {
            SearchLlmProperties.WebSearchFeature feature = getWebSearchFeature();
            String defaultPresetName = feature.getDefaultPreset();
            if (hasText(defaultPresetName)) {
                matchedPreset = feature.getPresets().get(defaultPresetName);
                if (matchedPreset != null && hasText(matchedPreset.getBackend())) {
                    targetBackend = matchedPreset.getBackend().trim().toLowerCase();
                }
            }
        }

        if (targetBackend == null) {
            throw new IllegalStateException(
                    "无法解析可用的搜索后端，request backend=" + request.getBackend()
                            + ", scene=" + request.getScene());
        }

        SearchBackend backend = findBackendByName(targetBackend);
        if (backend == null) {
            throw new IllegalStateException("未找到名为 '" + targetBackend + "' 的搜索后端实现");
        }

        // 验证 scene 和 strength 兼容性（仅打印警告，不阻断）
        String scene = hasText(request.getScene()) ? request.getScene().trim()
                : (matchedPreset != null ? matchedPreset.getScene() : "");
        String strength = hasText(request.getStrength()) ? request.getStrength().trim()
                : (matchedPreset != null ? matchedPreset.getStrength() : "");

        if (hasText(scene) && !backend.supportsScene(scene)) {
            log.warn("后端 '{}' 不支持场景 '{}'", targetBackend, scene);
        }
        if (hasText(strength) && !backend.supportsStrength(strength)) {
            log.warn("后端 '{}' 不支持强度档位 '{}'", targetBackend, strength);
        }

        return new ResolvedBackend(backend, matchedPreset);
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

    private SearchLlmProperties.WebSearchPreset findPresetByBackend(String backendName) {
        SearchLlmProperties.WebSearchFeature feature = getWebSearchFeature();
        if (feature == null || feature.getPresets() == null) {
            return null;
        }
        for (SearchLlmProperties.WebSearchPreset preset : feature.getPresets().values()) {
            if (preset != null && hasText(preset.getBackend())
                    && backendName.equalsIgnoreCase(preset.getBackend().trim())) {
                return preset;
            }
        }
        return null;
    }

    private SearchLlmProperties.WebSearchPreset findPresetByScene(String scene) {
        SearchLlmProperties.WebSearchFeature feature = getWebSearchFeature();
        if (feature == null || feature.getPresets() == null) {
            return null;
        }
        for (SearchLlmProperties.WebSearchPreset preset : feature.getPresets().values()) {
            if (preset != null && hasText(preset.getScene())
                    && scene.equalsIgnoreCase(preset.getScene().trim())) {
                return preset;
            }
        }
        return null;
    }

    private SearchLlmProperties.WebSearchFeature getWebSearchFeature() {
        if (properties == null || properties.getFeatures() == null) {
            return null;
        }
        return properties.getFeatures().getWebSearch();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 路由解析结果：包含选中的 backend 实例及对应 preset。
     */
    public record ResolvedBackend(SearchBackend backend, SearchLlmProperties.WebSearchPreset preset) {
    }
}
