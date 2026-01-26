package world.willfrog.agent.graph;

import lombok.Builder;
import lombok.Data;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ParallelPlanValidator {

    @Data
    @Builder
    public static class Result {
        private boolean valid;
        private String reason;
    }

    public Result validate(ParallelPlan plan, Set<String> toolWhitelist, int maxTasks, int maxSubSteps) {
        if (plan == null) {
            return Result.builder().valid(false).reason("plan is null").build();
        }
        if (plan.getTasks() == null || plan.getTasks().isEmpty()) {
            return Result.builder().valid(false).reason("plan has no tasks").build();
        }
        if (plan.getTasks().size() > maxTasks) {
            return Result.builder().valid(false).reason("plan exceeds max tasks").build();
        }
        Set<String> ids = new HashSet<>();
        for (ParallelPlan.PlanTask task : plan.getTasks()) {
            if (task.getId() == null || task.getId().isBlank()) {
                return Result.builder().valid(false).reason("task id missing").build();
            }
            if (!ids.add(task.getId())) {
                return Result.builder().valid(false).reason("duplicate task id").build();
            }
            String type = task.getType() == null ? "" : task.getType().trim().toLowerCase();
            if (!type.equals("tool") && !type.equals("sub_agent")) {
                return Result.builder().valid(false).reason("unsupported task type: " + task.getType()).build();
            }
            if (type.equals("tool")) {
                if (task.getTool() == null || task.getTool().isBlank()) {
                    return Result.builder().valid(false).reason("tool name missing").build();
                }
                if (!toolWhitelist.contains(task.getTool())) {
                    return Result.builder().valid(false).reason("tool not allowed: " + task.getTool()).build();
                }
            }
            if (type.equals("sub_agent")) {
                if (task.getGoal() == null || task.getGoal().isBlank()) {
                    return Result.builder().valid(false).reason("sub_agent goal missing").build();
                }
                if (task.getMaxSteps() != null && task.getMaxSteps() > maxSubSteps) {
                    return Result.builder().valid(false).reason("sub_agent max_steps exceeds limit").build();
                }
            }
            List<String> deps = task.getDependsOn();
            if (deps != null && deps.contains(task.getId())) {
                return Result.builder().valid(false).reason("task depends on itself").build();
            }
        }
        for (ParallelPlan.PlanTask task : plan.getTasks()) {
            List<String> deps = task.getDependsOn();
            if (deps == null) {
                continue;
            }
            for (String dep : deps) {
                if (!ids.contains(dep)) {
                    return Result.builder().valid(false).reason("unknown dependency: " + dep).build();
                }
            }
        }
        return Result.builder().valid(true).build();
    }
}
