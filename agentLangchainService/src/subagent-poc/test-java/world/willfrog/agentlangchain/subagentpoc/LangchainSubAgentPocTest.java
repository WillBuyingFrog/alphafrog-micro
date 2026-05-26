package world.willfrog.agentlangchain.subagentpoc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangchainSubAgentPocTest {

    @Test
    void runReturnsChildConclusionForValidGoal() {
        LangchainSubAgentPocResult result = LangchainSubAgentPocRunner.run("compare hs300 vs csi500");
        assertTrue(result.isSuccess());
        assertTrue(result.getChildConclusion().contains("compare hs300 vs csi500"));
        assertTrue(result.getLimitationNotes().containsKey("tool_router"));
    }

    @Test
    void runRejectsBlankGoal() {
        LangchainSubAgentPocResult result = LangchainSubAgentPocRunner.run("  ");
        assertFalse(result.isSuccess());
    }
}
