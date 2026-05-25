package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;

/**
 * LINEAR vs DAG 路由决策器。~30 行薄判断层。
 *
 * <p>决策逻辑：
 * <ol>
 *   <li>plan 的 executionMode 显式为 DAG → DAG</li>
 *   <li>plan 的 executionMode 显式为 LINEAR → LINEAR</li>
 *   <li>AUTO 模式：检查是否有 Todo 声明了 dependsOn（依赖关系），
 *       有则 DAG，无则 LINEAR</li>
 * </ol>
 *
 * <p>被 {@code LangchainLinearRunPipelineImpl} 调用，
 * 决定走 DAG 执行器还是 LINEAR 执行器。
 */
final class LangchainWorkflowRouting {

    private LangchainWorkflowRouting() {
    }

    static boolean shouldUseDag(LangchainTodoPlan plan) {
        if (plan == null) {
            return false;
        }
        if (plan.getExecutionMode() == PlanExecutionMode.DAG) {
            return true;
        }
        if (plan.getExecutionMode() == PlanExecutionMode.LINEAR) {
            return false;
        }
        List<TodoItem> items = plan.getItems();
        if (items == null || items.isEmpty()) {
            return false;
        }
        for (TodoItem item : items) {
            if (item != null && item.getDependsOn() != null && !item.getDependsOn().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
