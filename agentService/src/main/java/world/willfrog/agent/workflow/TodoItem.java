package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoItem {
    private String id;
    private int sequence;
    private TodoType type;
    private String toolName;
    @Builder.Default
    private Map<String, Object> params = new LinkedHashMap<>();
    private String reasoning;
    private ExecutionMode executionMode;
    private TodoStatus status;
    private String resultSummary;
    private String output;
    private Instant createdAt;
    private Instant completedAt;
}
