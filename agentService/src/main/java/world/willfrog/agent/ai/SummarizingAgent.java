package world.willfrog.agent.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface SummarizingAgent {

    @SystemMessage("""
        You are a helpful financial analyst.
        Your task is to review the execution logs of a financial task and provide a clear, concise, and user-friendly summary answer to the user's original request.
        
        Focus on the outcome and key data points found.
        """)
    String summarize(@UserMessage String executionLogs);
}
