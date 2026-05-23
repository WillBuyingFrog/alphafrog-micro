package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class LangchainLinearWorkflowRequest {
    private String runId;
    private String userId;
    private String userGoal;
    private String dialogueContext;
    private ChatModel model;
    private ChatModel planningModel;
    private ChatModel executionModel;
    private ChatModel finalAnswerModel;
    @Builder.Default
    private List<ToolSpecification> toolSpecifications = new ArrayList<>();
    private Integer maxTodos;
    private Integer maxToolRoundTrips;
    private Boolean webSearchEnabled;
    private Boolean codeInterpreterEnabled;
    private String planningEndpointName;
    private String planningModelName;
    private List<String> planningProviderOrder;

    ChatModel planningModelOrDefault() {
        return planningModel == null ? model : planningModel;
    }

    ChatModel executionModelOrDefault() {
        return executionModel == null ? model : executionModel;
    }

    ChatModel finalAnswerModelOrDefault() {
        return finalAnswerModel == null ? model : finalAnswerModel;
    }
}
