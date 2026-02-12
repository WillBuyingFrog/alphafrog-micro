package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunListItemMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentToolMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DeleteAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.DubboAgentDubboServiceTriple;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsResponse;
import world.willfrog.alphafrogmicro.agent.idl.PauseAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.SubmitAgentFeedbackRequest;
import world.willfrog.alphafrogmicro.agent.idl.AgentRetentionConfigMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentFeatureConfigMessage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@DubboService
@RequiredArgsConstructor
@Slf4j
public class AgentDubboServiceImpl extends DubboAgentDubboServiceTriple.AgentDubboServiceImplBase {

    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentEventService eventService;
    private final AgentRunExecutor executor;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final AgentLlmResolver llmResolver;
    private final AgentArtifactService artifactService;
    private final ObjectMapper objectMapper;

    @Value("${agent.run.list.default-days:30}")
    private int listDefaultDays;

    @Value("${agent.artifact.retention-days.normal:7}")
    private int artifactRetentionNormalDays;

    @Value("${agent.artifact.retention-days.admin:30}")
    private int artifactRetentionAdminDays;

    @Value("${agent.api.max-polling-interval-seconds:3}")
    private int maxPollingIntervalSeconds;

    @Value("${agent.flow.parallel.enabled:true}")
    private boolean parallelExecutionEnabled;

    @Value("${agent.run.checkpoint-version:v1}")
    private String checkpointVersion;

    /**
     * 创建 run 并触发异步执行。
     *
     * @param request 创建请求
     * @return run 信息
     */
    @Override
    public AgentRunMessage createRun(CreateAgentRunRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        llmResolver.resolve(request.getEndpointName(), request.getModelName());
        var run = eventService.createRun(
                userId,
                message,
                request.getContextJson(),
                request.getIdempotencyKey(),
                request.getModelName(),
                request.getEndpointName(),
                request.getCaptureLlmRequests(),
                request.getProvider(),
                request.getPlannerCandidateCount(),
                request.getDebugMode()
        );
        executor.executeAsync(run.getId());
        return toRunMessage(run);
    }

    /**
     * 获取 run 基本信息。
     *
     * @param request 查询请求
     * @return run 信息
     */
    @Override
    public AgentRunMessage getRun(GetAgentRunRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        return toRunMessage(run);
    }

    /**
     * 按用户分页查询历史 run 列表。
     *
     * @param request 查询请求
     * @return 列表结果
     */
    @Override
    public ListAgentRunsResponse listRuns(ListAgentRunsRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        int limit = request.getLimit() <= 0 ? 20 : Math.min(request.getLimit(), 100);
        int offset = Math.max(0, request.getOffset());
        AgentRunStatus statusFilter = parseStatusFilter(request.getStatus());
        int days = request.getDays() > 0 ? request.getDays() : listDefaultDays;
        OffsetDateTime fromTime = days > 0 ? OffsetDateTime.now().minusDays(days) : null;

        List<AgentRun> runs = runMapper.listByUser(userId, statusFilter, fromTime, limit, offset);
        int total = runMapper.countByUser(userId, statusFilter, fromTime);
        boolean hasMore = offset + runs.size() < total;

        ListAgentRunsResponse.Builder builder = ListAgentRunsResponse.newBuilder();
        builder.setTotal(total);
        builder.setHasMore(hasMore);
        for (AgentRun run : runs) {
            run = markExpiredIfNeeded(run);
            String userGoal = eventService.extractUserGoal(run.getExt());
            boolean hasArtifacts = hasVisibleArtifacts(run);
            AgentObservabilityService.ListMetrics metrics = observabilityService.extractListMetrics(run.getSnapshotJson());
            builder.addItems(AgentRunListItemMessage.newBuilder()
                    .setId(nvl(run.getId()))
                    .setMessage(nvl(userGoal))
                    .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                    .setCreatedAt(run.getStartedAt() == null ? "" : run.getStartedAt().toString())
                    .setCompletedAt(run.getCompletedAt() == null ? "" : run.getCompletedAt().toString())
                    .setHasArtifacts(hasArtifacts)
                    .setDurationMs(Math.max(0L, metrics.durationMs()))
                    .setTotalTokens(Math.max(0, metrics.totalTokens()))
                    .setToolCalls(Math.max(0, metrics.toolCalls()))
                    .build());
        }
        return builder.build();
    }

