package world.willfrog.agent.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

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
     * 
     * <p>DAG 仅在以下条件下触发：
     * <ul>
     *   <li>Plan 中存在显式的 dependsOn 依赖关系标注</li>
     *   <li>Plan 中存在 parallelizable 并行化标注</li>
     * </ul>
     * 不再使用"独立任务数 ≥ 3"的启发式规则，因为该阈值过于激进。
     * 没有显式依赖/并行化标注的 Plan 将默认使用 LINEAR 模式。</p>
     */
    PlanExecutionMode autoSelect(TodoPlan plan) {
        List<TodoItem> items = plan.getItems();

        // 检查是否有可并行化标注
        boolean hasParallelizable = items.stream()
                .anyMatch(TodoItem::isParallelizable);
        if (hasParallelizable) {
            log.debug("Plan has parallelizable annotation, selecting DAG mode");
            return PlanExecutionMode.DAG;
        }

        // 依赖结构判断：仅分支/汇聚（或多起点）触发 DAG，单链回退 LINEAR
        Set<String> knownIds = new HashSet<>();
        for (TodoItem item : items) {
            if (item != null && item.getId() != null && !item.getId().isBlank()) {
                knownIds.add(item.getId());
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();
        for (String id : knownIds) {
            inDegree.put(id, 0);
            outDegree.put(id, 0);
        }

        boolean hasDependencyEdge = false;
        for (TodoItem item : items) {
            if (item == null || item.getId() == null || item.getId().isBlank()) {
                continue;
            }
            List<String> deps = item.getDependsOn();
            if (deps == null || deps.isEmpty()) {
                continue;
            }
            for (String depId : deps) {
                if (depId == null || depId.isBlank() || !knownIds.contains(depId)) {
                    continue;
                }
                hasDependencyEdge = true;
                inDegree.put(item.getId(), inDegree.getOrDefault(item.getId(), 0) + 1);
                outDegree.put(depId, outDegree.getOrDefault(depId, 0) + 1);
            }
        }

        if (!hasDependencyEdge) {
            return PlanExecutionMode.LINEAR;
        }

        boolean hasBranch = outDegree.values().stream().anyMatch(v -> v > 1);
        boolean hasMerge = inDegree.values().stream().anyMatch(v -> v > 1);
        long rootCount = inDegree.values().stream().filter(v -> v == 0).count();

        if (hasBranch || hasMerge || rootCount > 1) {
            log.debug("Plan has DAG structure (branch={}, merge={}, roots={}), selecting DAG mode",
                    hasBranch, hasMerge, rootCount);
            return PlanExecutionMode.DAG;
        }

        // 其余视为单链依赖，回退 LINEAR
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
