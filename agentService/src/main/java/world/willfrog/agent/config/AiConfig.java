package world.willfrog.agent.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import world.willfrog.agent.ai.PlanningAgent;
import world.willfrog.agent.ai.SummarizingAgent;
import world.willfrog.agent.tool.MarketDataTools;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class AiConfig {

    @Value("${langchain4j.open-ai.api-key}")
    private String openAiApiKey;

    @Value("${langchain4j.open-ai.base-url:https://api.openai.com/v1}")
    private String openAiBaseUrl;

    @Value("${langchain4j.open-ai.model-name:gpt-4o}")
    private String modelName;

    @Value("${langchain4j.open-ai.max-tokens:4096}")
    private Integer maxTokens;

    @Value("${langchain4j.open-ai.temperature:0.7}")
    private Double temperature;

    // Reasoning 配置 - 支持 OpenRouter 的 reasoning 参数
    // effort: xhigh, high, medium, low, minimal, none (用于 OpenAI o系列, Grok 等)
    @Value("${langchain4j.open-ai.reasoning.effort:#{null}}")
    private String reasoningEffort;

    // max-tokens: 直接指定 reasoning 使用的最大 token 数 (用于 Anthropic, Gemini 等)
    @Value("${langchain4j.open-ai.reasoning.max-tokens:#{null}}")
    private Integer reasoningMaxTokens;

    // exclude: 是否在响应中排除 reasoning 内容
    @Value("${langchain4j.open-ai.reasoning.exclude:false}")
    private Boolean reasoningExclude;

    // enabled: 显式启用/禁用 reasoning (用于 DeepSeek 等)
    @Value("${langchain4j.open-ai.reasoning.enabled:#{null}}")
    private Boolean reasoningEnabled;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        var builder = OpenAiChatModel.builder()
                .apiKey(openAiApiKey)
                .baseUrl(openAiBaseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(true)
                .logResponses(true);

        // 构建 reasoning 自定义参数 (OpenRouter 兼容)
        Map<String, Object> customParameters = buildReasoningParameters();
        if (!customParameters.isEmpty()) {
            builder.customParameters(customParameters);
        }

        return builder.build();
    }

    /**
     * 构建 OpenRouter reasoning 参数
     * 参考: https://openrouter.ai/docs/guides/best-practices/reasoning-tokens
     */
    private Map<String, Object> buildReasoningParameters() {
        Map<String, Object> customParams = new HashMap<>();
        Map<String, Object> reasoning = new HashMap<>();

        // effort 参数 (OpenAI o系列, Grok)
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            reasoning.put("effort", reasoningEffort);
        }

        // max_tokens 参数 (Anthropic, Gemini)
        if (reasoningMaxTokens != null) {
            reasoning.put("max_tokens", reasoningMaxTokens);
        }

        // exclude 参数
        if (reasoningExclude != null && reasoningExclude) {
            reasoning.put("exclude", true);
        }

        // enabled 参数 (DeepSeek)
        if (reasoningEnabled != null) {
            reasoning.put("enabled", reasoningEnabled);
        }

        if (!reasoning.isEmpty()) {
            customParams.put("reasoning", reasoning);
        }

        return customParams;
    }

    @Bean
    public PlanningAgent planningAgent(ChatLanguageModel chatLanguageModel) {
        return AiServices.builder(PlanningAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }

    @Bean
    public SummarizingAgent summarizingAgent(ChatLanguageModel chatLanguageModel) {
        return AiServices.builder(SummarizingAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.withMaxMessages(20);
    }
}
