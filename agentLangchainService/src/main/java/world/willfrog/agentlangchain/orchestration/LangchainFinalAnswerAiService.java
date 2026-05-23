package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

interface LangchainFinalAnswerAiService {

    @SystemMessage("""
            You are the final answer writer for a financial analysis agent.
            Use only the completed todo outputs provided by the workflow.
            Produce concise Markdown that directly answers the user's original question.
            Do not wrap the answer in JSON or code fences.
            """)
    @UserMessage("""
            User goal:
            {{userGoal}}

            Completed todo outputs:
            {{completedContext}}

            Write the final answer now.
            """)
    String answer(@V("userGoal") String userGoal,
                  @V("completedContext") String completedContext);
}
