package world.willfrog.agentlangchain.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningStructuredOutputSettings;

public final class LangchainTestFixtures {

    private LangchainTestFixtures() {
    }

    public static AgentLlmProperties llmProperties() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Planning planning = new AgentLlmProperties.Planning();
        AgentLlmProperties.StructuredOutput structuredOutput = new AgentLlmProperties.StructuredOutput();
        structuredOutput.setStrategyStageEnabled(false);
        planning.setStructuredOutput(structuredOutput);
        runtime.setPlanning(planning);
        properties.setRuntime(runtime);
        AgentLlmProperties.Prompts prompts = new AgentLlmProperties.Prompts();
        prompts.setAgentRunSystemPrompt("你是专业金融分析代理。");
        prompts.setTodoPlannerSystemPromptTemplate(
                "你是任务规划器。只输出 JSON。工具: {{toolWhitelist}}，最多 {{maxTodos}} 步。");
        prompts.setDagReactSystemPrompt("你是金融分析代理，使用工具完成任务。");
        properties.setPrompts(prompts);
        return properties;
    }

    public static AgentPromptService promptService() {
        return new AgentPromptService(llmProperties(), new AgentLlmLocalConfigLoader(new ObjectMapper()));
    }

    public static LangchainPlanningStructuredOutputSettings structuredOutputSettings() {
        return new LangchainPlanningStructuredOutputSettings(
                llmProperties(),
                new AgentLlmLocalConfigLoader(new ObjectMapper()));
    }

    public static LangchainAiPlanner planner() {
        return new LangchainAiPlanner(promptService(), structuredOutputSettings(), JsonMapper.builder().build());
    }
}
