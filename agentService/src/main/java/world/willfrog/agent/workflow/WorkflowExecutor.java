package world.willfrog.agent.workflow;

public interface WorkflowExecutor {
    WorkflowExecutionResult execute(LinearWorkflowExecutor.WorkflowRequest request);
}
