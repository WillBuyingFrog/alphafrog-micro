package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentModelCatalogService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.service.SnapshotPartService;
import world.willfrog.agent.platform.service.SnapshotPartsMeta;
import world.willfrog.agentlangchain.routing.LangchainSingleWriterGuard;
import world.willfrog.agentlangchain.tools.LangchainToolCatalogService;
import world.willfrog.alphafrogmicro.agent.idl.AgentEmpty;
import world.willfrog.alphafrogmicro.agent.idl.AgentFeatureConfigMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentModelMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRetentionConfigMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunListItemMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessageItem;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentSnapshotPartMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentSnapshotPartsMetaMessage;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsResponse;
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
import world.willfrog.alphafrogmicro.agent.idl.SubmitAgentFeedbackRequest;
import world.willfrog.alphafrogmicro.agent.idl.UpdateAgentRunRequest;

import com.google.protobuf.ByteString;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LangchainRunReadService {

    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final AgentCreditService creditService;
    private final AgentModelCatalogService modelCatalogService;
    private final AgentMessageService messageService;
    private final SnapshotPartService snapshotPartService;
    private final LangchainToolCatalogService toolCatalogService;
    private final LangchainSingleWriterGuard singleWriterGuard;
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

    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage getRun(GetAgentRunRequest request) {
        return AgentLangchainRunMessageMapper.toRunMessage(requireReadableRun(request.getId(), request.getUserId()));
    }

    public world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage updateRun(UpdateAgentRunRequest request) {
        String title = normalizeTitle(request.getTitle());
        if (title == null) {
            throw new IllegalArgumentException("title is required");
        }
        AgentRun run = requireWritableRun(request.getId(), request.getUserId());
        Map<String, Object> ext = readExtMap(run.getExt());
        ext.put("title", title);
        int updated = runMapper.updateExt(run.getId(), run.getUserId(), writeJson(ext));
        if (updated <= 0) {
            throw new IllegalStateException("run not found");
        }
        return AgentLangchainRunMessageMapper.toRunMessage(requireReadableRun(run.getId(), run.getUserId()));
    }

    public ListAgentRunsResponse listRuns(ListAgentRunsRequest request) {
        String userId = requireUserId(request.getUserId());
        int limit = request.getLimit() <= 0 ? 20 : Math.min(request.getLimit(), 100);
        int offset = Math.max(0, request.getOffset());
        AgentRunStatus statusFilter = parseStatusFilter(request.getStatus());
        int days = request.getDays() > 0 ? request.getDays() : listDefaultDays;
        OffsetDateTime fromTime = days > 0 ? OffsetDateTime.now().minusDays(days) : null;

        List<AgentRun> runs = runMapper.listByUser(userId, statusFilter, fromTime, limit, offset);
        int total = runMapper.countByUser(userId, statusFilter, fromTime);
        ListAgentRunsResponse.Builder builder = ListAgentRunsResponse.newBuilder()
                .setTotal(total)
                .setHasMore(offset + runs.size() < total);
        for (AgentRun run : runs) {
            AgentRunStatus effectiveStatus = eventService.shouldMarkExpired(run)
                    ? AgentRunStatus.EXPIRED : run.getStatus();
            builder.addItems(AgentRunListItemMessage.newBuilder()
                    .setId(nvl(run.getId()))
                    .setMessage(nvl(eventService.extractRunDisplayTitle(run.getExt())))
                    .setStatus(effectiveStatus == null ? "" : effectiveStatus.name())
                    .setCreatedAt(run.getStartedAt() == null ? "" : run.getStartedAt().toString())
                    .setCompletedAt(run.getCompletedAt() == null ? "" : run.getCompletedAt().toString())
                    .setHasArtifacts(!artifactService.listArtifacts(run, false).isEmpty())
                    .setDurationMs(nonNegativeLong(run.getDurationMs()))
                    .setTotalTokens(nonNegativeInt(run.getTotalTokens()))
                    .setToolCalls(nonNegativeInt(run.getToolCalls()))
                    .build());
        }
        return builder.build();
    }

    public ListAgentRunEventsResponse listEvents(ListAgentRunEventsRequest request) {
        requireReadableRun(request.getId(), request.getUserId());
        int afterSeq = Math.max(0, request.getAfterSeq());
        int limit = request.getLimit() <= 0 ? 200 : Math.min(request.getLimit(), 500);
        List<AgentRunEvent> events = eventMapper.listByRunIdAfterSeq(request.getId(), afterSeq, limit + 1);
        boolean hasMore = events.size() > limit;
        if (hasMore) {
            events = events.subList(0, limit);
        }
        int nextAfterSeq = afterSeq;
        ListAgentRunEventsResponse.Builder builder = ListAgentRunEventsResponse.newBuilder();
        for (AgentRunEvent event : events) {
            builder.addItems(toEventMessage(event));
            if (event.getSeq() != null) {
                nextAfterSeq = Math.max(nextAfterSeq, event.getSeq());
            }
        }
        return builder.setNextAfterSeq(nextAfterSeq).setHasMore(hasMore).build();
    }

    public AgentRunResultMessage getResult(GetAgentRunResultRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        String snapshotJson = nvl(run.getSnapshotJson());
        String observabilityJson = nvl(observabilityService.loadObservabilityJson(run.getId(), snapshotJson));
        Map<String, Object> snapshot = readExtMap(snapshotJson);
        String answerMarkdown = firstNonBlank(stringValue(snapshot.get("answer_markdown")), stringValue(snapshot.get("answer")));
        String structuredAnswerJson = "";
        if (snapshot.get("structured_answer") != null) {
            structuredAnswerJson = writeJson(snapshot.get("structured_answer"));
        }
        int totalCredits = creditService.calculateRunTotalCredits(run, eventMapper.listByRunId(run.getId()), observabilityJson);
        return AgentRunResultMessage.newBuilder()
                .setId(nvl(run.getId()))
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setAnswer(nvl(answerMarkdown))
                .setPayloadJson(snapshotJson)
                .setObservabilityJson(observabilityJson)
                .setTotalCreditsConsumed(totalCredits)
                .setAnswerMarkdown(nvl(answerMarkdown))
                .setStructuredAnswerJson(nvl(structuredAnswerJson))
                .build();
    }

    public AgentRunStatusMessage getStatus(GetAgentRunStatusRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        AgentRunEvent latestEvent = eventMapper.findLatestByRunId(run.getId());
        String planJson = nvl(run.getPlanJson());
        var cachedPlan = stateStore.loadPlan(run.getId());
        if (cachedPlan.isPresent()) {
            planJson = cachedPlan.get();
        }
        String progressJson = planJson.isBlank() ? "" : stateStore.buildProgressJson(run.getId(), planJson);
        String observabilitySummaryJson = observabilityService.loadObservabilitySummaryJson(run.getId(), run.getSnapshotJson());
        boolean observabilityFullAvailable = observabilityService.isFullObservabilityAvailable(run.getId(), run.getSnapshotJson());
        int totalCredits = creditService.calculateRunTotalCredits(run, eventMapper.listByRunId(run.getId()), observabilitySummaryJson);
        Integer maxSeq = eventMapper.findMaxSeq(run.getId());
        return toStatusMessage(
                run,
                latestEvent,
                planJson,
                progressJson,
                "",
                observabilitySummaryJson,
                observabilityFullAvailable,
                totalCredits,
                maxSeq == null ? 0 : maxSeq,
                toEpochMillis(run.getStartedAt()),
                toEpochMillis(run.getCompletedAt()),
                computeElapsedMs(run, System.currentTimeMillis()));
    }

    public ListAgentToolsResponse listTools(ListAgentToolsRequest request) {
        requireUserId(request.getUserId());
        return ListAgentToolsResponse.newBuilder()
                .addAllItems(toolCatalogService.listToolMessages())
                .build();
    }

    public GetAgentConfigResponse getConfig(GetAgentConfigRequest request) {
        requireUserId(request.getUserId());
        return GetAgentConfigResponse.newBuilder()
                .setRetentionDays(AgentRetentionConfigMessage.newBuilder()
                        .setNormalDays(Math.max(0, artifactRetentionNormalDays))
                        .setAdminDays(Math.max(0, artifactRetentionAdminDays))
                        .build())
                .setMaxPollingInterval(Math.max(1, maxPollingIntervalSeconds))
                .setFeatures(AgentFeatureConfigMessage.newBuilder()
                        .setParallelExecution(true)
                        .setPauseResume(true)
                        .build())
                .build();
    }

    public ListAgentModelsResponse listModels(ListAgentModelsRequest request) {
        requireUserId(request.getUserId());
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

    public ApplyAgentCreditsResponse applyCredits(ApplyAgentCreditsRequest request) {
        AgentCreditService.ApplyCreditSummary summary = creditService.applyCredits(
                request.getUserId(),
                request.getAmount(),
                request.getReason(),
                request.getContact());
        return ApplyAgentCreditsResponse.newBuilder()
                .setApplicationId(nvl(summary.applicationId()))
                .setTotalCredits(summary.totalCredits())
                .setRemainingCredits(summary.remainingCredits())
                .setUsedCredits(summary.usedCredits())
                .setStatus(nvl(summary.status()))
                .setAppliedAt(nvl(summary.appliedAt()))
                .build();
    }

    public AgentEmpty submitFeedback(SubmitAgentFeedbackRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        eventService.append(run.getId(), run.getUserId(), "FEEDBACK_RECEIVED", Map.of(
                "rating", request.getRating(),
                "comment", request.getComment(),
                "tags_json", request.getTagsJson(),
                "payload_json", request.getPayloadJson()));
        return AgentEmpty.newBuilder().build();
    }

    public ExportAgentRunResponse exportRun(ExportAgentRunRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        String exportId = java.util.UUID.randomUUID().toString().replace("-", "");
        eventService.append(run.getId(), run.getUserId(), "EXPORT_REQUESTED", Map.of(
                "export_id", exportId,
                "format", request.getFormat()));
        return ExportAgentRunResponse.newBuilder()
                .setExportId(exportId)
                .setStatus("not_implemented")
                .setMessage("export not implemented in langchain service yet")
                .build();
    }

    public ListAgentMessagesResponse listMessages(ListAgentMessagesRequest request) {
        String userId = requireUserId(request.getUserId());
        String runId = requireId(request.getRunId(), "run_id");
        requireReadableRun(runId, userId);
        int limit = request.getLimit() <= 0 ? 50 : Math.min(request.getLimit(), 200);
        int offset = Math.max(0, request.getOffset());
        boolean includeInitial = request.getIncludeInitial();
        int total = includeInitial
                ? messageService.countMessages(runId)
                : messageService.countMessagesExcludingInitial(runId);
        List<AgentRunMessage> messages = includeInitial
                ? messageService.listMessagesWithPagination(runId, limit, offset)
                : messageService.listMessagesWithPaginationExcludingInitial(runId, limit, offset);
        ListAgentMessagesResponse.Builder builder = ListAgentMessagesResponse.newBuilder()
                .setTotal(total)
                .setHasMore(offset + messages.size() < total);
        for (AgentRunMessage msg : messages) {
            builder.addItems(AgentRunMessageItem.newBuilder()
                    .setId(msg.getId() == null ? 0L : msg.getId())
                    .setSeq(msg.getSeq() == null ? 0 : msg.getSeq())
                    .setRole(nvl(msg.getRole()))
                    .setContent(nvl(msg.getContent()))
                    .setMsgType(nvl(msg.getMsgType()))
                    .setMetaJson(nvl(msg.getMetaJson()))
                    .setCreatedAt(msg.getCreatedAt() == null ? "" : msg.getCreatedAt().toString())
                    .build());
        }
        return builder.build();
    }

    public AgentSnapshotPartsMetaMessage getSnapshotPartsMeta(GetAgentSnapshotPartsRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        SnapshotPartsMeta meta = snapshotPartService.getOrBuildMeta(
                run.getId(),
                run.getSnapshotJson(),
                request.getMaxPartSize());
        return AgentSnapshotPartsMetaMessage.newBuilder()
                .setRunId(nvl(meta.getRunId()))
                .setPartSize(meta.getPartSize())
                .setTotalParts(meta.getTotalParts())
                .setUncompressedSize(meta.getUncompressedSize())
                .setCompressedSize(meta.getCompressedSize())
                .setCompression(nvl(meta.getCompression()))
                .setChecksum(nvl(meta.getChecksum()))
                .build();
    }

    public AgentSnapshotPartMessage getSnapshotPart(GetAgentSnapshotPartRequest request) {
        AgentRun run = requireReadableRun(request.getId(), request.getUserId());
        SnapshotPartsMeta meta = snapshotPartService.getOrBuildMeta(
                run.getId(),
                run.getSnapshotJson(),
                request.getMaxPartSize());
        byte[] content = snapshotPartService.getPartBytes(
                run.getId(),
                run.getSnapshotJson(),
                request.getPartIndex(),
                request.getMaxPartSize());
        return AgentSnapshotPartMessage.newBuilder()
                .setRunId(nvl(meta.getRunId()))
                .setPartIndex(request.getPartIndex())
                .setPartSize(meta.getPartSize())
                .setTotalParts(meta.getTotalParts())
                .setContent(ByteString.copyFrom(content))
                .setCompression(nvl(meta.getCompression()))
                .build();
    }

    AgentRun requireReadableRun(String id, String userId) {
        return singleWriterGuard.requireReadable(requireRun(id, userId));
    }

    AgentRun requireWritableRun(String id, String userId) {
        return singleWriterGuard.requireWritable(requireRun(id, userId));
    }

    private AgentRun requireRun(String id, String userId) {
        String safeId = requireId(id, "id");
        String safeUserId = requireUserId(userId);
        AgentRun run = runMapper.findByIdAndUser(safeId, safeUserId);
        if (run == null) {
            throw new IllegalArgumentException("run not found");
        }
        return markExpiredIfNeeded(run);
    }

    private AgentRun markExpiredIfNeeded(AgentRun run) {
        if (run == null || !eventService.shouldMarkExpired(run)) {
            return run;
        }
        runMapper.updateStatus(run.getId(), run.getUserId(), AgentRunStatus.EXPIRED);
        eventService.append(run.getId(), run.getUserId(), "RUN_EXPIRED", Map.of(
                "run_id", run.getId(),
                "expired_at", OffsetDateTime.now().toString()));
        stateStore.markRunStatus(run.getId(), AgentRunStatus.EXPIRED.name());
        AgentRun refreshed = runMapper.findByIdAndUser(run.getId(), run.getUserId());
        return refreshed == null ? run : refreshed;
    }

    private String requireUserId(String userId) {
        return requireId(userId, "user_id");
    }

    private String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
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

    private AgentRunEventMessage toEventMessage(AgentRunEvent event) {
        return AgentRunEventMessage.newBuilder()
                .setId(event.getId() == null ? 0L : event.getId())
                .setRunId(nvl(event.getRunId()))
                .setSeq(event.getSeq() == null ? 0 : event.getSeq())
                .setEventType(nvl(event.getEventType()))
                .setPayloadJson(nvl(event.getPayloadJson()))
                .setCreatedAt(event.getCreatedAt() == null ? "" : event.getCreatedAt().toString())
                .build();
    }

    private AgentRunStatusMessage toStatusMessage(AgentRun run,
                                                  AgentRunEvent lastEvent,
                                                  String planJson,
                                                  String progressJson,
                                                  String observabilityJson,
                                                  String observabilitySummaryJson,
                                                  boolean observabilityFullAvailable,
                                                  int totalCreditsConsumed,
                                                  int eventCount,
                                                  long startedAtMs,
                                                  long completedAtMs,
                                                  long elapsedMs) {
        String lastEventType = lastEvent == null ? "" : nvl(lastEvent.getEventType());
        return AgentRunStatusMessage.newBuilder()
                .setId(nvl(run.getId()))
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setPhase(resolvePhase(run.getStatus(), lastEventType))
                .setCurrentTool(resolveCurrentTool(lastEventType, lastEvent == null ? null : lastEvent.getPayloadJson()))
                .setLastEventType(lastEventType)
                .setLastEventAt(lastEvent == null || lastEvent.getCreatedAt() == null ? "" : lastEvent.getCreatedAt().toString())
                .setLastEventPayloadJson(lastEvent == null ? "" : nvl(lastEvent.getPayloadJson()))
                .setPlanJson(nvl(planJson))
                .setProgressJson(nvl(progressJson))
                .setObservabilityJson(nvl(observabilityJson))
                .setObservabilitySummaryJson(nvl(observabilitySummaryJson))
                .setObservabilityFullAvailable(observabilityFullAvailable)
                .setTotalCreditsConsumed(Math.max(0, totalCreditsConsumed))
                .setEventCount(eventCount)
                .setStartedAtMs(startedAtMs)
                .setCompletedAtMs(completedAtMs)
                .setElapsedMs(elapsedMs)
                .build();
    }

    private String resolvePhase(AgentRunStatus status, String lastEventType) {
        if (status == null) {
            return "";
        }
        if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELED || status == AgentRunStatus.EXPIRED) {
            return status.name();
        }
        if (status == AgentRunStatus.WAITING) {
            return "PAUSED";
        }
        if ("PLAN_READY".equals(lastEventType)
                || "PLANNING_STARTED".equals(lastEventType)
                || "TODO_LIST_CREATED".equals(lastEventType)) {
            return "PLANNING";
        }
        if ("FINAL_ANSWER_GENERATING".equals(lastEventType) || "SUMMARIZING_STARTED".equals(lastEventType)) {
            return "SUMMARIZING";
        }
        if ("TOOL_CALL_STARTED".equals(lastEventType)) {
            return "EXECUTING_TOOL";
        }
        if ("EXECUTION_STARTED".equals(lastEventType) || "TODO_STARTED".equals(lastEventType)
                || "TODO_FINISHED".equals(lastEventType) || "WORKFLOW_RESUMED".equals(lastEventType)) {
            return "EXECUTING";
        }
        return status.name();
    }

    private String resolveCurrentTool(String lastEventType, String payloadJson) {
        if (!"TOOL_CALL_STARTED".equals(lastEventType) || payloadJson == null || payloadJson.isBlank()) {
            return "";
        }
        Map<String, Object> payload = readExtMap(payloadJson);
        return firstNonBlank(stringValue(payload.get("tool_name")), stringValue(payload.get("tool")));
    }

    private Map<String, Object> readExtMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
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

    private long toEpochMillis(OffsetDateTime time) {
        return time == null ? 0L : time.toInstant().toEpochMilli();
    }

    private long computeElapsedMs(AgentRun run, long nowMs) {
        if (run.getStartedAt() == null) {
            return 0L;
        }
        long startMs = run.getStartedAt().toInstant().toEpochMilli();
        if (run.getCompletedAt() != null) {
            return Math.max(0L, run.getCompletedAt().toInstant().toEpochMilli() - startMs);
        }
        return Math.max(0L, nowMs - startMs);
    }

    private int nonNegativeInt(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private long nonNegativeLong(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
