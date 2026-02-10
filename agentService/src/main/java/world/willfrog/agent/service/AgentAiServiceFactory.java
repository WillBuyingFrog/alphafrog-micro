package world.willfrog.agent.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentAiServiceFactory {

    private final AgentLlmResolver llmResolver;

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
        return buildChatModel(resolveLlm(endpointName, modelName));
    }

    public AgentLlmResolver.ResolvedLlm resolveLlm(String endpointName, String modelName) {
        return llmResolver.resolve(endpointName, modelName);
    }

    public ChatLanguageModel buildChatModel(AgentLlmResolver.ResolvedLlm resolved) {
        boolean debugEnabled = log.isDebugEnabled();
        String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
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
}
