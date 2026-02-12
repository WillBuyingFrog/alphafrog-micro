package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.Map;

public record AgentRunCreateRequest(
        String message,
        Map<String, Object> context,
        AgentRunCreateConfig config,
        String idempotencyKey,
        String modelName,
        String endpointName,
        Boolean captureLlmRequests,
        String provider,
        Integer plannerCandidateCount,
        Boolean debugMode
) {
}
