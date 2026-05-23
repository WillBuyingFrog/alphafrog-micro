package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import world.willfrog.agentlangchain.orchestration.AgentLangchainOrchestrator;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentSnapshotPartMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentSnapshotPartsMetaMessage;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DeleteAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.DubboAgentDubboServiceTriple;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentSnapshotPartRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentSnapshotPartsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentModelsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentModelsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsResponse;
import world.willfrog.alphafrogmicro.agent.idl.PauseAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageResponse;
import world.willfrog.alphafrogmicro.agent.idl.SubmitAgentFeedbackRequest;
import world.willfrog.alphafrogmicro.agent.idl.UpdateAgentRunRequest;

@DubboService
@ConditionalOnProperty(prefix = "agent.langchain.provider", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AgentLangchainDubboServiceImpl extends DubboAgentDubboServiceTriple.AgentDubboServiceImplBase {

    private final AgentLangchainOrchestrator orchestrator;

    @Override
    public AgentRunMessage createRun(CreateAgentRunRequest request) {
        orchestrator.assertRunExecutionDisabled();
        throw new UnsupportedOperationException("unreachable");
    }

    @Override
    public AgentRunMessage getRun(GetAgentRunRequest request) {
        return reject();
    }

    @Override
    public AgentRunMessage updateRun(UpdateAgentRunRequest request) {
        return reject();
    }

    @Override
    public ListAgentRunsResponse listRuns(ListAgentRunsRequest request) {
        return reject();
    }

    @Override
    public ListAgentRunEventsResponse listEvents(ListAgentRunEventsRequest request) {
        return reject();
    }

    @Override
    public AgentEmpty deleteRun(DeleteAgentRunRequest request) {
        return reject();
    }

    @Override
    public AgentRunMessage cancelRun(CancelAgentRunRequest request) {
        return reject();
    }

    @Override
    public AgentRunMessage pauseRun(PauseAgentRunRequest request) {
        return reject();
    }

    @Override
    public AgentRunMessage resumeRun(ResumeAgentRunRequest request) {
        return reject();
    }

    @Override
    public AgentRunResultMessage getResult(GetAgentRunResultRequest request) {
        return reject();
    }

    @Override
    public AgentRunStatusMessage getStatus(GetAgentRunStatusRequest request) {
        return reject();
    }

    @Override
    public ListAgentToolsResponse listTools(ListAgentToolsRequest request) {
        return reject();
    }

    @Override
    public ListAgentArtifactsResponse listArtifacts(ListAgentArtifactsRequest request) {
        return reject();
    }

    @Override
    public DownloadAgentArtifactResponse downloadArtifact(DownloadAgentArtifactRequest request) {
        return reject();
    }

    @Override
    public GetAgentConfigResponse getConfig(GetAgentConfigRequest request) {
        return reject();
    }

    @Override
    public ListAgentModelsResponse listModels(ListAgentModelsRequest request) {
        return reject();
    }

    @Override
    public GetAgentCreditsResponse getCredits(GetAgentCreditsRequest request) {
        return reject();
    }

    @Override
    public ApplyAgentCreditsResponse applyCredits(ApplyAgentCreditsRequest request) {
        return reject();
    }

    @Override
    public AgentEmpty submitFeedback(SubmitAgentFeedbackRequest request) {
        return reject();
    }

    @Override
    public ExportAgentRunResponse exportRun(ExportAgentRunRequest request) {
        return reject();
    }

    @Override
    public SendAgentMessageResponse sendMessage(SendAgentMessageRequest request) {
        return reject();
    }

    @Override
    public ListAgentMessagesResponse listMessages(ListAgentMessagesRequest request) {
        return reject();
    }

    @Override
    public AgentSnapshotPartsMetaMessage getSnapshotPartsMeta(GetAgentSnapshotPartsRequest request) {
        return reject();
    }

    @Override
    public AgentSnapshotPartMessage getSnapshotPart(GetAgentSnapshotPartRequest request) {
        return reject();
    }

    private <T> T reject() {
        orchestrator.assertRunExecutionDisabled();
        throw new UnsupportedOperationException("unreachable");
    }
}
