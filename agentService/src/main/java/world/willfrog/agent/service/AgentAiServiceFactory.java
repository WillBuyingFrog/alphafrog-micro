package world.willfrog.agent.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.ai.PlanningAgent;
import world.willfrog.agent.ai.SummarizingAgent;

@Component
@RequiredArgsConstructor
public class AgentAiServiceFactory {

    private final AgentLlmResolver llmResolver;

    @Value("${langchain4j.open-ai.api-key}")
    private String openAiApiKey;

    @Value("${langchain4j.open-ai.max-tokens:4096}")
    private Integer maxTokens;

    @Value("${langchain4j.open-ai.temperature:0.7}")
    private Double temperature;

    /**
     * 创建规划 Agent（可按 run 维度选择模型与端点）。
     *
     * @param endpointName 端点名（可为空，使用默认值）
     * @param modelName    模型名（可为空，使用默认值）
     * @return PlanningAgent
     */
    public PlanningAgent createPlanningAgent(String endpointName, String modelName) {
        ChatLanguageModel model = buildChatModel(endpointName, modelName);
        return AiServices.builder(PlanningAgent.class)
                .chatLanguageModel(model)
                .build();
    }

    /**
     * 创建总结 Agent（可按 run 维度选择模型与端点）。
     *
     * @param endpointName 端点名（可为空，使用默认值）
     * @param modelName    模型名（可为空，使用默认值）
     * @return SummarizingAgent
     */
    public SummarizingAgent createSummarizingAgent(String endpointName, String modelName) {
        ChatLanguageModel model = buildChatModel(endpointName, modelName);
        return AiServices.builder(SummarizingAgent.class)
                .chatLanguageModel(model)
                .build();
    }

    public ChatLanguageModel buildChatModel(String endpointName, String modelName) {
        AgentLlmResolver.ResolvedLlm resolved = llmResolver.resolve(endpointName, modelName);
        return OpenAiChatModel.builder()
                .apiKey(openAiApiKey)
                .baseUrl(resolved.baseUrl())
                .modelName(resolved.modelName())
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
