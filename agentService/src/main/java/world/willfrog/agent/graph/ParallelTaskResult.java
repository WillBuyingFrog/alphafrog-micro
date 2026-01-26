package world.willfrog.agent.graph;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ParallelTaskResult {
    private String taskId;
    private String type;
    private boolean success;
    private String output;
    private String error;
}
