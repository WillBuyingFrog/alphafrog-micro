package world.willfrog.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 从 TodoPlan 构建 DAG 执行图。
 *
 * <p>解析 {@link TodoItem#getDependsOn()} 构建邻接表，计算入度，
 * 进行拓扑排序检测循环依赖，并计算 DAG 元数据。</p>
 */
@Component
@Slf4j
public class DagBuilder {

    /**
     * 从 TodoPlan 构建 DAG 执行图。
     *
     * @param plan TodoPlan（包含带 dependsOn 的 TodoItem 列表）
     * @return 执行图
     * @throws IllegalArgumentException 如果检测到循环依赖
     */
    public ExecutionGraph build(TodoPlan plan) {
        if (plan == null || plan.getItems() == null || plan.getItems().isEmpty()) {
            return ExecutionGraph.builder()
                    .totalNodes(0)
                    .maxDepth(0)
                    .maxParallelism(0)
                    .build();
        }

        List<TodoItem> items = plan.getItems();

        // 1. 构建节点映射
        Map<String, TodoItem> nodes = new HashMap<>();
        for (TodoItem item : items) {
            if (item.getId() == null || item.getId().isBlank()) {
                continue;
            }
            nodes.put(item.getId(), item);
        }

        // 2. 构建邻接表和入度表
        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (String nodeId : nodes.keySet()) {
            adjacency.put(nodeId, new ArrayList<>());
            inDegree.put(nodeId, 0);
        }

        for (TodoItem item : items) {
            if (item.getId() == null || item.getDependsOn() == null) {
                continue;
            }
            for (String dep : item.getDependsOn()) {
                if (dep == null || dep.isBlank() || !nodes.containsKey(dep)) {
                    log.warn("DagBuilder: node '{}' depends on unknown node '{}', ignoring", item.getId(), dep);
                    continue;
                }
                adjacency.get(dep).add(item.getId());
                inDegree.merge(item.getId(), 1, Integer::sum);
            }
        }

        // 3. 拓扑排序（Kahn 算法）+ 循环依赖检测
        List<String> topologicalOrder = new ArrayList<>();
        Queue<String> queue = new LinkedList<>();

        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            topologicalOrder.add(current);
            for (String successor : adjacency.getOrDefault(current, List.of())) {
                int newDegree = inDegree.get(successor) - 1;
                inDegree.put(successor, newDegree);
                if (newDegree == 0) {
                    queue.add(successor);
                }
            }
        }

        if (topologicalOrder.size() != nodes.size()) {
            Set<String> processed = new HashSet<>(topologicalOrder);
            Set<String> cycleNodes = new HashSet<>(nodes.keySet());
            cycleNodes.removeAll(processed);
            throw new IllegalArgumentException("Circular dependency detected among nodes: " + cycleNodes);
        }

        // 4. 计算最大深度（从入度为 0 的节点开始 BFS）
        Map<String, Integer> depth = new HashMap<>();
        int maxDepth = 0;
        for (String nodeId : topologicalOrder) {
            int nodeDepth = 0;
            TodoItem item = nodes.get(nodeId);
            if (item.getDependsOn() != null) {
                for (String dep : item.getDependsOn()) {
                    if (depth.containsKey(dep)) {
                        nodeDepth = Math.max(nodeDepth, depth.get(dep) + 1);
                    }
                }
            }
            depth.put(nodeId, nodeDepth);
            maxDepth = Math.max(maxDepth, nodeDepth);
        }

        // 5. 计算最大并行度（同一深度的节点数最大值）
        Map<Integer, Integer> levelCount = new HashMap<>();
        for (int d : depth.values()) {
            levelCount.merge(d, 1, Integer::sum);
        }
        int maxParallelism = levelCount.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        // 6. 重置入度（拓扑排序过程中修改了入度）
        Map<String, Integer> originalInDegree = new HashMap<>();
        for (String nodeId : nodes.keySet()) {
            originalInDegree.put(nodeId, 0);
        }
        for (TodoItem item : items) {
            if (item.getId() == null || item.getDependsOn() == null) {
                continue;
            }
            for (String dep : item.getDependsOn()) {
                if (dep != null && !dep.isBlank() && nodes.containsKey(dep)) {
                    originalInDegree.merge(item.getId(), 1, Integer::sum);
                }
            }
        }

        return ExecutionGraph.builder()
                .nodes(nodes)
                .adjacency(adjacency)
                .inDegree(originalInDegree)
                .topologicalOrder(topologicalOrder)
                .totalNodes(nodes.size())
                .maxDepth(maxDepth + 1)
                .maxParallelism(maxParallelism)
                .build();
    }
}
