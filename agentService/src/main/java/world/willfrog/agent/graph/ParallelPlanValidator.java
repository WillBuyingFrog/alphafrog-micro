package world.willfrog.agent.graph;

import lombok.Builder;
import lombok.Data;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 并行计划校验器。
 * <p>
 * 主要校验维度：
 * 1. 计划结构完整性（任务存在、ID 唯一、依赖有效）；
 * 2. 任务类型合法性（tool / sub_agent）；
 * 3. 工具白名单约束；
 * 4. 并行数量、sub_agent 数量与步数上限约束。
 */
public class ParallelPlanValidator {

    /**
     * 计划校验结果。
     */
    @Data
    @Builder
    public static class Result {
        /** 是否通过校验。 */
        private boolean valid;
        /** 失败原因（通过时通常为空）。 */
        private String reason;
    }

    /**
     * 校验并行计划是否满足运行要求。
     *
     * @param plan             并行计划
     * @param toolWhitelist    可用工具白名单
     * @param maxTasks         最大任务数
     * @param maxSubSteps      sub_agent 最大步数
     * @param maxParallelTasks 并行任务上限（无依赖任务数），-1 表示不限制
     * @param maxSubAgents     sub_agent 任务上限，-1 表示不限制
     * @return 校验结果
     */
    public Result validate(ParallelPlan plan,
                           Set<String> toolWhitelist,
                           int maxTasks,
                           int maxSubSteps,
                           int maxParallelTasks,
                           int maxSubAgents) {
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
        // parallelCount: 无依赖任务数，作为“可并行任务数量”的近似约束指标。
        int parallelCount = 0;
        // subAgentCount: sub_agent 类型任务数量。
        int subAgentCount = 0;
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
            if (task.getDependsOn() == null || task.getDependsOn().isEmpty()) {
                parallelCount += 1;
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
                subAgentCount += 1;
            }
            List<String> deps = task.getDependsOn();
            if (deps != null && deps.contains(task.getId())) {
                return Result.builder().valid(false).reason("task depends on itself").build();
            }
        }
        if (maxParallelTasks > 0 && parallelCount > maxParallelTasks) {
            return Result.builder().valid(false)
                    .reason("parallel tasks exceed limit: " + parallelCount + " > " + maxParallelTasks)
                    .build();
        }
        if (maxSubAgents > 0 && subAgentCount > maxSubAgents) {
            return Result.builder().valid(false)
                    .reason("sub_agent tasks exceed limit: " + subAgentCount + " > " + maxSubAgents)
                    .build();
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
