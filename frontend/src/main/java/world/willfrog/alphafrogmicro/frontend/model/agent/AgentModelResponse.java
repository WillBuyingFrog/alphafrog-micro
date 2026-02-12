package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

public record AgentModelResponse(
        String id,
        String displayName,
        String endpoint,
        String compositeId,
        Double baseRate,
        List<String> features
) {
}
