package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentRunStatusResponse(
        String id,
        String status,
        String phase,
        String currentTool,
        String lastEventType,
        String lastEventAt,
        String lastEventPayloadJson
) {
}
