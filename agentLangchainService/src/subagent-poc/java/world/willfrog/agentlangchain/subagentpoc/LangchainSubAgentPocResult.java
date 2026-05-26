package world.willfrog.agentlangchain.subagentpoc;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class LangchainSubAgentPocResult {
    private boolean success;
    private String parentGoal;
    private String childConclusion;
    private Map<String, String> limitationNotes;
}
