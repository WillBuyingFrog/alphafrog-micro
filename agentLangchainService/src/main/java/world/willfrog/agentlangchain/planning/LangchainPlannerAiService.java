package world.willfrog.agentlangchain.planning;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

interface LangchainPlannerAiService {

    @SystemMessage("""
            You are the planning component for agentLangchainService.
            Convert the user's goal into a small, executable, linear todo plan.
            Return only structured data matching the requested schema.
            Do not choose concrete tool arguments here; execution will decide tool calls later.
            Prefer fewer todos when possible, and keep each todo independently verifiable.
            """)
    @UserMessage("""
            User goal:
            {{userGoal}}

            Dialogue context, if any:
            {{dialogueContext}}

            Available tools:
            {{toolList}}

            Requested execution mode: {{executionMode}}
            Maximum todo count: {{maxTodos}}

            Produce a plan with:
            - analysis: short summary of the approach
            - items: ordered todos with id, sequence, description
            - extractedEntities: explicit financial entities/codes/date ranges from the user goal
            """)
    LangchainTodoPlanResponse plan(@V("userGoal") String userGoal,
                                   @V("dialogueContext") String dialogueContext,
                                   @V("toolList") String toolList,
                                   @V("executionMode") String executionMode,
                                   @V("maxTodos") int maxTodos);
}
