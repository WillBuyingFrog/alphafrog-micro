package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.OpenAiClient;
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

    public ChatLanguageModel buildChatModelWithProviderOrder(AgentLlmResolver.ResolvedLlm resolved, List<String> providerOrder) {
        boolean debugEnabled = log.isDebugEnabled();
        String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
        }
        List<String> normalizedProviderOrder = sanitizeProviderOrder(providerOrder);
        if (isOpenRouterEndpoint(resolved) && !normalizedProviderOrder.isEmpty()) {
            OpenAiClient.Builder<?, ?> clientBuilder = OpenAiClient.builder()
                    .baseUrl(resolved.baseUrl())
                    .openAiApiKey(apiKey)
                    .logRequests(debugEnabled)
                    .logResponses(debugEnabled);
            Map<String, String> headers = buildCustomHeaders(resolved.baseUrl());
            if (!headers.isEmpty()) {
                clientBuilder.customHeaders(headers);
            }
            OpenAiClient client = clientBuilder.build();
            return new OpenRouterProviderRoutedChatModel(
                    client,
                    objectMapper,
                    resolved.modelName(),
                    temperature,
                    maxTokens,
                    normalizedProviderOrder
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

    private boolean isOpenRouterEndpoint(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved == null) {
            return false;
        }
        if (!isBlank(resolved.endpointName()) && "openrouter".equalsIgnoreCase(resolved.endpointName().trim())) {
            return true;
        }
        return resolved.baseUrl() != null && resolved.baseUrl().contains("openrouter.ai");
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
}
