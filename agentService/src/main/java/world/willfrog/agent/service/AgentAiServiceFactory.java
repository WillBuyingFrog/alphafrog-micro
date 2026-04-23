package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.AgentLlmProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentAiServiceFactory {

    private final AgentLlmResolver llmResolver;
    private final AgentLlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final RawHttpLogger httpLogger;
    private final AgentObservabilityService observabilityService;
    private final OpenRouterCostService openRouterCostService;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    @Value("${langchain4j.open-ai.api-key}")
    private String openAiApiKey;

    @Value("${langchain4j.open-ai.max-tokens:4096}")
    private Integer maxTokens;

    @Value("${langchain4j.open-ai.temperature:0.7}")
    private Double temperature;

    @Value("${agent.llm.openrouter.http-referer:}")
    private String openRouterHttpReferer;

    @Value("${agent.llm.openrouter.title:}")
    private String openRouterTitle;

    public ChatModel buildChatModel(String endpointName, String modelName) {
        return buildChatModelWithProviderOrder(resolveLlm(endpointName, modelName), List.of());
    }

    public AgentLlmResolver.ResolvedLlm resolveLlm(String endpointName, String modelName) {
        return llmResolver.resolve(endpointName, modelName);
    }

    public AgentLlmResolver.ResolvedLlm resolveLlmForPlanning(String endpointName, String modelName) {
        return llmResolver.resolveForPlanning(endpointName, modelName);
    }

    public ChatModel buildChatModel(AgentLlmResolver.ResolvedLlm resolved) {
        return buildChatModelWithProviderOrder(resolved, List.of());
    }

    public ChatModel buildChatModelWithTemperature(AgentLlmResolver.ResolvedLlm resolved, Double temperatureOverride) {
        boolean debugEnabled = log.isDebugEnabled();
        String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
        }
        double finalTemperature = temperatureOverride == null ? (temperature == null ? 0.7D : temperature) : temperatureOverride;
        if (isDashScopeEndpoint(resolved)) {
            return buildDashScopeChatModel(resolved, apiKey, finalTemperature);
        }
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(resolved.baseUrl())
                .modelName(resolved.modelName())
                .maxTokens(maxTokens)
                .temperature(finalTemperature)
                .logRequests(debugEnabled)
                .logResponses(debugEnabled);

        Map<String, String> headers = buildCustomHeaders(resolved.baseUrl());
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }
        return builder.build();
    }

    public ChatModel buildChatModelWithProviderOrderAndTemperature(AgentLlmResolver.ResolvedLlm resolved,
                                                                           List<String> providerOrder,
                                                                           Double temperatureOverride) {
        List<String> normalizedProviderOrder = sanitizeProviderOrder(providerOrder);
        // ALP-25: 对所有端点使用 OpenRouterProviderRoutedChatModel 以支持 HTTP 捕获
        if (isDashScopeEndpoint(resolved)) {
            String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
            if (isBlank(apiKey)) {
                throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
            }
            double finalTemperature = temperatureOverride == null ? (temperature == null ? 0.7D : temperature) : temperatureOverride;
            return buildDashScopeChatModel(resolved, apiKey, finalTemperature);
        }
        if (shouldUseProviderRoutedModel(resolved)) {
            String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
            if (isBlank(apiKey)) {
                throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
            }
            double finalTemperature = temperatureOverride == null ? (temperature == null ? 0.7D : temperature) : temperatureOverride;
            Map<String, String> headers = buildCustomHeaders(resolved.baseUrl());
            return new OpenRouterProviderRoutedChatModel(
                    objectMapper,
                    resolved.baseUrl(),
                    apiKey,
                    headers,
                    resolved.modelName(),
                    finalTemperature,
                    maxTokens,
                    normalizedProviderOrder,
                    httpLogger,
                    observabilityService,
                    openRouterCostService,
                    resolved.endpointName(),
                    localConfigLoader
            );
        }
        return buildChatModelWithTemperature(resolved, temperatureOverride);
    }

    public ChatModel buildChatModelWithProviderOrder(AgentLlmResolver.ResolvedLlm resolved, List<String> providerOrder) {
        boolean debugEnabled = log.isDebugEnabled();
        String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
        }
        List<String> normalizedProviderOrder = sanitizeProviderOrder(providerOrder);
        // ALP-25: 对所有端点使用 OpenRouterProviderRoutedChatModel 以支持 HTTP 捕获
        if (isDashScopeEndpoint(resolved)) {
            return buildDashScopeChatModel(resolved, apiKey, temperature);
        }
        if (shouldUseProviderRoutedModel(resolved)) {
            Map<String, String> headers = buildCustomHeaders(resolved.baseUrl());
            return new OpenRouterProviderRoutedChatModel(
                    objectMapper,
                    resolved.baseUrl(),
                    apiKey,
                    headers,
                    resolved.modelName(),
                    temperature,
                    maxTokens,
                    normalizedProviderOrder,
                    httpLogger,
                    observabilityService,
                    openRouterCostService,
                    resolved.endpointName(),
                    localConfigLoader
            );
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(resolved.baseUrl())
                .modelName(resolved.modelName())
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(debugEnabled)
                .logResponses(debugEnabled);

        Map<String, String> headers = buildCustomHeaders(resolved.baseUrl());
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }
        return builder.build();
    }

    private Map<String, String> buildCustomHeaders(String baseUrl) {
        if (baseUrl == null || !baseUrl.contains("openrouter.ai")) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>();
        
        // 优先从热加载配置读取，其次从 @Value 注解读取
        String httpReferer = getOpenRouterHttpReferer();
        String title = getOpenRouterTitle();
        String categories = getOpenRouterCategories();
        
        // HTTP-Referer 是必需的（OpenRouter App Attribution）
        if (httpReferer != null && !httpReferer.isBlank()) {
            headers.put("HTTP-Referer", httpReferer);
        }
        // X-OpenRouter-Title 是新的标准 header，X-Title 仍兼容
        if (title != null && !title.isBlank()) {
            headers.put("X-OpenRouter-Title", title);
            // 同时发送 X-Title 以确保兼容性
            headers.put("X-Title", title);
        }
        // 可选的分类信息
        if (categories != null && !categories.isBlank()) {
            headers.put("X-OpenRouter-Categories", categories);
        }
        return headers;
    }
    
    /**
     * 获取 OpenRouter HTTP Referer，优先从热加载配置读取。
     * <p>这是 OpenRouter App Attribution 的必需字段。</p>
     */
    private String getOpenRouterHttpReferer() {
        if (localConfigLoader != null) {
            String fromConfig = localConfigLoader.current()
                    .map(cfg -> cfg.getOpenrouter())
                    .map(or -> or.getHttpReferer())
                    .filter(v -> v != null && !v.isBlank())
                    .orElse(null);
            if (fromConfig != null) {
                return fromConfig;
            }
        }
        return openRouterHttpReferer;
    }
    
    /**
     * 获取 OpenRouter Title，优先从热加载配置读取。
     * <p>建议使用新的 X-OpenRouter-Title header。</p>
     */
    private String getOpenRouterTitle() {
        if (localConfigLoader != null) {
            String fromConfig = localConfigLoader.current()
                    .map(cfg -> cfg.getOpenrouter())
                    .map(or -> or.getTitle())
                    .filter(v -> v != null && !v.isBlank())
                    .orElse(null);
            if (fromConfig != null) {
                return fromConfig;
            }
        }
        return openRouterTitle;
    }
    
    /**
     * 获取 OpenRouter Categories，优先从热加载配置读取。
     * <p>可选的分类信息，如 "cloud-agent,programming-app"。</p>
     */
    private String getOpenRouterCategories() {
        if (localConfigLoader != null) {
            return localConfigLoader.current()
                    .map(cfg -> cfg.getOpenrouter())
                    .map(or -> or.getCategories())
                    .filter(v -> v != null && !v.isBlank())
                    .orElse(null);
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private ChatModel buildDashScopeChatModel(AgentLlmResolver.ResolvedLlm resolved,
                                                      String apiKey,
                                                      double finalTemperature) {
        boolean enableThinking = resolveEnableThinking(resolved);
        return new DashScopeChatModel(
                objectMapper,
                resolveDashScopeBaseUrl(resolved),
                apiKey,
                resolved.modelName(),
                finalTemperature,
                maxTokens,
                httpLogger,
                observabilityService,
                resolved.endpointName(),
                enableThinking,
                localConfigLoader
        );
    }

    /**
     * 从 endpoint 模型元数据中解析 thinking 开关。
     * 默认开启；若模型 features 中不包含 "thinking" 则关闭。
     */
    private boolean resolveEnableThinking(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved == null || isBlank(resolved.endpointName()) || isBlank(resolved.modelName())) {
            return true; // 默认开启
        }
        AgentLlmProperties.Endpoint endpoint = null;
        if (llmProperties != null && llmProperties.getEndpoints() != null) {
            endpoint = llmProperties.getEndpoints().get(resolved.endpointName());
        }
        if (endpoint == null) {
            // fallback: 尝试从热加载配置读取
            endpoint = localConfigLoader.current()
                    .map(cfg -> cfg.getEndpoints() != null ? cfg.getEndpoints().get(resolved.endpointName()) : null)
                    .orElse(null);
        }
        if (endpoint == null || endpoint.getModels() == null) {
            return true; // 默认开启
        }
        AgentLlmProperties.ModelMetadata meta = endpoint.getModels().get(resolved.modelName());
        if (meta == null || meta.getFeatures() == null) {
            return true; // 默认开启
        }
        return meta.getFeatures().contains("thinking");
    }

    /**
     * 判断是否使用 OpenRouterProviderRoutedChatModel（支持 HTTP 捕获）
     * ALP-25: 对所有 OpenAI 兼容端点启用 HTTP 捕获
     */
    private boolean shouldUseProviderRoutedModel(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved == null || isBlank(resolved.baseUrl())) {
            return false;
        }
        // 支持所有 OpenAI 兼容 API：OpenRouter、Fireworks、OpenAI 等
        String baseUrl = resolved.baseUrl().toLowerCase();
        if (baseUrl.contains("dashscope")) {
            return false;
        }
        return baseUrl.contains("openrouter.ai") 
            || baseUrl.contains("fireworks.ai")
            || baseUrl.contains("openai.com")
            || baseUrl.contains("api/v1");  // OpenAI 兼容 API 通用路径
    }
    
    /**
     * @deprecated 使用 {@link #shouldUseProviderRoutedModel} 替代
     */
    @Deprecated
    private boolean isOpenRouterEndpoint(AgentLlmResolver.ResolvedLlm resolved) {
        return shouldUseProviderRoutedModel(resolved);
    }

    private List<String> sanitizeProviderOrder(List<String> providerOrder) {
        if (providerOrder == null || providerOrder.isEmpty()) {
            return List.of();
        }
        List<String> providers = new java.util.ArrayList<>();
        for (String provider : providerOrder) {
            if (provider == null) {
                continue;
            }
            String value = provider.trim();
            if (!value.isBlank()) {
                providers.add(value);
            }
        }
        return providers;
    }

    private boolean isDashScopeEndpoint(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved == null) {
            return false;
        }
        String endpointName = resolved.endpointName();
        if (endpointName != null && endpointName.trim().equalsIgnoreCase("dashscope")) {
            return true;
        }
        String baseUrl = resolved.baseUrl();
        return baseUrl != null && baseUrl.toLowerCase().contains("dashscope");
    }

    private String resolveDashScopeBaseUrl(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved != null && !isBlank(resolved.baseUrl())) {
            return resolved.baseUrl();
        }
        String region = resolved == null ? null : resolved.region();
        String normalized = region == null ? "" : region.trim().toLowerCase();
        return switch (normalized) {
            case "us" -> "https://dashscope-us.aliyuncs.com/compatible-mode/v1";
            case "cn" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "singapore" -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
            default -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
        };
    }
}
