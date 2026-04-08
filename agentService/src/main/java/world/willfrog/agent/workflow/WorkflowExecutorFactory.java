package world.willfrog.agent.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 根据执行模式选择合适的工作流执行器。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkflowExecutorFactory {

    private final LinearWorkflowExecutor linearWorkflowExecutor;
    private final DagWorkflowExecutor dagWorkflowExecutor;
    private final ExecutionModeSelector executionModeSelector;

    /**
     * 根据 TodoPlan 特征选择执行器。
     */
    public WorkflowExecutor select(TodoPlan plan) {
        PlanExecutionMode mode = executionModeSelector.select(plan);
        return selectByMode(mode);
    }

    /**
     * 根据指定的执行模式选择执行器。
     */
    public WorkflowExecutor selectByMode(PlanExecutionMode mode) {
        return switch (mode) {
            case DAG -> {
                log.info("Selected DAG workflow executor");
                yield dagWorkflowExecutor;
            }
            case LINEAR, AUTO -> {
                log.info("Selected Linear workflow executor (mode={})", mode);
                yield linearWorkflowExecutor;
            }
        };
    }
}
