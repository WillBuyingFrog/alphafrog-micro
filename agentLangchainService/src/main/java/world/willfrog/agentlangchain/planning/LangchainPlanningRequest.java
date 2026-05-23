package world.willfrog.agentlangchain.planning;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Builder;
import lombok.Data;
import world.willfrog.agent.workflow.PlanExecutionMode;

import java.util.List;

@Data
@Builder
public class LangchainPlanningRequest {

    private String runId;
    private String userId;
    private String userGoal;
    private String dialogueContext;
    private ChatModel model;
    private List<ToolSpecification> toolSpecifications;
    private PlanExecutionMode executionMode;
    private Integer maxTodos;
}
