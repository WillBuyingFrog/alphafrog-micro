package world.willfrog.agentlangchain.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentLangchainOrchestrator {

    private final UnsupportedOrchestrationService unsupportedOrchestrationService;
    private final ObjectProvider<LangchainLinearRunPipeline> linearRunPipelineProvider;

    public void assertRunExecutionDisabled() {
        unsupportedOrchestrationService.rejectExecution();
    }

    public String unimplementedStatus() {
        if (linearRunPipelineProvider.getIfAvailable() != null) {
            return "langchain_linear_pipeline_registered";
        }
        return unsupportedOrchestrationService.statusMessage();
    }
}
