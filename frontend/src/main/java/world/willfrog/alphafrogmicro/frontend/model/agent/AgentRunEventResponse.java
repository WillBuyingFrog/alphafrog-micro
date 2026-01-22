package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentRunEventResponse(
        long id,
        String runId,
        int seq,
        String eventType,
        String payloadJson,
        String createdAt
) {
}

