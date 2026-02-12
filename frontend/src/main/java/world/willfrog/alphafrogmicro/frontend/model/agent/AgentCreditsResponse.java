package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentCreditsResponse(
        Integer totalCredits,
        Integer remainingCredits,
        Integer usedCredits,
        String resetCycle,
        String nextResetAt
) {
}
