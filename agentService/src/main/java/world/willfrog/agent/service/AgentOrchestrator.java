package world.willfrog.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.tool.MarketDataTools;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

@Service
@Slf4j
public class AgentOrchestrator {

    private final AgentAiServiceFactory aiServiceFactory;
    private final AgentPromptService promptService;
    private final MarketDataTools marketDataTools;

    public AgentOrchestrator(AgentAiServiceFactory aiServiceFactory,
                             AgentPromptService promptService,
                             MarketDataTools marketDataTools) {
        this.aiServiceFactory = aiServiceFactory;
        this.promptService = promptService;
        this.marketDataTools = marketDataTools;
    }

    /**
     * 最简 Agent 执行流程（规划 -> 执行 -> 总结）。
     *
     * @param userGoal 用户目标
     * @return 总结结果
     */
    public String execute(String userGoal) {
        log.info("Receiving user goal: {}", userGoal);
        ChatLanguageModel model = aiServiceFactory.buildChatModel(null, null);

        // 1. Plan
        Response<AiMessage> planResponse = model.generate(List.of(
                new SystemMessage(promptService.orchestratorPlanningSystemPrompt()),
                new UserMessage(userGoal)
        ));
        String planJson = planResponse.content().text();
        log.info("Generated Plan: {}", planJson);

        // 2. Execute (Mock execution for now)
        // In a real scenario, we would parse the JSON and call tools
        // For MVP verification, we just confirm we can access tools
        // log.info("Testing Tool Access: {}", marketDataTools.searchFund("Index"));

        StringBuilder executionLogs = new StringBuilder();
        executionLogs.append("Plan created: ").append(planJson).append("\n");
        executionLogs.append("Execution: Simulated execution of plan.\n");

        // 3. Summarize
        Response<AiMessage> summaryResponse = model.generate(List.of(
                new SystemMessage(promptService.orchestratorSummarySystemPrompt()),
                new UserMessage(executionLogs.toString())
        ));
        String summary = summaryResponse.content().text();
        log.info("Summary: {}", summary);

        return summary;
    }
}
