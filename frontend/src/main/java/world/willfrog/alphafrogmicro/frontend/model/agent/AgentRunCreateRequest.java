package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.Map;

public record AgentRunCreateRequest(
        String message,
        Map<String, Object> context,
        String idempotencyKey,
        String modelName,
        String endpointName
) {
}
