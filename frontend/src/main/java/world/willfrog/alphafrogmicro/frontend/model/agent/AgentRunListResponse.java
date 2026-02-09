package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

public record AgentRunListResponse(
        List<AgentRunListItemResponse> items,
        int total,
        boolean hasMore
) {
}
