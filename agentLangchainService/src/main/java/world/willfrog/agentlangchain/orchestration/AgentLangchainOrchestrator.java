package world.willfrog.agentlangchain.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentLangchainOrchestrator {

    private final UnsupportedOrchestrationService unsupportedOrchestrationService;

    public void assertRunExecutionDisabled() {
        unsupportedOrchestrationService.rejectExecution();
    }

    public String unimplementedStatus() {
        return unsupportedOrchestrationService.statusMessage();
    }
}
