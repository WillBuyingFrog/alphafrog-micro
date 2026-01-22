package world.willfrog.agent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentLlmResolver {

    private final AgentLlmProperties properties;

    /**
     * 根据请求中的 endpoint/model 名称解析出实际的 baseUrl 与模型名。
     *
     * @param endpointName 请求携带的端点名（允许为空，使用默认值）
     * @param modelName    请求携带的模型名（允许为空，使用默认值）
     * @return 解析后的 LLM 配置
     */
    public ResolvedLlm resolve(String endpointName, String modelName) {
        String endpointKey = normalize(endpointName);
        if (endpointKey == null) {
            endpointKey = normalize(properties.getDefaultEndpoint());
        }
        if (endpointKey == null && !properties.getEndpoints().isEmpty()) {
            endpointKey = properties.getEndpoints().keySet().iterator().next();
        }

        Map<String, AgentLlmProperties.Endpoint> endpoints = properties.getEndpoints();
        AgentLlmProperties.Endpoint endpoint = endpointKey == null ? null : endpoints.get(endpointKey);
        if (endpoint == null || isBlank(endpoint.getBaseUrl())) {
            throw new IllegalArgumentException("endpoint_name 未配置或未找到: " + endpointKey);
        }

        String model = normalize(modelName);
        if (model == null) {
            model = normalize(properties.getDefaultModel());
        }
        if (model == null && !properties.getModels().isEmpty()) {
            model = properties.getModels().get(0);
        }
        if (model == null) {
            throw new IllegalArgumentException("model_name 未配置");
        }
        if (!properties.getModels().isEmpty() && !properties.getModels().contains(model)) {
            throw new IllegalArgumentException("model_name 不在允许列表: " + model);
        }

        return new ResolvedLlm(endpointKey, endpoint.getBaseUrl(), model);
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

    public record ResolvedLlm(String endpointName, String baseUrl, String modelName) {
    }
}
