package world.willfrog.agentlangchain.agenticpoc.branch;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Static POC branch A: simulates read-only HS300 lookup (no ToolRouter / no PG).
 */
public interface Hs300ReadOnlyBranchAgent {

    @UserMessage("Reply with exactly HS300_OK for goal={{goal}}")
    @Agent(outputKey = "hs300")
    String fetch(@V("goal") String goal);
}