    /**
     * 列出 run 事件流（分页）。
     *
     * @param request 查询请求
     * @return 事件分页结果
     */
    @Override
    public ListAgentRunEventsResponse listEvents(ListAgentRunEventsRequest request) {
        requireRun(request.getId(), request.getUserId());
        int afterSeq = Math.max(0, request.getAfterSeq());
        int limit = request.getLimit() <= 0 ? 200 : Math.min(request.getLimit(), 500);
        List<AgentRunEvent> events = eventMapper.listByRunIdAfterSeq(request.getId(), afterSeq, limit + 1);
        boolean hasMore = events.size() > limit;
        if (hasMore) {
            events = events.subList(0, limit);
        }
        int nextAfterSeq = afterSeq;
        ListAgentRunEventsResponse.Builder builder = ListAgentRunEventsResponse.newBuilder();
        for (AgentRunEvent e : events) {
            builder.addItems(toEventMessage(e));
            if (e.getSeq() != null) {
                nextAfterSeq = Math.max(nextAfterSeq, e.getSeq());
            }
        }
        builder.setNextAfterSeq(nextAfterSeq);
        builder.setHasMore(hasMore);
        return builder.build();
    }

    /**
     * 删除指定 run（运行中任务禁止删除）。
     *
     * @param request 删除请求
     * @return 空响应
     */
    @Override
    public AgentEmpty deleteRun(DeleteAgentRunRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
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
     * 取消 run 执行。
     *
     * @param request 取消请求
     * @return 取消后的 run 信息
     */
    @Override
    public AgentRunMessage cancelRun(CancelAgentRunRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        if (isTerminal(run.getStatus())) {
            return toRunMessage(run);
        }
        runMapper.updateStatusWithTtl(run.getId(), run.getUserId(), AgentRunStatus.CANCELED, eventService.nextInterruptedExpiresAt());
        eventService.append(run.getId(), run.getUserId(), "CANCELED", Map.of("run_id", run.getId()));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.CANCELED.name());
        return toRunMessage(requireRun(run.getId(), run.getUserId()));
    }

