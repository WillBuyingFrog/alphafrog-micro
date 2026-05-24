package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;

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
