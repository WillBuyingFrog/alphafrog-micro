package world.willfrog.agent.platform.entity;

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class AgentRunEvent {
    private Long id;
    private String runId;
    private Integer seq;
    private String eventType;
    private String payloadJson; // JSON string
    private OffsetDateTime createdAt;
}
