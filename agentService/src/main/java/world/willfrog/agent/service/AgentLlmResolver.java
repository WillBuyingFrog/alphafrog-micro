package world.willfrog.agent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;

import java.util.ArrayList;
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
        List<String> models = chooseModels(properties, local);

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
        if (endpoint == null || isBlank(endpoint.getBaseUrl())) {
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

        return new ResolvedLlm(endpointKey, endpoint.getBaseUrl(), model, normalize(endpoint.getApiKey()));
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
            }
        }
        return merged;
    }

    private AgentLlmProperties.Endpoint copyEndpoint(AgentLlmProperties.Endpoint source) {
        AgentLlmProperties.Endpoint target = new AgentLlmProperties.Endpoint();
        if (source != null) {
            target.setBaseUrl(source.getBaseUrl());
            target.setApiKey(source.getApiKey());
        }
        return target;
    }

    private List<String> chooseModels(AgentLlmProperties base, AgentLlmProperties local) {
        if (local != null && local.getModels() != null && !local.getModels().isEmpty()) {
            return local.getModels();
        }
        if (base != null && base.getModels() != null) {
            return base.getModels();
        }
        return new ArrayList<>();
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

    public record ResolvedLlm(String endpointName, String baseUrl, String modelName, String apiKey) {
    }
}
