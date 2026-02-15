package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.entity.AgentRunMessage;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunListItemMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;

import world.willfrog.alphafrogmicro.agent.idl.AgentModelMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentToolMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
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
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;
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
import world.willfrog.alphafrogmicro.agent.idl.SubmitAgentFeedbackRequest;
import world.willfrog.alphafrogmicro.agent.idl.UpdateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesResponse;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessageItem;
import world.willfrog.alphafrogmicro.agent.idl.AgentRetentionConfigMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentFeatureConfigMessage;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final AgentModelCatalogService modelCatalogService;
    private final AgentCreditService creditService;
    private final UserDao userDao;
    private final ObjectMapper objectMapper;
    private final AgentMessageService messageService;

    @Value("${agent.run.list.default-days:30}")
    private int listDefaultDays;

    @Value("${agent.artifact.retention-days.normal:7}")
    private int artifactRetentionNormalDays;

    @Value("${agent.artifact.retention-days.admin:30}")
    private int artifactRetentionAdminDays;

    @Value("${agent.api.max-polling-interval-seconds:3}")
    private int maxPollingIntervalSeconds;

    @Value("${agent.flow.parallel.enabled:false}")
    private boolean parallelExecutionEnabled;

    @Value("${agent.run.checkpoint-version:v2}")
    private String checkpointVersion;

    /**
     * 创建 run 并触发异步执行。
     *
     * @param request 创建请求
     * @return run 信息
     */
    @Override
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage createRun(CreateAgentRunRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        ensureUserActive(userId);
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
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage getRun(GetAgentRunRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        return toRunMessage(run);
    }

    @Override
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage updateRun(UpdateAgentRunRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        String runId = request.getId();
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        String title = normalizeTitle(request.getTitle());
        if (title == null) {
            throw new IllegalArgumentException("title is required");
        }
        AgentRun run = requireRun(runId, userId);
        Map<String, Object> extMap = readExtMap(run.getExt());
        extMap.put("title", title);
        String updatedExt = writeJson(extMap);
        int updated = runMapper.updateExt(runId, userId, updatedExt);
        if (updated <= 0) {
            throw new IllegalStateException("run not found");
        }
        return toRunMessage(requireRun(runId, userId));
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
        Set<String> runIdsWithArtifacts = resolveRunsWithArtifacts(runs);

        ListAgentRunsResponse.Builder builder = ListAgentRunsResponse.newBuilder();
        builder.setTotal(total);
        builder.setHasMore(hasMore);
        for (AgentRun run : runs) {
            AgentRunStatus effectiveStatus = eventService.shouldMarkExpired(run) ? AgentRunStatus.EXPIRED : run.getStatus();
            String displayTitle = eventService.extractRunDisplayTitle(run.getExt());
            boolean hasArtifacts = hasVisibleArtifacts(run, runIdsWithArtifacts);
            builder.addItems(AgentRunListItemMessage.newBuilder()
                    .setId(nvl(run.getId()))
                    .setMessage(nvl(displayTitle))
                    .setStatus(effectiveStatus == null ? "" : effectiveStatus.name())
                    .setCreatedAt(run.getStartedAt() == null ? "" : run.getStartedAt().toString())
                    .setCompletedAt(run.getCompletedAt() == null ? "" : run.getCompletedAt().toString())
                    .setHasArtifacts(hasArtifacts)
                    .setDurationMs(nonNegativeLong(run.getDurationMs()))
                    .setTotalTokens(nonNegativeInt(run.getTotalTokens()))
                    .setToolCalls(nonNegativeInt(run.getToolCalls()))
                    .build());
        }
        return builder.build();
    }

    private Set<String> resolveRunsWithArtifacts(List<AgentRun> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptySet();
        }
        List<String> runIds = runs.stream()
                .map(AgentRun::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (runIds.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return new HashSet<>(eventMapper.listRunIdsWithExecutePythonArtifacts(runIds));
        } catch (Exception e) {
            log.warn("Resolve run artifacts in batch failed, fallback empty set", e);
            return Collections.emptySet();
        }
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
     * 删除指定的 Agent Run。
     * <p>
     * 业务规则：
     * <ul>
     *   <li>运行中的任务禁止删除（状态为 RECEIVED/PLANNING/EXECUTING/SUMMARIZING），
     *       需先调用 {@link #cancelRun} 或 {@link #pauseRun}</li>
     *   <li>删除成功后，会同步清理 Redis 中的 run 状态缓存（{@link AgentRunStateStore#clear}）</li>
     * </ul>
     * <p>
     * 异常场景：
     * <ul>
     *   <li>run 不存在或不属于该用户：抛出 IllegalArgumentException("run not found")</li>
     *   <li>run 正在运行中：抛出 IllegalStateException("run is running, cancel/pause first")</li>
     * </ul>
     *
     * @param request 删除请求，包含 user_id（用户 ID）和 id（run ID）
     * @return 空响应（AgentEmpty）
     * @throws IllegalArgumentException 当 run 不存在或 user_id/id 为空时
     * @throws IllegalStateException    当 run 正在运行中时
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
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage cancelRun(CancelAgentRunRequest request) {
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
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage pauseRun(PauseAgentRunRequest request) {
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
    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage resumeRun(ResumeAgentRunRequest request) {
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
        eventService.append(run.getId(), run.getUserId(), "WORKFLOW_RESUMED", Map.of("run_id", run.getId()));
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
        String observabilityJson = nvl(observabilityService.loadObservabilityJson(run.getId(), snapshotJson));
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
        int totalCreditsConsumed = creditService.calculateRunTotalCredits(
                run,
                eventMapper.listByRunId(run.getId()),
                observabilityJson
        );
        return AgentRunResultMessage.newBuilder()
                .setId(run.getId())
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setAnswer(answer == null ? "" : answer)
                .setPayloadJson(snapshotJson == null ? "" : snapshotJson)
                .setObservabilityJson(observabilityJson)
                .setTotalCreditsConsumed(totalCreditsConsumed)
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
        int totalCreditsConsumed = creditService.calculateRunTotalCredits(
                run,
                eventMapper.listByRunId(run.getId()),
                observabilityJson
        );
        return toStatusMessage(run, latestEvent, planJson, progressJson, observabilityJson, totalCreditsConsumed);
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
                        .setParallelExecution(false)
                        .setPauseResume(true)
                        .build())
                .build();
    }

    @Override
    public ListAgentModelsResponse listModels(ListAgentModelsRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        ListAgentModelsResponse.Builder builder = ListAgentModelsResponse.newBuilder();
        for (AgentModelCatalogService.ModelCatalogItem item : modelCatalogService.listModels()) {
            builder.addModels(AgentModelMessage.newBuilder()
                    .setId(nvl(item.id()))
                    .setDisplayName(nvl(item.displayName()))
                    .setEndpoint(nvl(item.endpoint()))
                    .setCompositeId(nvl(item.compositeId()))
                    .setBaseRate(item.baseRate())
                    .addAllFeatures(item.features() == null ? List.of() : item.features())
                    .addAllValidProviders(item.validProviders() == null ? List.of() : item.validProviders())
                    .build());
        }
        return builder.build();
    }

    @Override
    public GetAgentCreditsResponse getCredits(GetAgentCreditsRequest request) {
        AgentCreditService.CreditSummary summary = creditService.getUserCredits(request.getUserId());
        return GetAgentCreditsResponse.newBuilder()
                .setTotalCredits(summary.totalCredits())
                .setRemainingCredits(summary.remainingCredits())
                .setUsedCredits(summary.usedCredits())
                .setResetCycle(nvl(summary.resetCycle()))
                .setNextResetAt(nvl(summary.nextResetAt()))
                .build();
    }

    @Override
    public ApplyAgentCreditsResponse applyCredits(ApplyAgentCreditsRequest request) {
        AgentCreditService.ApplyCreditSummary summary = creditService.applyCredits(
                request.getUserId(),
                request.getAmount(),
                request.getReason(),
                request.getContact()
        );
        return ApplyAgentCreditsResponse.newBuilder()
                .setApplicationId(nvl(summary.applicationId()))
                .setTotalCredits(summary.totalCredits())
                .setRemainingCredits(summary.remainingCredits())
                .setUsedCredits(summary.usedCredits())
                .setStatus(nvl(summary.status()))
                .setAppliedAt(nvl(summary.appliedAt()))
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

    /**
     * 发送追问消息。
     * <p>
     * 业务规则（MVP）：
     * <ul>
     *   <li>仅支持 Run 完整跑完并产出最终结果后才允许继续追问</li>
     *   <li>准入状态：仅允许 COMPLETED（如有需要可再放开 FAILED/CANCELED）</li>
     *   <li>其余状态（RECEIVED/PLANNING/EXECUTING/SUMMARIZING/WAITING）拒绝并提示用户等待/新建 run</li>
     *   <li>context_override 仅对 admin + debugMode 开放（在 frontend 层校验）</li>
     * </ul>
     * <p>
     * 执行流程：
     * 1. 校验权限和 Run 状态
     * 2. 保存用户消息（msg_type=follow_up）
     * 3. 记录 FOLLOW_UP_RECEIVED 事件
     * 4. 重置 Run 状态并触发异步执行
     *
     * @param request 发送消息请求
     * @return 发送结果
     */
    @Override
    public SendAgentMessageResponse sendMessage(SendAgentMessageRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        String runId = request.getRunId();
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id is required");
        }
        String content = request.getContent();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }

        AgentRun run = requireRun(runId, userId);

        // MVP：仅允许 COMPLETED 状态的 Run 进行追问
        // 注：如需支持 FAILED/CANCELED，可扩展为：
        // if (run.getStatus() != AgentRunStatus.COMPLETED && run.getStatus() != AgentRunStatus.FAILED && run.getStatus() != AgentRunStatus.CANCELED)
        if (run.getStatus() != AgentRunStatus.COMPLETED) {
            return SendAgentMessageResponse.newBuilder()
                    .setStatus("rejected")
                    .setRejectReason("run not completed, current status: " + run.getStatus().name() + ", please wait or create a new run")
                    .setRunStatus(run.getStatus().name())
                    .build();
        }

        // 检查 Run 是否已过期
        if (eventService.shouldMarkExpired(run)) {
            runMapper.updateStatus(runId, userId, AgentRunStatus.EXPIRED);
            eventService.append(runId, userId, "RUN_EXPIRED", Map.of(
                    "run_id", runId,
                    "expired_at", OffsetDateTime.now().toString()
            ));
            return SendAgentMessageResponse.newBuilder()
                    .setStatus("rejected")
                    .setRejectReason("run expired")
                    .setRunStatus(AgentRunStatus.EXPIRED.name())
                    .build();
        }

        // 保存用户追问消息
        String metaJson = messageService.buildMetaJson(null, null, null, null);
        AgentRunMessage userMessage = messageService.createUserMessage(runId, content, metaJson);

        // 记录 FOLLOW_UP_RECEIVED 事件
        eventService.append(runId, userId, "FOLLOW_UP_RECEIVED", Map.of(
                "seq", userMessage.getSeq(),
                "content_preview", preview(content, 200),
                "message_id", userMessage.getId()
        ));

        // 清理旧计划缓存，确保追问重新规划
        runMapper.updatePlanJson(runId, userId, "{}");
        stateStore.clearPlanCache(runId);
        stateStore.clearTasks(runId);

        // 重置 Run 状态为 RECEIVED，准备重新执行
        runMapper.resetForResume(runId, userId, eventService.nextTtlExpiresAt());
        eventService.append(runId, userId, "WORKFLOW_RESUMED", Map.of(
                "run_id", runId,
                "reason", "follow_up",
                "message_seq", userMessage.getSeq()
        ));

        // 触发异步执行
        executor.executeAsync(runId);

        return SendAgentMessageResponse.newBuilder()
                .setMessageId(userMessage.getId())
                .setSeq(userMessage.getSeq())
                .setStatus("accepted")
                .setRunStatus(AgentRunStatus.RECEIVED.name())
                .build();
    }

    /**
     * 获取 Run 的消息历史。
     * <p>
     * 权限校验：只能查询属于自己的 run 的消息
     *
     * @param request 查询请求
     * @return 消息历史列表
     */
    @Override
    public ListAgentMessagesResponse listMessages(ListAgentMessagesRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        String runId = request.getRunId();
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id is required");
        }

        // 校验权限
        requireRun(runId, userId);

        int limit = request.getLimit() <= 0 ? 50 : Math.min(request.getLimit(), 200);
        int offset = Math.max(0, request.getOffset());
        boolean includeInitial = request.getIncludeInitial();

        int total;
        java.util.List<AgentRunMessage> messages;
        if (includeInitial) {
            total = messageService.countMessages(runId);
            messages = messageService.listMessagesWithPagination(runId, limit, offset);
        } else {
            total = messageService.countMessagesExcludingInitial(runId);
            messages = messageService.listMessagesWithPaginationExcludingInitial(runId, limit, offset);
        }

        ListAgentMessagesResponse.Builder builder = ListAgentMessagesResponse.newBuilder();
        builder.setTotal(total);
        builder.setHasMore(offset + messages.size() < total);

        for (AgentRunMessage msg : messages) {
            builder.addItems(AgentRunMessageItem.newBuilder()
                    .setId(msg.getId() != null ? msg.getId() : 0L)
                    .setSeq(msg.getSeq() != null ? msg.getSeq() : 0)
                    .setRole(nvl(msg.getRole()))
                    .setContent(nvl(msg.getContent()))
                    .setMsgType(nvl(msg.getMsgType()))
                    .setMetaJson(nvl(msg.getMetaJson()))
                    .setCreatedAt(msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : "")
                    .build());
        }

        return builder.build();
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
        String expected = checkpointVersion == null || checkpointVersion.isBlank() ? "v2" : checkpointVersion.trim();
        String actual = readExtField(run.getExt(), "checkpoint_version");
        if (actual == null || actual.isBlank() || expected.equals(actual)) {
            return;
        }
        String originalMessage = eventService.extractUserGoal(run.getExt());
        String payload = "reason=SNAPSHOT_VERSION_INCOMPATIBLE; suggested_action=CREATE_NEW_RUN_WITH_ORIGINAL_MESSAGE; original_message="
                + nvl(originalMessage);
        throw new IllegalStateException(payload);
    }

    private Map<String, Object> readExtMap(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<?, ?> ext = objectMapper.readValue(extJson, Map.class);
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : ext.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String normalized = title.trim();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("title too long");
        }
        return normalized;
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

    private boolean hasVisibleArtifacts(AgentRun run, Set<String> runIdsWithArtifacts) {
        if (run == null || run.getId() == null || run.getId().isBlank()) {
            return false;
        }
        if (run.getStartedAt() == null) {
            return false;
        }
        if (artifactRetentionNormalDays > 0) {
            OffsetDateTime visibleSince = OffsetDateTime.now().minusDays(artifactRetentionNormalDays);
            if (run.getStartedAt().isBefore(visibleSince)) {
                return false;
            }
        }
        return runIdsWithArtifacts.contains(run.getId());
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

    private world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage toRunMessage(AgentRun run) {
        return world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage.newBuilder()
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
                                                  String observabilityJson,
                                                  int totalCreditsConsumed) {
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
                .setTotalCreditsConsumed(Math.max(0, totalCreditsConsumed))
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
        if ("PLANNING_STARTED".equals(lastEventType)
                || "PLANNING_COMPLETED".equals(lastEventType)
                || "TODO_LIST_CREATED".equals(lastEventType)) {
            return "PLANNING";
        }
        if ("TOOL_CALL_STARTED".equals(lastEventType)) {
            return "EXECUTING_TOOL";
        }
        if ("TODO_STARTED".equals(lastEventType)
                || "TODO_FINISHED".equals(lastEventType)
                || "TODO_FAILED".equals(lastEventType)
                || "SUB_AGENT_STARTED".equals(lastEventType)
                || "SUB_AGENT_FINISHED".equals(lastEventType)
                || "WORKFLOW_RESUMED".equals(lastEventType)) {
            return "EXECUTING";
        }
        if ("FINAL_ANSWER_GENERATING".equals(lastEventType) || "SUMMARIZING_STARTED".equals(lastEventType)) {
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
            if (v == null) {
                v = map.get("tool");
            }
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    private String nvl(String v) {
        return v == null ? "" : v;
    }

    private long nonNegativeLong(Long v) {
        return v == null ? 0L : Math.max(0L, v);
    }

    private int nonNegativeInt(Integer v) {
        return v == null ? 0 : Math.max(0, v);
    }

    private void ensureUserActive(String userId) {
        Long userIdLong;
        try {
            userIdLong = Long.parseLong(userId.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("user_id must be numeric");
        }
        User user = userDao.getUserById(userIdLong);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
        }
        String status = user.getStatus();
        if (status == null || status.isBlank()) {
            return;
        }
        if (!"ACTIVE".equalsIgnoreCase(status.trim())) {
            throw new IllegalStateException("user disabled");
        }
    }

    /**
     * 生成内容预览（截断并添加省略号）。
     *
     * @param content 原始内容
     * @param maxLen  最大长度
     * @return 预览内容
     */
    private String preview(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen) + "...";
    }
}
