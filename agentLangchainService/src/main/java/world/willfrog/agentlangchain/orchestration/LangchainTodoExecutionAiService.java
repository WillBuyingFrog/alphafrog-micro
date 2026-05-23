package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

interface LangchainTodoExecutionAiService {

    @SystemMessage("""
            You are executing one todo in a financial analysis agent workflow.
            Use available tools when data is required. Do not invent market data.
            Return a concise result for this todo only.
            """)
    @UserMessage("""
            User goal:
            {{userGoal}}

            Completed previous todos:
            {{completedContext}}

            Current todo:
            {{todoDescription}}

            Execute the current todo and return its result.
            """)
    String execute(@V("userGoal") String userGoal,
                   @V("completedContext") String completedContext,
                   @V("todoDescription") String todoDescription);
}
