package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentAiServiceFactory {

    private final AgentLlmResolver llmResolver;
    private final ObjectMapper objectMapper;
    private final RawHttpLogger httpLogger;
    private final AgentObservabilityService observabilityService;
    private final OpenRouterCostService openRouterCostService;

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

    public ChatLanguageModel buildChatModel(String endpointName, String modelName) {
        return buildChatModelWithProviderOrder(resolveLlm(endpointName, modelName), List.of());
    }

    public AgentLlmResolver.ResolvedLlm resolveLlm(String endpointName, String modelName) {
        return llmResolver.resolve(endpointName, modelName);
    }

    public ChatLanguageModel buildChatModel(AgentLlmResolver.ResolvedLlm resolved) {
        return buildChatModelWithProviderOrder(resolved, List.of());
    }

    public ChatLanguageModel buildChatModelWithTemperature(AgentLlmResolver.ResolvedLlm resolved, Double temperatureOverride) {
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

    public ChatLanguageModel buildChatModelWithProviderOrderAndTemperature(AgentLlmResolver.ResolvedLlm resolved,
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
                    resolved.endpointName()
            );
        }
        return buildChatModelWithTemperature(resolved, temperatureOverride);
    }

    public ChatLanguageModel buildChatModelWithProviderOrder(AgentLlmResolver.ResolvedLlm resolved, List<String> providerOrder) {
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
                    resolved.endpointName()
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
        if (openRouterHttpReferer != null && !openRouterHttpReferer.isBlank()) {
            headers.put("HTTP-Referer", openRouterHttpReferer);
        }
        if (openRouterTitle != null && !openRouterTitle.isBlank()) {
            headers.put("X-Title", openRouterTitle);
        }
        return headers;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private ChatLanguageModel buildDashScopeChatModel(AgentLlmResolver.ResolvedLlm resolved,
                                                      String apiKey,
                                                      double finalTemperature) {
        return new DashScopeChatModel(
                objectMapper,
                resolveDashScopeBaseUrl(resolved),
                apiKey,
                resolved.modelName(),
                finalTemperature,
                maxTokens,
                httpLogger,
                observabilityService,
                resolved.endpointName()
        );
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
