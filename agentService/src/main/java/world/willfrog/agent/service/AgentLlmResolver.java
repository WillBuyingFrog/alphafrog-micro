package world.willfrog.agent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentLlmResolver {

    private final AgentLlmProperties properties;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    /**
     * 根据请求中的 endpoint/model 名称解析出实际的 baseUrl 与模型名。
     *
     * @param endpointName 请求携带的端点名（允许为空，使用默认值）
     * @param modelName    请求携带的模型名（允许为空，使用默认值）
     * @return 解析后的 LLM 配置
     */
    public ResolvedLlm resolve(String endpointName, String modelName) {
        AgentLlmProperties local = localConfigLoader.current().orElse(null);
        Map<String, AgentLlmProperties.Endpoint> endpoints = mergeEndpoints(properties, local);
        List<String> models = chooseModels(properties, local, endpoints);

        String endpointKey = normalize(endpointName);
        if (endpointKey == null) {
            endpointKey = firstNonBlank(
                    local == null ? null : local.getDefaultEndpoint(),
                    properties.getDefaultEndpoint()
            );
        }
        if (endpointKey == null && !endpoints.isEmpty()) {
            endpointKey = endpoints.keySet().iterator().next();
        }

        AgentLlmProperties.Endpoint endpoint = endpointKey == null ? null : endpoints.get(endpointKey);
        String baseUrl = endpoint == null ? null : endpoint.getBaseUrl();
        String region = endpoint == null ? null : normalize(endpoint.getRegion());
        if (endpoint == null || (isBlank(baseUrl) && isBlank(region))) {
            throw new IllegalArgumentException("endpoint_name 未配置或未找到: " + endpointKey);
        }

        String model = normalize(modelName);
        if (model == null) {
            model = firstNonBlank(
                    local == null ? null : local.getDefaultModel(),
                    properties.getDefaultModel()
            );
        }
        if (model == null && !models.isEmpty()) {
            model = models.get(0);
        }
        if (model == null) {
            throw new IllegalArgumentException("model_name 未配置");
        }
        if (!models.isEmpty() && !models.contains(model)) {
            throw new IllegalArgumentException("model_name 不在允许列表: " + model);
        }

        return new ResolvedLlm(endpointKey, baseUrl, model, normalize(endpoint.getApiKey()), region);
    }

    private Map<String, AgentLlmProperties.Endpoint> mergeEndpoints(AgentLlmProperties base, AgentLlmProperties local) {
        Map<String, AgentLlmProperties.Endpoint> merged = new LinkedHashMap<>();
        if (base != null && base.getEndpoints() != null) {
            for (Map.Entry<String, AgentLlmProperties.Endpoint> entry : base.getEndpoints().entrySet()) {
                merged.put(entry.getKey(), copyEndpoint(entry.getValue()));
            }
        }
        if (local != null && local.getEndpoints() != null) {
            for (Map.Entry<String, AgentLlmProperties.Endpoint> entry : local.getEndpoints().entrySet()) {
                AgentLlmProperties.Endpoint localEp = entry.getValue();
                AgentLlmProperties.Endpoint target = merged.get(entry.getKey());
                if (target == null) {
                    target = new AgentLlmProperties.Endpoint();
                    merged.put(entry.getKey(), target);
                }
                if (localEp != null && !isBlank(localEp.getBaseUrl())) {
                    target.setBaseUrl(localEp.getBaseUrl());
                }
                if (localEp != null && !isBlank(localEp.getApiKey())) {
                    target.setApiKey(localEp.getApiKey());
                }
                if (localEp != null && !isBlank(localEp.getRegion())) {
                    target.setRegion(localEp.getRegion());
                }
                if (localEp != null && localEp.getModels() != null && !localEp.getModels().isEmpty()) {
                    Map<String, AgentLlmProperties.ModelMetadata> mergedModels = target.getModels();
                    for (Map.Entry<String, AgentLlmProperties.ModelMetadata> modelEntry : localEp.getModels().entrySet()) {
                        String modelId = normalize(modelEntry.getKey());
                        if (modelId == null) {
                            continue;
                        }
                        mergedModels.put(modelId, copyMetadata(modelEntry.getValue()));
                    }
                    target.setModels(mergedModels);
                }
            }
        }
        return merged;
    }

    private AgentLlmProperties.Endpoint copyEndpoint(AgentLlmProperties.Endpoint source) {
        AgentLlmProperties.Endpoint target = new AgentLlmProperties.Endpoint();
        if (source != null) {
            target.setBaseUrl(source.getBaseUrl());
            target.setApiKey(source.getApiKey());
            target.setRegion(source.getRegion());
            if (source.getModels() != null && !source.getModels().isEmpty()) {
                Map<String, AgentLlmProperties.ModelMetadata> copied = new LinkedHashMap<>();
                for (Map.Entry<String, AgentLlmProperties.ModelMetadata> entry : source.getModels().entrySet()) {
                    String modelId = normalize(entry.getKey());
                    if (modelId == null) {
                        continue;
                    }
                    copied.put(modelId, copyMetadata(entry.getValue()));
                }
                target.setModels(copied);
            }
        }
        return target;
    }

    private List<String> chooseModels(AgentLlmProperties base,
                                      AgentLlmProperties local,
                                      Map<String, AgentLlmProperties.Endpoint> endpoints) {
        List<String> source = local != null && local.getModels() != null && !local.getModels().isEmpty()
                ? local.getModels()
                : (base == null ? List.of() : base.getModels());
        LinkedHashSet<String> models = new LinkedHashSet<>();
        for (String model : source == null ? List.<String>of() : source) {
            String normalized = normalize(model);
            if (normalized != null) {
                models.add(normalized);
            }
        }
        if (models.isEmpty() && endpoints != null && !endpoints.isEmpty()) {
            for (AgentLlmProperties.Endpoint endpoint : endpoints.values()) {
                if (endpoint == null || endpoint.getModels() == null) {
                    continue;
                }
                for (String modelId : endpoint.getModels().keySet()) {
                    String normalized = normalize(modelId);
                    if (normalized != null) {
                        models.add(normalized);
                    }
                }
            }
        }
        return new ArrayList<>(models);
    }

    private String firstNonBlank(String first, String second) {
        String v1 = normalize(first);
        if (v1 != null) {
            return v1;
        }
        return normalize(second);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private AgentLlmProperties.ModelMetadata copyMetadata(AgentLlmProperties.ModelMetadata source) {
        AgentLlmProperties.ModelMetadata target = new AgentLlmProperties.ModelMetadata();
        if (source != null) {
            target.setDisplayName(source.getDisplayName());
            target.setBaseRate(source.getBaseRate());
            target.setFeatures(source.getFeatures());
            target.setValidProviders(source.getValidProviders());
        }
        return target;
    }

    public record ResolvedLlm(String endpointName, String baseUrl, String modelName, String apiKey, String region) {
    }
}
