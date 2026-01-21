package world.willfrog.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.ai.PlanningAgent;
import world.willfrog.agent.ai.SummarizingAgent;
import world.willfrog.agent.tool.MarketDataTools;

@Service
@Slf4j
public class AgentOrchestrator {

    private final PlanningAgent planningAgent;
    private final SummarizingAgent summarizingAgent;
    private final MarketDataTools marketDataTools;

    public AgentOrchestrator(PlanningAgent planningAgent, 
                             SummarizingAgent summarizingAgent,
                             MarketDataTools marketDataTools) {
        this.planningAgent = planningAgent;
        this.summarizingAgent = summarizingAgent;
        this.marketDataTools = marketDataTools;
    }

    public String execute(String userGoal) {
        log.info("Receiving user goal: {}", userGoal);

        // 1. Plan
        String planJson = planningAgent.plan(userGoal);
        log.info("Generated Plan: {}", planJson);

        // 2. Execute (Mock execution for now)
        // In a real scenario, we would parse the JSON and call tools
        // For MVP verification, we just confirm we can access tools
        // log.info("Testing Tool Access: {}", marketDataTools.searchFund("Index"));

        StringBuilder executionLogs = new StringBuilder();
        executionLogs.append("Plan created: ").append(planJson).append("\n");
        executionLogs.append("Execution: Simulated execution of plan.\n");

        // 3. Summarize
        String summary = summarizingAgent.summarize(executionLogs.toString());
        log.info("Summary: {}", summary);

        return summary;
    }
}
