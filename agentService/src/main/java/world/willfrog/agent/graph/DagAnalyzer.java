package world.willfrog.agent.graph;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyze DAG structure for observability only.
 */
@Component
public class DagAnalyzer {

    public DagMetrics analyze(ParallelPlan plan) {
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            return DagMetrics.empty();
        }

        Map<String, ParallelPlan.PlanTask> taskMap = new LinkedHashMap<>();
        for (ParallelPlan.PlanTask task : plan.getTasks()) {
            if (task == null || task.getId() == null || task.getId().isBlank()) {
                continue;
            }
            taskMap.put(task.getId(), task);
        }
        if (taskMap.isEmpty()) {
            return DagMetrics.empty();
        }

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> downstream = new HashMap<>();
        for (String taskId : taskMap.keySet()) {
            indegree.put(taskId, 0);
            downstream.put(taskId, new ArrayList<>());
        }

        for (ParallelPlan.PlanTask task : taskMap.values()) {
            List<String> deps = task.getDependsOn();
            if (deps == null) {
                continue;
            }
            Set<String> dedupDeps = new HashSet<>();
            for (String dep : deps) {
                if (dep == null || dep.isBlank() || !taskMap.containsKey(dep) || !dedupDeps.add(dep)) {
                    continue;
                }
                indegree.put(task.getId(), indegree.get(task.getId()) + 1);
                downstream.computeIfAbsent(dep, key -> new ArrayList<>()).add(task.getId());
            }
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        Map<String, Integer> depth = new HashMap<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
                depth.put(entry.getKey(), 1);
            }
        }

        int visited = 0;
        int maxParallelism = 0;
        int criticalPathLength = 0;

        while (!queue.isEmpty()) {
            int width = queue.size();
            maxParallelism = Math.max(maxParallelism, width);

            for (int i = 0; i < width; i++) {
                String taskId = queue.removeFirst();
                visited++;
                int currentDepth = depth.getOrDefault(taskId, 1);
                criticalPathLength = Math.max(criticalPathLength, currentDepth);

                for (String next : downstream.getOrDefault(taskId, List.of())) {
                    depth.put(next, Math.max(depth.getOrDefault(next, 1), currentDepth + 1));
                    int nextIndegree = indegree.getOrDefault(next, 0) - 1;
                    indegree.put(next, nextIndegree);
                    if (nextIndegree == 0) {
                        queue.addLast(next);
                    }
                }
            }
        }

        boolean hasCycle = visited < taskMap.size();
        if (criticalPathLength == 0 && !taskMap.isEmpty()) {
            criticalPathLength = 1;
        }

        return DagMetrics.builder()
                .taskCount(taskMap.size())
                .maxParallelism(maxParallelism)
                .criticalPathLength(criticalPathLength)
                .hasCycle(hasCycle)
                .build();
    }

    @Data
    @Builder
    public static class DagMetrics {
        private int taskCount;
        private int maxParallelism;
        private int criticalPathLength;
        private boolean hasCycle;

        public static DagMetrics empty() {
            return DagMetrics.builder()
                    .taskCount(0)
                    .maxParallelism(0)
                    .criticalPathLength(0)
                    .hasCycle(false)
                    .build();
        }
    }
}
