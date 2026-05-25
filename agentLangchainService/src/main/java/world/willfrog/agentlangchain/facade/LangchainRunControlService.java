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

/**
 * Agent run 的生命周期控制服务 —— 取消（cancel）、暂停（pause）、恢复（resume）、删除（delete）。
 *
 * <h2>状态机</h2>
 * <pre>
 * RECEIVED → PLANNING → EXECUTING → SUMMARIZING → COMPLETED
 *                     ↘ (cancel) → CANCELING → CANCELED
 *                     ↘ (pause)  → WAITING   → (resume) → RECEIVED（重新执行）
 *                                                               (delete)  → （清除）
 * </pre>
 *
 * <h2>cancel 的关键步骤</h2>
 * <ol>
 *   <li>写 Redis 状态为 CANCELING —— 执行中的 todo loop 通过
 *       {@code LangchainRunExecutionGuard} 检测到后停止工具调用</li>
 *   <li>sleep 200ms 给执行器一个窗口感知 CANCELING 状态</li>
 *   <li>forceFlush observability —— 把当前观测数据刷新到 Redis</li>
 *   <li>attachObservabilityToSnapshot —— 最终观测写入 snapshot JSON</li>
 *   <li>updateSnapshot + updateStatusWithTtl —— 写 DB 并设中断 TTL</li>
 *   <li>eventService.append —— 写入 CANCELED 事件</li>
 * </ol>
 *
 * <h2>resume 的特殊处理</h2>
 * <p>如果请求带有 {@code planOverrideJson}，会先清除旧 plan 再存入新 plan，
 * 然后重置 run 状态为 RECEIVED 并异步启动新一次执行。</p>
 *
 * <h2>面试常考点</h2>
 * <ul>
 *   <li>"cancel 为什么 sleep 200ms？"→ 给执行器一个窗口去感知 CANCELING 状态并自行停止，
 *       而非直接强制 kill。这比硬 kill 更安全，正在执行的工具调用可以自然完成当前轮。</li>
 *   <li>"pause 和 cancel 有什么区别？"→ pause 把状态改为 WAITING 并保留 snapshot，
 *       resume 后从原状态继续；cancel 是终态不可恢复。</li>
 * </ul>
 *
 * @see LangchainRunReadService 读路径，提供 requireWritableRun
 * @see LangchainLinearRunPipeline 异步执行入口
 * @see AgentObservabilityService observability 落盘
 */
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

    /**
     * 删除 run 及其关联的状态数据（Redis）。
     * 仅允许在非运行状态下删除；正在执行的 run 需要先 cancel 或 pause。
     */
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

    /**
     * 取消正在进行的 run。终态不可恢复。
     * 已在终态的 run 直接返回当前状态（幂等）。
     */
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage cancelRun(CancelAgentRunRequest request) {
        AgentRun run = readService.requireWritableRun(request.getId(), request.getUserId());
        if (isTerminal(run.getStatus())) {
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        }
        String runId = run.getId();
        String userId = run.getUserId();
        stateStore.markRunStatus(runId, AgentRunStatus.CANCELING.name());
        // 给执行器 200ms 窗口去感知 CANCELING 状态（ExecutionGuard 轮询 Redis），
        // 让正在执行的 todo loop 有机会自然收尾而非被暴力中断
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

    /**
     * 暂停 run：状态改为 WAITING，可被 resume 恢复。
     * 已在终态的 run 直接返回（幂等）。
     */
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

    /**
     * 恢复被暂停或失败的 run。如果请求带有 planOverrideJson，
     * 先清除旧 plan 再存入新 plan，然后重置状态为 RECEIVED 异步重新执行。
     */
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

    /** COMPLETED / FAILED / CANCELED / EXPIRED 均为不可逆终态 */
    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }

    /** RECEIVED / PLANNING / EXECUTING / SUMMARIZING 均为运行中可中断状态 */
    private boolean isRunning(AgentRunStatus status) {
        return status == AgentRunStatus.RECEIVED
                || status == AgentRunStatus.PLANNING
                || status == AgentRunStatus.EXECUTING
                || status == AgentRunStatus.SUMMARIZING;
    }
}
