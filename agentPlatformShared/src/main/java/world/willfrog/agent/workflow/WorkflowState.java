package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ── DAG 模式状态字段 ──

    /** 执行模式 */
    private PlanExecutionMode executionMode;

    /** 已完成节点 ID 集合（DAG 模式） */
    @Builder.Default
    private Set<String> completedNodeIds = new HashSet<>();

    /** 运行中节点 ID 集合（用于故障恢复） */
    @Builder.Default
    private Set<String> runningNodeIds = new HashSet<>();
}
