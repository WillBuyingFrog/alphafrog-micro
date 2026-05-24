package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DeleteAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.PauseAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class LangchainRunControlService {

    private final LangchainRunReadService readService;
    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final LangchainLinearRunPipeline pipeline;

    public AgentEmpty deleteRun(DeleteAgentRunRequest request) {
        AgentRun run = readService.requireWritableRun(request.getId(), request.getUserId());
        if (isRunning(run.getStatus())) {
            throw new IllegalStateException("run is running, cancel/pause first");
        }
        int deleted = runMapper.deleteByIdAndUser(run.getId(), run.getUserId());
        if (deleted <= 0) {
            throw new IllegalArgumentException("run not found");
        }
        stateStore.clear(run.getId());
        return AgentEmpty.newBuilder().build();
    }

    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage cancelRun(CancelAgentRunRequest request) {
        AgentRun run = readService.requireWritableRun(request.getId(), request.getUserId());
        if (isTerminal(run.getStatus())) {
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        }
        String runId = run.getId();
        String userId = run.getUserId();
        stateStore.markRunStatus(runId, AgentRunStatus.CANCELING.name());
        // Give the executor a short window to observe CANCELING before forcing an observability flush.
        // If cancel snapshots become incomplete, replace this with an explicit completion signal.
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Cancel langchain run {} interrupted during observability flush wait", runId);
        }
        observabilityService.forceFlush(runId);
        String snapshot = observabilityService.attachObservabilityToSnapshot(
                runId, run.getSnapshotJson(), AgentRunStatus.CANCELED);
        runMapper.updateSnapshot(runId, userId, AgentRunStatus.CANCELED, snapshot, false, null);
        runMapper.updateStatusWithTtl(runId, userId, AgentRunStatus.CANCELED, eventService.nextInterruptedExpiresAt());
        eventService.append(runId, userId, "CANCELED", Map.of(
                "run_id", runId,
                "engine", "agentLangchainService"));
        stateStore.markRunStatus(runId, AgentRunStatus.CANCELED.name());
        return AgentLangchainRunMessageMapper.toRunMessage(readService.requireReadableRun(runId, userId));
    }

    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage pauseRun(PauseAgentRunRequest request) {
        AgentRun run = readService.requireWritableRun(request.getId(), request.getUserId());
        if (isTerminal(run.getStatus())) {
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        }
        String snapshot = observabilityService.attachObservabilityToSnapshot(
                run.getId(), run.getSnapshotJson(), AgentRunStatus.WAITING);
        runMapper.updateSnapshot(run.getId(), run.getUserId(), AgentRunStatus.WAITING, snapshot, false, null);
        runMapper.updateStatusWithTtl(run.getId(), run.getUserId(), AgentRunStatus.WAITING,
                eventService.nextInterruptedExpiresAt());
        eventService.append(run.getId(), run.getUserId(), "PAUSED", Map.of(
                "run_id", run.getId(),
                "engine", "agentLangchainService"));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.WAITING.name());
        return AgentLangchainRunMessageMapper.toRunMessage(readService.requireReadableRun(run.getId(), run.getUserId()));
    }

    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage resumeRun(ResumeAgentRunRequest request) {
        AgentRun run = readService.requireWritableRun(request.getId(), request.getUserId());
        if (run.getStatus() == AgentRunStatus.EXPIRED) {
            throw new IllegalStateException("run expired");
        }
        if (run.getStatus() != AgentRunStatus.FAILED
                && run.getStatus() != AgentRunStatus.CANCELED
                && run.getStatus() != AgentRunStatus.WAITING) {
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        }
        if (request.getPlanOverrideJson() != null && !request.getPlanOverrideJson().isBlank()) {
            stateStore.clearTasks(run.getId());
            stateStore.storePlanOverride(run.getId(), request.getPlanOverrideJson());
        }
        runMapper.resetForResume(run.getId(), run.getUserId(), eventService.nextTtlExpiresAt());
        eventService.append(run.getId(), run.getUserId(), "WORKFLOW_RESUMED", Map.of(
                "run_id", run.getId(),
                "engine", "agentLangchainService"));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.RECEIVED.name());
        AgentRun refreshed = readService.requireReadableRun(run.getId(), run.getUserId());
        pipeline.launchAsync(refreshed);
        return AgentLangchainRunMessageMapper.toRunMessage(refreshed);
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }

    private boolean isRunning(AgentRunStatus status) {
        return status == AgentRunStatus.RECEIVED
                || status == AgentRunStatus.PLANNING
                || status == AgentRunStatus.EXECUTING
                || status == AgentRunStatus.SUMMARIZING;
    }
}
