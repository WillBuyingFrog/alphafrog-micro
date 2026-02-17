package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentCreditsApplyResponse(
        String applicationId,
        Integer totalCredits,
        Integer remainingCredits,
        Integer usedCredits,
        String status,
        String appliedAt
) {
}
