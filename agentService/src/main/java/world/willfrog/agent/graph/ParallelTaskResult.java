package world.willfrog.agent.graph;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
public class ParallelTaskResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String type;
    private boolean success;
    private String output;
    private String error;
    private Map<String, Object> cache;
}
