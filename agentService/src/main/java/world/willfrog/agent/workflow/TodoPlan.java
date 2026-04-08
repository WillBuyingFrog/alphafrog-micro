package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoPlan {
    private String analysis;
    @Builder.Default
    private List<TodoItem> items = new ArrayList<>();

    /** 执行模式：LINEAR / DAG / AUTO */
    private PlanExecutionMode executionMode;

    /** DAG 元数据（可选） */
    private DagMetadata dagMetadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DagMetadata {
        private int totalNodes;
        private int maxDepth;
        private int maxParallelism;
    }
}
