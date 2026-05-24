package world.willfrog.agentlangchain.subagentpoc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Experimental spawn/wait-style POC. Not wired to Dubbo or production pipeline.
 * Compile with {@code -Psubagent-poc}.
 */
public final class LangchainSubAgentPocRunner {

    private static final Map<String, String> DEFAULT_LIMITATIONS = Map.of(
            "tool_router", "Child tasks do not inherit ToolRouter governance from parent.",
            "budget_obs", "No shared budget/observability propagation in this POC.",
            "lifecycle", "Pause/cancel on parent does not automatically stop child futures.",
            "isolation", "Uses in-memory futures only; not a substitute for SubAgentRunner DB events.");

    private LangchainSubAgentPocRunner() {
    }

    public static LangchainSubAgentPocResult run(String parentGoal) {
        if (parentGoal == null || parentGoal.isBlank()) {
            return LangchainSubAgentPocResult.builder()
                    .success(false)
                    .parentGoal("")
                    .childConclusion("parent_goal_required")
                    .limitationNotes(DEFAULT_LIMITATIONS)
                    .build();
        }
        try {
            CompletableFuture<String> child = CompletableFuture.supplyAsync(
                    () -> "child-local-answer:" + parentGoal.trim());
            String childConclusion = child.get(5, TimeUnit.SECONDS);
            Map<String, String> notes = new LinkedHashMap<>(DEFAULT_LIMITATIONS);
            notes.put("replacement_scope", "Demonstrates spawn/wait sequencing only; production still uses legacy SubAgentRunner.");
            return LangchainSubAgentPocResult.builder()
                    .success(true)
                    .parentGoal(parentGoal.trim())
                    .childConclusion(childConclusion)
                    .limitationNotes(notes)
                    .build();
        } catch (Exception e) {
            return LangchainSubAgentPocResult.builder()
                    .success(false)
                    .parentGoal(parentGoal.trim())
                    .childConclusion(e.getMessage())
                    .limitationNotes(DEFAULT_LIMITATIONS)
                    .build();
        }
    }
}
