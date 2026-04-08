package world.willfrog.agent.workflow;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 执行图数据结构。
 *
 * <p>保存节点邻接表、入度信息和 DAG 元数据，
 * 用于 {@link DagWorkflowExecutor} 的并行调度。</p>
 */
@Data
@Builder
public class ExecutionGraph {

    /** 所有节点（todoId → TodoItem） */
    @Builder.Default
    private Map<String, TodoItem> nodes = new HashMap<>();

    /** 邻接表：节点 → 后继节点列表 */
    @Builder.Default
    private Map<String, List<String>> adjacency = new HashMap<>();

    /** 入度表：节点 → 入度数 */
    @Builder.Default
    private Map<String, Integer> inDegree = new HashMap<>();

    /** 拓扑排序后的节点顺序 */
    @Builder.Default
    private List<String> topologicalOrder = new ArrayList<>();

    /** 总节点数 */
    private int totalNodes;

    /** 最大深度（最长路径长度） */
    private int maxDepth;

    /** 最大并行度（同时可执行的最大节点数） */
    private int maxParallelism;

    /**
     * 获取初始就绪节点（入度为 0 的节点）。
     */
    public List<String> getReadyNodes() {
        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        return ready;
    }

    /**
     * 获取指定节点的后继节点。
     */
    public List<String> getSuccessors(String nodeId) {
        return adjacency.getOrDefault(nodeId, Collections.emptyList());
    }

    /**
     * 获取指定节点的 TodoItem。
     */
    public TodoItem getNode(String nodeId) {
        return nodes.get(nodeId);
    }
}
