package world.willfrog.agentlangchain.agenticpoc.branch;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Static POC branch B: simulates read-only CSI500 lookup (no ToolRouter / no PG).
 */
public interface Csi500ReadOnlyBranchAgent {

    @UserMessage("Reply with exactly CSI500_OK for goal={{goal}}")
    @Agent(outputKey = "csi500")
    String fetch(@V("goal") String goal);
}
