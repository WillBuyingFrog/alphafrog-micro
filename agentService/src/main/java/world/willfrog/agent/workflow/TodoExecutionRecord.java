package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoExecutionRecord {
    private boolean success;
    private String output;
    private String summary;
    private int toolCallsUsed;
}
