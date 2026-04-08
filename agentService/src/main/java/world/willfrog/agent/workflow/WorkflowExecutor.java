package world.willfrog.agent.workflow;

/**
 * 工作流执行器接口。
 */
public interface WorkflowExecutor {
    WorkflowExecutionResult execute(WorkflowRequest request);
}
