package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

public record AgentModelListResponse(
        List<AgentModelResponse> models
) {
}