    /**
     * 暂停 run 执行。
     *
     * @param request 暂停请求
     * @return 暂停后的 run 信息
     */
    @Override
    public AgentRunMessage pauseRun(PauseAgentRunRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        if (isTerminal(run.getStatus())) {
            return toRunMessage(run);
        }
        runMapper.updateStatusWithTtl(run.getId(), run.getUserId(), AgentRunStatus.WAITING, eventService.nextInterruptedExpiresAt());
        eventService.append(run.getId(), run.getUserId(), "PAUSED", Map.of("run_id", run.getId()));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.WAITING.name());
        return toRunMessage(requireRun(run.getId(), run.getUserId()));
    }

    /**
     * 续做已失败或已取消的 run。
     *
     * @param request 续做请求
     * @return 续做后的 run 信息
     */
    @Override
    public AgentRunMessage resumeRun(ResumeAgentRunRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        if (run.getStatus() == AgentRunStatus.EXPIRED) {
            throw new IllegalStateException("run expired");
        }
        ensureCheckpointCompatible(run);
        if (run.getStatus() != AgentRunStatus.FAILED
                && run.getStatus() != AgentRunStatus.CANCELED
                && run.getStatus() != AgentRunStatus.WAITING) {
            return toRunMessage(run);
        }
        if (request.getPlanOverrideJson() != null && !request.getPlanOverrideJson().isBlank()) {
            stateStore.clearTasks(run.getId());
            stateStore.storePlanOverride(run.getId(), request.getPlanOverrideJson());
        }
        runMapper.resetForResume(run.getId(), run.getUserId(), eventService.nextTtlExpiresAt());
        eventService.append(run.getId(), run.getUserId(), "RESUMED", Map.of("run_id", run.getId()));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.RECEIVED.name());
        executor.executeAsync(run.getId());
        return toRunMessage(requireRun(run.getId(), run.getUserId()));
    }

    /**
     * 获取 run 的最终结果（若已完成）。
     *
     * @param request 查询请求
     * @return 结果信息
     */
    @Override
    public AgentRunResultMessage getResult(GetAgentRunResultRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        String snapshotJson = run.getSnapshotJson();
        String answer = "";
        if (snapshotJson != null && !snapshotJson.isBlank()) {
            try {
                Map<?, ?> snap = objectMapper.readValue(snapshotJson, Map.class);
                Object v = snap.get("answer");
                answer = v == null ? "" : String.valueOf(v);
            } catch (Exception ignore) {
                // ignore
            }
        }
        return AgentRunResultMessage.newBuilder()
                .setId(run.getId())
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setAnswer(answer == null ? "" : answer)
                .setPayloadJson(snapshotJson == null ? "" : snapshotJson)
                .setObservabilityJson(nvl(observabilityService.loadObservabilityJson(run.getId(), snapshotJson)))
                .build();
    }

    /**
     * 获取 run 当前执行状态（基于最新事件）。
     *
     * @param request 查询请求
     * @return 当前状态
     */
    @Override
    public AgentRunStatusMessage getStatus(GetAgentRunStatusRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        AgentRunEvent latestEvent = eventMapper.findLatestByRunId(run.getId());
        String planJson = run.getPlanJson() == null ? "" : run.getPlanJson();
        var cachedPlan = stateStore.loadPlan(run.getId());
        if (cachedPlan.isPresent()) {
            planJson = cachedPlan.get();
        }
        String progressJson = "";
        if (planJson != null && !planJson.isBlank()) {
            progressJson = stateStore.buildProgressJson(run.getId(), planJson);
        }
        String observabilityJson = observabilityService.loadObservabilityJson(run.getId(), run.getSnapshotJson());
        return toStatusMessage(run, latestEvent, planJson, progressJson, observabilityJson);
    }

    /**
     * 获取可用工具列表。
     *
     * @param request 查询请求
     * @return 工具列表
     */
    @Override
    public ListAgentToolsResponse listTools(ListAgentToolsRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        return ListAgentToolsResponse.newBuilder()
                .addItems(AgentToolMessage.newBuilder()
                        .setName("getStockInfo")
                        .setDescription("Get basic information about a stock by its TS code (e.g., 000001.SZ)")
                        .setParametersJson("{\"tsCode\":\"string\"}")
                        .build())
                .addItems(AgentToolMessage.newBuilder()
                        .setName("getStockDaily")
                        .setDescription("Get daily stock market data for a specific stock within a date range")
                        .setParametersJson("{\"tsCode\":\"string\",\"startDateStr\":\"YYYYMMDD\",\"endDateStr\":\"YYYYMMDD\"}")
                        .build())
                .addItems(AgentToolMessage.newBuilder()
                        .setName("searchStock")
                        .setDescription("Search for a stock by keyword")
                        .setParametersJson("{\"keyword\":\"string\"}")
                        .build())
                .addItems(AgentToolMessage.newBuilder()
                        .setName("searchFund")
                        .setDescription("Search for a fund by keyword")
                        .setParametersJson("{\"keyword\":\"string\"}")
                        .build())
                .addItems(AgentToolMessage.newBuilder()
                        .setName("getIndexInfo")
                        .setDescription("Get basic information about an index by its TS code (e.g., 000300.SH)")
                        .setParametersJson("{\"tsCode\":\"string\"}")
                        .build())
                .addItems(AgentToolMessage.newBuilder()
                        .setName("getIndexDaily")
                        .setDescription("Get daily index market data for a specific index within a date range")
                        .setParametersJson("{\"tsCode\":\"string\",\"startDateStr\":\"YYYYMMDD\",\"endDateStr\":\"YYYYMMDD\"}")
                        .build())
                .addItems(AgentToolMessage.newBuilder()
                        .setName("searchIndex")
                        .setDescription("Search for an index by keyword")
                        .setParametersJson("{\"keyword\":\"string\"}")
                        .build())
                .build();
    }

    /**
     * 列出 run 的产物列表。
     *
     * @param request 查询请求
     * @return 产物列表
     */
    @Override
    public ListAgentArtifactsResponse listArtifacts(ListAgentArtifactsRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        return ListAgentArtifactsResponse.newBuilder()
                .addAllItems(artifactService.listArtifacts(run, request.getIsAdmin()))
                .build();
    }

    /**
     * 下载指定 artifact 内容。
     *
     * @param request 下载请求
     * @return 文件内容
     */
    @Override
    public DownloadAgentArtifactResponse downloadArtifact(DownloadAgentArtifactRequest request) {
        String runId = artifactService.extractRunId(request.getArtifactId());
        AgentRun run = requireRun(runId, request.getUserId());
        AgentArtifactService.ArtifactContent artifact = artifactService.loadArtifact(
                run,
                request.getIsAdmin(),
                request.getArtifactId()
        );
        return DownloadAgentArtifactResponse.newBuilder()
                .setArtifactId(artifact.artifactId())
                .setFilename(nvl(artifact.filename()))
                .setContentType(nvl(artifact.contentType()))
                .setContent(com.google.protobuf.ByteString.copyFrom(artifact.content()))
                .build();
    }

    /**
     * 获取 Agent 前端所需配置。
     *
     * @param request 配置请求
     * @return 配置结果
     */
    @Override
    public GetAgentConfigResponse getConfig(GetAgentConfigRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        return GetAgentConfigResponse.newBuilder()
                .setRetentionDays(AgentRetentionConfigMessage.newBuilder()
                        .setNormalDays(Math.max(0, artifactRetentionNormalDays))
                        .setAdminDays(Math.max(0, artifactRetentionAdminDays))
                        .build())
                .setMaxPollingInterval(Math.max(1, maxPollingIntervalSeconds))
                .setFeatures(AgentFeatureConfigMessage.newBuilder()
                        .setParallelExecution(parallelExecutionEnabled)
                        .setPauseResume(true)
                        .build())
                .build();
    }

    /**
     * 提交用户反馈。
     *
     * @param request 反馈请求
     * @return 空响应
     */
    @Override
    public AgentEmpty submitFeedback(SubmitAgentFeedbackRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        eventService.append(run.getId(), run.getUserId(), "FEEDBACK_RECEIVED", Map.of(
                "rating", request.getRating(),
                "comment", request.getComment(),
                "tags_json", request.getTagsJson(),
                "payload_json", request.getPayloadJson()
        ));
        return AgentEmpty.newBuilder().build();
    }

    /**
     * 导出 run 结果（当前为 MVP stub）。
     *
     * @param request 导出请求
     * @return 导出响应
     */
    @Override
    public ExportAgentRunResponse exportRun(ExportAgentRunRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        String exportId = java.util.UUID.randomUUID().toString().replace("-", "");
        eventService.append(run.getId(), run.getUserId(), "EXPORT_REQUESTED", Map.of(
                "export_id", exportId,
                "format", request.getFormat()
        ));
        return ExportAgentRunResponse.newBuilder()
                .setExportId(exportId)
                .setStatus("not_implemented")
                .setUrl("")
                .setMessage("export not implemented in MVP yet")
                .build();
    }

    private AgentRun requireRun(String id, String userId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        AgentRun run = runMapper.findByIdAndUser(id, userId);
        if (run == null) {
            throw new IllegalArgumentException("run not found");
        }
        return markExpiredIfNeeded(run);
    }

    private AgentRun markExpiredIfNeeded(AgentRun run) {
        if (run == null) {
            return null;
        }
        if (!eventService.shouldMarkExpired(run)) {
            return run;
        }
        runMapper.updateStatus(run.getId(), run.getUserId(), AgentRunStatus.EXPIRED);
        eventService.append(run.getId(), run.getUserId(), "RUN_EXPIRED", Map.of(
                "run_id", run.getId(),
                "expired_at", OffsetDateTime.now().toString()
        ));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.EXPIRED.name());
        AgentRun refreshed = runMapper.findByIdAndUser(run.getId(), run.getUserId());
        return refreshed == null ? run : refreshed;
    }

    private void ensureCheckpointCompatible(AgentRun run) {
        String expected = checkpointVersion == null || checkpointVersion.isBlank() ? "v1" : checkpointVersion.trim();
        String actual = readExtField(run.getExt(), "checkpoint_version");
        if (actual == null || actual.isBlank() || expected.equals(actual)) {
            return;
        }
        String originalMessage = eventService.extractUserGoal(run.getExt());
        String payload = "reason=SNAPSHOT_VERSION_INCOMPATIBLE; suggested_action=CREATE_NEW_RUN_WITH_ORIGINAL_MESSAGE; original_message="
                + nvl(originalMessage);
        throw new IllegalStateException(payload);
    }

    private String readExtField(String extJson, String field) {
        if (extJson == null || extJson.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> ext = objectMapper.readValue(extJson, Map.class);
            Object value = ext.get(field);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean hasVisibleArtifacts(AgentRun run) {
        try {
            return !artifactService.listArtifacts(run, false).isEmpty();
        } catch (Exception e) {
            log.warn("Resolve hasArtifacts failed for runId={}", run.getId(), e);
            return false;
        }
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

    private AgentRunStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AgentRunStatus.valueOf(status.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid status filter: " + status);
        }
    }

    private AgentRunMessage toRunMessage(AgentRun run) {
        return AgentRunMessage.newBuilder()
                .setId(nvl(run.getId()))
                .setUserId(nvl(run.getUserId()))
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setCurrentStep(run.getCurrentStep() == null ? 0 : run.getCurrentStep())
                .setMaxSteps(run.getMaxSteps() == null ? 0 : run.getMaxSteps())
                .setPlanJson(nvl(run.getPlanJson()))
                .setSnapshotJson(nvl(run.getSnapshotJson()))
                .setLastError(nvl(run.getLastError()))
                .setTtlExpiresAt(run.getTtlExpiresAt() == null ? "" : run.getTtlExpiresAt().toString())
                .setStartedAt(run.getStartedAt() == null ? "" : run.getStartedAt().toString())
                .setUpdatedAt(run.getUpdatedAt() == null ? "" : run.getUpdatedAt().toString())
                .setCompletedAt(run.getCompletedAt() == null ? "" : run.getCompletedAt().toString())
                .setExt(nvl(run.getExt()))
                .build();
    }

    private AgentRunEventMessage toEventMessage(AgentRunEvent e) {
        return AgentRunEventMessage.newBuilder()
                .setId(e.getId() == null ? 0L : e.getId())
                .setRunId(nvl(e.getRunId()))
                .setSeq(e.getSeq() == null ? 0 : e.getSeq())
                .setEventType(nvl(e.getEventType()))
                .setPayloadJson(nvl(e.getPayloadJson()))
                .setCreatedAt(e.getCreatedAt() == null ? "" : e.getCreatedAt().toString())
                .build();
    }

    private AgentRunStatusMessage toStatusMessage(AgentRun run,
                                                  AgentRunEvent lastEvent,
                                                  String planJson,
                                                  String progressJson,
                                                  String observabilityJson) {
        String lastEventType = lastEvent == null ? "" : nvl(lastEvent.getEventType());
        String currentTool = "";
        if ("TOOL_CALL_STARTED".equals(lastEventType) && lastEvent.getPayloadJson() != null) {
            currentTool = readToolName(lastEvent.getPayloadJson());
        }
        String phase = resolvePhase(run.getStatus(), lastEventType);
        return AgentRunStatusMessage.newBuilder()
                .setId(nvl(run.getId()))
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setPhase(phase)
                .setCurrentTool(nvl(currentTool))
                .setLastEventType(lastEventType)
                .setLastEventAt(lastEvent == null || lastEvent.getCreatedAt() == null ? "" : lastEvent.getCreatedAt().toString())
                .setLastEventPayloadJson(lastEvent == null ? "" : nvl(lastEvent.getPayloadJson()))
                .setPlanJson(nvl(planJson))
                .setProgressJson(nvl(progressJson))
                .setObservabilityJson(nvl(observabilityJson))
                .build();
    }

    private String resolvePhase(AgentRunStatus status, String lastEventType) {
        if (status == null) {
            return "";
        }
        if (status == AgentRunStatus.COMPLETED) {
            return "COMPLETED";
        }
        if (status == AgentRunStatus.FAILED) {
            return "FAILED";
        }
        if (status == AgentRunStatus.CANCELED) {
            return "CANCELED";
        }
        if (status == AgentRunStatus.EXPIRED) {
            return "EXPIRED";
        }
        if (status == AgentRunStatus.WAITING) {
            return "PAUSED";
        }
        if ("PLANNING_STARTED".equals(lastEventType) || "PLAN_STARTED".equals(lastEventType) || "PLAN_CREATED".equals(lastEventType)) {
            return "PLANNING";
        }
        if ("TOOL_CALL_STARTED".equals(lastEventType)) {
            return "EXECUTING_TOOL";
        }
        if ("PARALLEL_TASK_STARTED".equals(lastEventType)) {
            return "EXECUTING";
        }
        if ("SUMMARIZING_STARTED".equals(lastEventType)) {
            return "SUMMARIZING";
        }
        if ("EXECUTION_STARTED".equals(lastEventType) || "EXECUTION_FINISHED".equals(lastEventType)) {
            return "EXECUTING";
        }
        return status.name();
    }

    private String readToolName(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> map = objectMapper.readValue(payloadJson, Map.class);
            Object v = map.get("tool_name");
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    private String nvl(String v) {
        return v == null ? "" : v;
    }
}
