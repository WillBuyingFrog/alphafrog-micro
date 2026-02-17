package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowExecutionResult {
    private boolean paused;
    private boolean success;
    private String failureReason;
    private String finalAnswer;
    @Builder.Default
    private List<TodoItem> completedItems = new ArrayList<>();
    @Builder.Default
    private Map<String, TodoExecutionRecord> context = Map.of();
    private int toolCallsUsed;
}
