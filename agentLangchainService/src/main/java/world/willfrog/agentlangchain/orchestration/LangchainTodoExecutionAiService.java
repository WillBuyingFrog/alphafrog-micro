package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.service.UserMessage;

interface LangchainTodoExecutionAiService {

    @UserMessage("{{it}}")
    String execute(String userMessage);
}
