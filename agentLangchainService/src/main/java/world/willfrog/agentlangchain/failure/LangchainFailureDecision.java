package world.willfrog.agentlangchain.failure;

import lombok.Builder;
import lombok.Data;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class LangchainFailureDecision {

    private AgentRunStatus runStatus;
    private String eventType;
    private String reason;
    private LangchainFailureCategory category;
    private boolean retryable;
    private String observabilityFailureType;
    @Builder.Default
    private Map<String, Object> eventPayload = new LinkedHashMap<>();
}
