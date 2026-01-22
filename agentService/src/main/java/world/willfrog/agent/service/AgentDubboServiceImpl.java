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
import world.willfrog.alphafrogmicro.agent.idl.AgentToolMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DubboAgentDubboServiceTriple;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
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
    private final ObjectMapper objectMapper;

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
        var run = eventService.createRun(userId, message, request.getContextJson(), request.getIdempotencyKey());
        executor.executeAsync(run.getId());
        return toRunMessage(run);
    }

    @Override
    public AgentRunMessage getRun(GetAgentRunRequest request) {
        AgentRun run = requireRun(request.getId(), request.getUserId());
        return toRunMessage(run);
    }

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
                        .setName("searchFund")
                        .setDescription("Search for a fund by keyword")
                        .setParametersJson("{\"keyword\":\"string\"}")
                        .build())
                .build();
    }

    @Override
    public ListAgentArtifactsResponse listArtifacts(ListAgentArtifactsRequest request) {
        requireRun(request.getId(), request.getUserId());
        return ListAgentArtifactsResponse.newBuilder().build();
    }

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

    private String nvl(String v) {
        return v == null ? "" : v;
    }
}
