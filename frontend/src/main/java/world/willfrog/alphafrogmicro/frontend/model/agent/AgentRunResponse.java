package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentRunResponse(
        String id,
        String status,
        int currentStep,
        int maxSteps,
        Object plan,
        Object snapshot,
        String lastError,
        String ttlExpiresAt,
        String startedAt,
        String updatedAt,
        String completedAt,
        String ext
) {
}

