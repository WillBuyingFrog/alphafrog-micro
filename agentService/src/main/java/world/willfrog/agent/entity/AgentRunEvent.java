package world.willfrog.agent.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AgentRunEvent {
    private Long id;
    private String runId;
    private Integer seq;
    private String eventType;
    private String payloadJson; // JSON string
    private LocalDateTime createdAt;
}
