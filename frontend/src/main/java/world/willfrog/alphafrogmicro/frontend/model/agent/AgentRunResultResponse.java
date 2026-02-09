package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentRunResultResponse(
        String id,
        String status,
        String answer,
        String payloadJson,
        String observabilityJson
) {
}
