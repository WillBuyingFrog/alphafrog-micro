package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentRunListItemResponse(
        String id,
        String message,
        String status,
        String createdAt,
        String completedAt,
        boolean hasArtifacts,
        Long durationMs,
        Integer totalTokens,
        Integer toolCalls
) {
}
