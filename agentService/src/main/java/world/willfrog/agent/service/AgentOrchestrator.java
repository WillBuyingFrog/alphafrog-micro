package world.willfrog.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.tool.MarketDataTools;

@Service
@Slf4j
public class AgentOrchestrator {

    private final AgentAiServiceFactory aiServiceFactory;
    private final MarketDataTools marketDataTools;

    public AgentOrchestrator(AgentAiServiceFactory aiServiceFactory,
                             MarketDataTools marketDataTools) {
        this.aiServiceFactory = aiServiceFactory;
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

        // 1. Plan
        String planJson = aiServiceFactory.createPlanningAgent(null, null).plan(userGoal);
        log.info("Generated Plan: {}", planJson);

        // 2. Execute (Mock execution for now)
        // In a real scenario, we would parse the JSON and call tools
        // For MVP verification, we just confirm we can access tools
        // log.info("Testing Tool Access: {}", marketDataTools.searchFund("Index"));

        StringBuilder executionLogs = new StringBuilder();
        executionLogs.append("Plan created: ").append(planJson).append("\n");
        executionLogs.append("Execution: Simulated execution of plan.\n");

        // 3. Summarize
        String summary = aiServiceFactory.createSummarizingAgent(null, null).summarize(executionLogs.toString());
        log.info("Summary: {}", summary);

        return summary;
    }
}
