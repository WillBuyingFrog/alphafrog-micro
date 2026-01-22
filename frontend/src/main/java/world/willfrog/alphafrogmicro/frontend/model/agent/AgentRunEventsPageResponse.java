package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;

public record AgentRunEventsPageResponse(
        List<AgentRunEventResponse> items,
        int nextAfterSeq,
        boolean hasMore
) {
}

