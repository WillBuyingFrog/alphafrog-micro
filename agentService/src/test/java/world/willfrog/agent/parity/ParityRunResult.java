package world.willfrog.agent.parity;

import lombok.Builder;
import lombok.Data;
import world.willfrog.agent.workflow.WorkflowExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次 parity 运行后的完整结果快照，用于断言校验。
 */
@Data
@Builder
public class ParityRunResult {

    private String runId;
    private String caseId;
    private boolean success;
    private String finalStatus;
    private String finalAnswer;
    private String failureReason;
    private String failureCategory;

    @Builder.Default
    private List<Map<String, Object>> events = new ArrayList<>();

    @Builder.Default
    private List<String> datasetIds = new ArrayList<>();

    private WorkflowExecutionResult workflowResult;

    @Builder.Default
    private List<String> toolCalls = new ArrayList<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    public boolean hasEvent(String eventType) {
        return events.stream().anyMatch(e -> eventType.equals(e.get("event_type")));
    }

    public long eventCount(String eventType) {
        return events.stream().filter(e -> eventType.equals(e.get("event_type"))).count();
    }

    public Map<String, Object> firstEvent(String eventType) {
        return events.stream()
                .filter(e -> eventType.equals(e.get("event_type")))
                .findFirst()
                .orElse(null);
    }
}
