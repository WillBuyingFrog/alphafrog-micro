package world.willfrog.agent.workflow;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Builder;
import lombok.Data;
import world.willfrog.agent.platform.entity.AgentRun;

import java.util.List;

/**
 * 工作流执行请求。
 */
@Data
@Builder
public class WorkflowRequest {
    private AgentRun run;
    private String userId;
    private String userGoal;
    private TodoPlan plan;
    private ChatModel model;
    private ChatModel finalAnswerModel;
    private String finalAnswerReasoningEffort;
    private List<ToolSpecification> toolSpecifications;
    private String endpointName;
    private String endpointBaseUrl;
    private String modelName;
    private List<String> extractedEntities;
    @Builder.Default
    private boolean enablePlanPatch = false;
}
