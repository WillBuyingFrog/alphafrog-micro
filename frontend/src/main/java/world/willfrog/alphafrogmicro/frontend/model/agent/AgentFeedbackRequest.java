package world.willfrog.alphafrogmicro.frontend.model.agent;

import java.util.List;
import java.util.Map;

public record AgentFeedbackRequest(
        Integer rating,
        String comment,
        List<String> tags,
        Map<String, Object> payload
) {
}

