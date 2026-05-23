package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.service.UserMessage;

interface LangchainFinalAnswerAiService {

    @UserMessage("{{it}}")
    String answer(String userMessage);
}
