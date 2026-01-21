package world.willfrog.agent.entity;

import lombok.Data;
import world.willfrog.agent.model.AgentRunStatus;
import java.time.LocalDateTime;

@Data
public class AgentRun {
    private String id;
    private String userId;
    private AgentRunStatus status;
    private Integer currentStep;
    private Integer maxSteps;
    
    // JSON strings
    private String planJson;
    private String snapshotJson;
    
    private String lastError;
    private LocalDateTime ttlExpiresAt;
    private LocalDateTime startedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private String ext; // JSON string
}
