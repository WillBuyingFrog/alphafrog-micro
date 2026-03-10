package world.willfrog.agent.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 执行模式选择器。
 *
 * <p>根据 TodoPlan 的特征自动选择执行模式（LINEAR 或 DAG），
 * 并预留多判官模型投票决策接口。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ExecutionModeSelector {

    /**
     * 判官投票接口（预留），未来支持多判官模型投票。
     */
    public interface ExecutionModeJudge {
        String getName();
        PlanExecutionMode vote(TodoPlan plan, Map<String, Object> context);
    }

    /**
     * 根据 Plan 特征选择执行模式。
     */
    public PlanExecutionMode select(TodoPlan plan) {
        if (plan == null || plan.getItems() == null || plan.getItems().isEmpty()) {
            return PlanExecutionMode.LINEAR;
        }

        // 如果 Plan 显式指定了执行模式且非 AUTO，直接返回
        if (plan.getExecutionMode() != null && plan.getExecutionMode() != PlanExecutionMode.AUTO) {
            return plan.getExecutionMode();
        }

        // 自动选择策略
        return autoSelect(plan);
    }

    /**
     * 自动选择策略：根据 Plan 特征决定使用 LINEAR 或 DAG。
     */
    PlanExecutionMode autoSelect(TodoPlan plan) {
        List<TodoItem> items = plan.getItems();

        // 检查是否存在依赖关系标注
        boolean hasDependencies = items.stream()
                .anyMatch(item -> item.getDependsOn() != null && !item.getDependsOn().isEmpty());

        // 检查是否有可并行化标注
        boolean hasParallelizable = items.stream()
                .anyMatch(TodoItem::isParallelizable);

        if (hasDependencies || hasParallelizable) {
            log.debug("Plan has dependency/parallel annotations, selecting DAG mode");
            return PlanExecutionMode.DAG;
        }

        // 计算独立任务数（无依赖的任务）
        long independentCount = items.stream()
                .filter(item -> item.getDependsOn() == null || item.getDependsOn().isEmpty())
                .count();

        if (independentCount >= 3) {
            log.debug("Plan has {} independent tasks (>=3), selecting DAG mode", independentCount);
            return PlanExecutionMode.DAG;
        }

        // 默认保守策略
        return PlanExecutionMode.LINEAR;
    }

    /**
     * 多判官投票选择（预留接口）。
     */
    public PlanExecutionMode selectByVoting(TodoPlan plan,
                                            List<ExecutionModeJudge> judges,
                                            Map<String, Object> context) {
        if (judges == null || judges.isEmpty()) {
            return select(plan);
        }

        int linearVotes = 0;
        int dagVotes = 0;

        for (ExecutionModeJudge judge : judges) {
            try {
                PlanExecutionMode vote = judge.vote(plan, context);
                if (vote == PlanExecutionMode.DAG) {
                    dagVotes++;
                } else if (vote == PlanExecutionMode.LINEAR) {
                    linearVotes++;
                }
                log.debug("Judge '{}' voted: {}", judge.getName(), vote);
            } catch (Exception e) {
                log.warn("Judge '{}' voting failed: {}", judge.getName(), e.getMessage());
            }
        }

        PlanExecutionMode result = dagVotes > linearVotes ? PlanExecutionMode.DAG : PlanExecutionMode.LINEAR;
        log.debug("Voting result: DAG={}, LINEAR={}, selected={}", dagVotes, linearVotes, result);
        return result;
    }
}
