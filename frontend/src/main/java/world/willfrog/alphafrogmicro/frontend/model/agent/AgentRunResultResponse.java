package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentRunResultResponse(
        String id,
        String status,
        String answer,
        Object payload,
        Object observability,
        Integer totalCreditsConsumed
) {
}
