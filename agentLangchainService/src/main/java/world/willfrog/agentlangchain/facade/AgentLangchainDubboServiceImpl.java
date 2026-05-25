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

/**
 * agentLangchainService 的 Dubbo RPC 入口 —— 实现与 legacy agentService 相同的
 * {@code DubboAgentDubboService} 接口，但注册在独立的 {@code group=langchain}。
 *
 * <h2>设计意图</h2>
 * 这是整个 langchain 服务的"薄网关层"。所有方法都是简单的委托调用：
 * <ul>
 *   <li>写操作（createRun）→ {@link AgentLangchainRunService}</li>
 *   <li>读操作（getRun、getStatus、listRuns 等）→ {@link LangchainRunReadService}</li>
 *   <li>控制操作（cancel、pause、resume、delete）→ {@link LangchainRunControlService}</li>
 * </ul>
 * 不包含任何业务逻辑，只是 RPC 协议到内部 service 的路由。
 *
 * <h2>group=langchain 隔离</h2>
 * 注册在 {@code group=langchain}，与 legacy agentService（默认 group）隔离。
 * frontend 通过路径分流（POST /api/agent/runs → langchain，其余 → legacy）
 * 决定调用哪个 provider。
 *
 * <h2>条件装配</h2>
 * 仅在 {@code agent.langchain.provider.enabled=true} 时激活。
 * 这个开关同时控制 Nacos 注册——关闭时 langchain 服务不注册 Dubbo provider，
 * 所有流量自动走 legacy。
 *
 * @see AgentLangchainRunService 写路径
 * @see LangchainRunReadService 读路径
 * @see LangchainRunControlService 控制路径
 */
@DubboService(group = "langchain")
@ConditionalOnProperty(prefix = "agent.langchain.provider", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AgentLangchainDubboServiceImpl extends DubboAgentDubboServiceTriple.AgentDubboServiceImplBase {

    private final AgentLangchainRunService runService;
    private final LangchainRunReadService readService;
    private final LangchainRunControlService controlService;
    private final LangchainFollowUpService followUpService;
    private final LangchainArtifactFacadeService artifactFacadeService;
    private final AgentLangchainOrchestrator orchestrator;

    @Override
    public AgentRunMessage createRun(CreateAgentRunRequest request) {
        return runService.createRun(request);
    }

    @Override
    public AgentRunMessage getRun(GetAgentRunRequest request) {
        return readService.getRun(request);
    }

    @Override
    public AgentRunMessage updateRun(UpdateAgentRunRequest request) {
        return readService.updateRun(request);
    }

    @Override
    public ListAgentRunsResponse listRuns(ListAgentRunsRequest request) {
        return readService.listRuns(request);
    }

    @Override
    public ListAgentRunEventsResponse listEvents(ListAgentRunEventsRequest request) {
        return readService.listEvents(request);
    }

    @Override
    public AgentEmpty deleteRun(DeleteAgentRunRequest request) {
        return controlService.deleteRun(request);
    }

    @Override
    public AgentRunMessage cancelRun(CancelAgentRunRequest request) {
        return controlService.cancelRun(request);
    }

    @Override
    public AgentRunMessage pauseRun(PauseAgentRunRequest request) {
        return controlService.pauseRun(request);
    }

    @Override
    public AgentRunMessage resumeRun(ResumeAgentRunRequest request) {
        return controlService.resumeRun(request);
    }

    @Override
    public AgentRunResultMessage getResult(GetAgentRunResultRequest request) {
        return readService.getResult(request);
    }

    @Override
    public AgentRunStatusMessage getStatus(GetAgentRunStatusRequest request) {
        return readService.getStatus(request);
    }

    @Override
    public ListAgentToolsResponse listTools(ListAgentToolsRequest request) {
        return readService.listTools(request);
    }

    @Override
    public ListAgentArtifactsResponse listArtifacts(ListAgentArtifactsRequest request) {
        return artifactFacadeService.listArtifacts(request);
    }

    @Override
    public DownloadAgentArtifactResponse downloadArtifact(DownloadAgentArtifactRequest request) {
        return artifactFacadeService.downloadArtifact(request);
    }

    @Override
    public GetAgentConfigResponse getConfig(GetAgentConfigRequest request) {
        return readService.getConfig(request);
    }

    @Override
    public ListAgentModelsResponse listModels(ListAgentModelsRequest request) {
        return readService.listModels(request);
    }

    @Override
    public GetAgentCreditsResponse getCredits(GetAgentCreditsRequest request) {
        return readService.getCredits(request);
    }

    @Override
    public ApplyAgentCreditsResponse applyCredits(ApplyAgentCreditsRequest request) {
        return readService.applyCredits(request);
    }

    @Override
    public AgentEmpty submitFeedback(SubmitAgentFeedbackRequest request) {
        return readService.submitFeedback(request);
    }

    @Override
    public ExportAgentRunResponse exportRun(ExportAgentRunRequest request) {
        return readService.exportRun(request);
    }

    @Override
    public SendAgentMessageResponse sendMessage(SendAgentMessageRequest request) {
        return followUpService.sendMessage(request);
    }

    @Override
    public ListAgentMessagesResponse listMessages(ListAgentMessagesRequest request) {
        return readService.listMessages(request);
    }

    @Override
    public AgentSnapshotPartsMetaMessage getSnapshotPartsMeta(GetAgentSnapshotPartsRequest request) {
        return readService.getSnapshotPartsMeta(request);
    }

    @Override
    public AgentSnapshotPartMessage getSnapshotPart(GetAgentSnapshotPartRequest request) {
        return readService.getSnapshotPart(request);
    }

    private <T> T reject() {
        orchestrator.assertRunExecutionDisabled();
        throw new UnsupportedOperationException("unreachable");
    }
}
