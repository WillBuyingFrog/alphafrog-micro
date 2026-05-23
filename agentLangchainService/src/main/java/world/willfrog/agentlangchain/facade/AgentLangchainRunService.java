package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentLangchainRunService {

    private final ObjectProvider<AgentEventService> eventServiceProvider;
    private final ObjectProvider<LangchainLinearRunPipeline> linearRunPipelineProvider;

    public AgentRunMessage createRun(CreateAgentRunRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }

        AgentEventService eventService = eventServiceProvider.getIfAvailable();
        if (eventService == null) {
            throw new IllegalStateException("agent_event_service_unavailable");
        }

        AgentRun run = eventService.createRun(
                userId,
                message,
                request.getContextJson(),
                request.getIdempotencyKey(),
                request.getModelName(),
                request.getEndpointName(),
                request.getCaptureLlmRequests(),
                request.getProvider(),
                request.getPlannerCandidateCount(),
                request.getDebugMode(),
                request.getStageConfigJson()
        );

        LangchainLinearRunPipeline pipeline = linearRunPipelineProvider.getIfAvailable();
        if (pipeline != null) {
            log.info("Launching langchain linear pipeline for run {}", run.getId());
            pipeline.launchAsync(run);
        } else {
            log.warn("LangchainLinearRunPipeline not registered; run {} created but not executed", run.getId());
        }

        return AgentLangchainRunMessageMapper.toRunMessage(run);
    }
}
