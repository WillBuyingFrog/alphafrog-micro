package world.willfrog.agent.graph;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划复杂度评分器。
 * <p>
 * 用统一复杂度惩罚避免候选计划“越复杂越容易覆盖描述、却越难稳定执行”的问题。
 */
@Component
public class PlanComplexityScorer {

    public Result score(ParallelPlan plan, double lambda) {
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            return Result.empty();
        }
        int stepCount = plan.getTasks().size();
        int fanout = 0;
        Map<String, Integer> depthMemo = new HashMap<>();
        for (ParallelPlan.PlanTask task : plan.getTasks()) {
            List<String> deps = task.getDependsOn();
            if (deps == null || deps.isEmpty()) {
                fanout += 1;
            }
        }
        int criticalPathDepth = 0;
        for (ParallelPlan.PlanTask task : plan.getTasks()) {
            criticalPathDepth = Math.max(criticalPathDepth, depth(task.getId(), plan, depthMemo));
        }

        double safeLambda = lambda <= 0D ? 0D : lambda;
        double raw = stepCount * 1.0 + criticalPathDepth * 0.8 + fanout * 0.4;
        double penalty = safeLambda * raw;
        return Result.builder()
                .stepCount(stepCount)
                .fanout(fanout)
                .criticalPathDepth(criticalPathDepth)
                .lambda(safeLambda)
                .rawComplexity(raw)
                .penalty(penalty)
                .build();
    }

    private int depth(String taskId, ParallelPlan plan, Map<String, Integer> memo) {
        if (taskId == null || taskId.isBlank()) {
            return 1;
        }
        Integer cached = memo.get(taskId);
        if (cached != null) {
            return cached;
        }
        ParallelPlan.PlanTask task = findTask(taskId, plan);
        if (task == null) {
            memo.put(taskId, 1);
            return 1;
        }
        List<String> deps = task.getDependsOn();
        if (deps == null || deps.isEmpty()) {
            memo.put(taskId, 1);
            return 1;
        }
        int maxDep = 0;
        for (String depId : deps) {
            maxDep = Math.max(maxDep, depth(depId, plan, memo));
        }
        int out = maxDep + 1;
        memo.put(taskId, out);
        return out;
    }

    private ParallelPlan.PlanTask findTask(String taskId, ParallelPlan plan) {
        if (plan == null || plan.getTasks() == null || taskId == null) {
            return null;
        }
        for (ParallelPlan.PlanTask task : plan.getTasks()) {
            if (taskId.equals(task.getId())) {
                return task;
            }
        }
        return null;
    }

    @lombok.Builder
    @lombok.Data
    public static class Result {
        private int stepCount;
        private int fanout;
        private int criticalPathDepth;
        private double lambda;
        private double rawComplexity;
        private double penalty;

        public static Result empty() {
            return Result.builder()
                    .stepCount(0)
                    .fanout(0)
                    .criticalPathDepth(0)
                    .lambda(0D)
                    .rawComplexity(0D)
                    .penalty(0D)
                    .build();
        }
    }
}
