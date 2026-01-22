package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentToolMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DubboAgentDubboServiceTriple;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.SubmitAgentFeedbackRequest;

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
    private final AgentLlmResolver llmResolver;
    private final ObjectMapper objectMapper;

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
                request.getEndpointName()
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
        runMapper.updateStatus(run.getId(), run.getUserId(), AgentRunStatus.CANCELED);
        eventService.append(run.getId(), run.getUserId(), "CANCELED", Map.of("run_id", run.getId()));
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
        if (run.getStatus() != AgentRunStatus.FAILED && run.getStatus() != AgentRunStatus.CANCELED) {
            return toRunMessage(run);
        }
        runMapper.resetForResume(run.getId(), run.getUserId(), eventService.nextTtlExpiresAt());
        eventService.append(run.getId(), run.getUserId(), "RESUMED", Map.of("run_id", run.getId()));
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
        return toStatusMessage(run, latestEvent);
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
     * 列出 run 的产物列表（当前为 MVP 空实现）。
     *
     * @param request 查询请求
     * @return 产物列表
     */
    @Override
    public ListAgentArtifactsResponse listArtifacts(ListAgentArtifactsRequest request) {
        requireRun(request.getId(), request.getUserId());
        return ListAgentArtifactsResponse.newBuilder().build();
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
        return run;
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED;
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

    private AgentRunStatusMessage toStatusMessage(AgentRun run, AgentRunEvent lastEvent) {
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
        if ("PLANNING_STARTED".equals(lastEventType)) {
            return "PLANNING";
        }
        if ("TOOL_CALL_STARTED".equals(lastEventType)) {
            return "EXECUTING_TOOL";
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
