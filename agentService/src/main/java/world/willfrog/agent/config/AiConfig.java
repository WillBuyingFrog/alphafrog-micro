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

@Configuration
public class AiConfig {

    @Value("${langchain4j.open-ai.api-key}")
    private String openAiApiKey;

    @Value("${langchain4j.open-ai.base-url:https://api.openai.com/v1}")
    private String openAiBaseUrl;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(openAiApiKey)
                .baseUrl(openAiBaseUrl)
                .modelName("gpt-4-1106-preview") // Or a cheaper model for dev
                .logRequests(true)
                .logResponses(true)
                .build();
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
    
    // Example of an agent that uses tools
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.withMaxMessages(20);
    }
}
