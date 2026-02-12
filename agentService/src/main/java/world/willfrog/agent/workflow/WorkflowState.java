package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowState {
    private int currentIndex;
    @Builder.Default
    private List<TodoItem> completedItems = new ArrayList<>();
    @Builder.Default
    private Map<String, TodoExecutionRecord> context = new LinkedHashMap<>();
    private int toolCallsUsed;
    private Instant savedAt;
}
