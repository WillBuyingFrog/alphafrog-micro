package world.willfrog.agent.entity;

import lombok.Data;
import world.willfrog.agent.model.AgentRunStatus;
import java.time.OffsetDateTime;

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
    private OffsetDateTime ttlExpiresAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime completedAt;
    private String ext; // JSON string
}
