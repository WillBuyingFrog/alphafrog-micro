package world.willfrog.agentlangchain.planning;

import dev.langchain4j.service.UserMessage;

/**
 * Planning service interface. System prompt is supplied by {@link LangchainAiPlanner}
 * via {@code systemMessageProvider}; user message is pre-assembled to match
 * legacy {@code TodoPlanner} prompt structure.
 */
interface LangchainPlannerAiService {

    @UserMessage("{{it}}")
    LangchainTodoPlanResponse plan(String userMessage);
}
