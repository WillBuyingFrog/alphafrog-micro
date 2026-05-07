package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentRunStatusResponse(
        String id,
        String status,
        String phase,
        String currentTool,
        String lastEventType,
        String lastEventAt,
        Object lastEventPayload,
        Object plan,
        Object progress,
        Object observability,
        Object observabilitySummary,
        Boolean observabilityFullAvailable,
        Integer totalCreditsConsumed,
        Integer eventCount,
        Long startedAtMs,
        Long completedAtMs,
        Long elapsedMs
) {
}
