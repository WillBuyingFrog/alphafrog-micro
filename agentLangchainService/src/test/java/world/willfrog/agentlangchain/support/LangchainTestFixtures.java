package world.willfrog.agentlangchain.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningStructuredOutputSettings;

public final class LangchainTestFixtures {

    private LangchainTestFixtures() {
    }

    public static AgentPromptService promptService() {
        return new AgentPromptService(new AgentLlmProperties(), new AgentLlmLocalConfigLoader(new ObjectMapper()));
    }

    public static LangchainPlanningStructuredOutputSettings structuredOutputSettings() {
        return new LangchainPlanningStructuredOutputSettings(
                new AgentLlmProperties(),
                new AgentLlmLocalConfigLoader(new ObjectMapper()));
    }

    public static LangchainAiPlanner planner() {
        return new LangchainAiPlanner(promptService(), structuredOutputSettings());
    }
}
