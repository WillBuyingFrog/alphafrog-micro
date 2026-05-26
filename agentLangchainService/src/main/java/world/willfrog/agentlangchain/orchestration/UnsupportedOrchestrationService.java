package world.willfrog.agentlangchain.orchestration;

import org.springframework.stereotype.Service;

@Service
public class UnsupportedOrchestrationService {

    private static final String MESSAGE =
            "agentLangchainService orchestration is not implemented in P0";

    public void rejectExecution() {
        throw new UnsupportedOperationException(MESSAGE);
    }

    public String statusMessage() {
        return MESSAGE;
    }
}
