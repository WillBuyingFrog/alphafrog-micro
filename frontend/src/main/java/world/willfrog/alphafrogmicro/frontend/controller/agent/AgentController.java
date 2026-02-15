package world.willfrog.alphafrogmicro.frontend.controller.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DeleteAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsResponse;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunResponse;
import world.willfrog.alphafrogmicro.agent.idl.PauseAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;
import world.willfrog.alphafrogmicro.agent.idl.SubmitAgentFeedbackRequest;
import world.willfrog.alphafrogmicro.agent.idl.UpdateAgentRunRequest;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunCreateRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentArtifactResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentExportRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentExportResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentFeedbackRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunEventResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunEventsPageResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunResumeRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunResultResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunListItemResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunListResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunStatusResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunUpdateRequest;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/runs")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private static final int ADMIN_USER_TYPE = 1127;

    @DubboReference
    private AgentDubboService agentDubboService;

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseWrapper<AgentRunResponse> create(Authentication authentication,
                                                    @RequestBody AgentRunCreateRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        User user = authService.getUserByUsername(authentication.getName());
        if (!authService.isUserActive(user)) {
            return ResponseWrapper.error(ResponseCode.FORBIDDEN, "账号已被禁用，无法创建新任务");
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseWrapper.paramError("message 不能为空");
        }
        try {
            Map<String, Object> contextMap = request.context() == null
                    ? new HashMap<>()
                    : new HashMap<>(request.context());
            boolean captureLlmRequests = Boolean.TRUE.equals(request.captureLlmRequests());
            boolean debugMode = Boolean.TRUE.equals(request.debugMode())
                    || toBoolean(contextMap.get("debugMode"))
                    || toBoolean(contextMap.get("debug_mode"));
            String provider = nvl(request.provider());
            String modelName = nvl(request.modelName());
            String endpointName = nvl(request.endpointName());
            Integer plannerCandidateCount = request.plannerCandidateCount();
            boolean admin = isAdmin(authentication);
            if (request.config() != null) {
                contextMap.put("config", request.config());
                ParsedModelSelection modelSelection = parseModelSelection(request.config().model());
                if (!modelSelection.modelName().isBlank()) {
                    modelName = modelSelection.modelName();
                }
                if (!modelSelection.endpointName().isBlank()) {
                    endpointName = modelSelection.endpointName();
                }
            }
            if (captureLlmRequests) {
                contextMap.put("captureLlmRequests", true);
            }
            if (debugMode) {
                // 兼容 agentService 侧 ext/context 两种读取方式。
                contextMap.put("debugMode", true);
            }
            if (!provider.isBlank()) {
                contextMap.put("provider", provider);
            }
            int plannerCandidateCountForRpc = 0;
            if (plannerCandidateCount != null && plannerCandidateCount > 0) {
                if (admin) {
                    plannerCandidateCountForRpc = plannerCandidateCount;
                } else {
                    log.info("Ignore plannerCandidateCount for non-admin user: userId={}, value={}", userId, plannerCandidateCount);
                }
            }
            String contextJson = contextMap.isEmpty() ? "" : objectMapper.writeValueAsString(contextMap);
            AgentRunMessage run = agentDubboService.createRun(
                    CreateAgentRunRequest.newBuilder()
                            .setUserId(userId)
                            .setMessage(request.message())
                            .setContextJson(contextJson)
                            .setIdempotencyKey(nvl(request.idempotencyKey()))
                            .setModelName(modelName)
                            .setEndpointName(endpointName)
                            .setCaptureLlmRequests(captureLlmRequests)
                            .setProvider(provider)
                            .setPlannerCandidateCount(plannerCandidateCountForRpc)
                            .setDebugMode(debugMode)
                            .build()
            );
            return ResponseWrapper.success(toRunResponse(run));
        } catch (RpcException e) {
            return handleRpcError(e, "创建 agent run");
        } catch (Exception e) {
            return handleError(e, "创建 agent run");
        }
    }

    @GetMapping
    public ResponseWrapper<AgentRunListResponse> list(Authentication authentication,
                                                      @RequestParam(value = "limit", required = false) Integer limit,
                                                      @RequestParam(value = "offset", required = false) Integer offset,
                                                      @RequestParam(value = "page", required = false) Integer page,
                                                      @RequestParam(value = "size", required = false) Integer size,
                                                      @RequestParam(value = "max", required = false) Integer max,
                                                      @RequestParam(value = "status", required = false, defaultValue = "") String status,
                                                      @RequestParam(value = "days", required = false, defaultValue = "0") int days) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            int resolvedLimit = resolveLimit(limit, size, max);
            int resolvedOffset = resolveOffset(offset, page, resolvedLimit);
            ListAgentRunsResponse resp = agentDubboService.listRuns(
                    ListAgentRunsRequest.newBuilder()
                            .setUserId(userId)
                            .setLimit(resolvedLimit)
                            .setOffset(resolvedOffset)
                            .setStatus(nvl(status))
                            .setDays(days)
                            .build()
            );
            List<AgentRunListItemResponse> items = new ArrayList<>();
            for (var item : resp.getItemsList()) {
                items.add(new AgentRunListItemResponse(
                        item.getId(),
                        emptyToNull(item.getMessage()),
                        item.getStatus(),
                        emptyToNull(item.getCreatedAt()),
                        emptyToNull(item.getCompletedAt()),
                        item.getHasArtifacts(),
                        item.getDurationMs() <= 0 ? null : item.getDurationMs(),
                        item.getTotalTokens() <= 0 ? null : item.getTotalTokens(),
                        item.getToolCalls() <= 0 ? null : item.getToolCalls()
                ));
            }
            return ResponseWrapper.success(new AgentRunListResponse(items, resp.getTotal(), resp.getHasMore()));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent run 列表");
        } catch (Exception e) {
            return handleError(e, "查询 agent run 列表");
        }
    }

    @GetMapping("/{runId}")
    public ResponseWrapper<AgentRunResponse> get(Authentication authentication,
                                                @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunMessage run = agentDubboService.getRun(GetAgentRunRequest.newBuilder().setUserId(userId).setId(runId).build());
            return ResponseWrapper.success(toRunResponse(run));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent run");
        } catch (Exception e) {
            return handleError(e, "查询 agent run");
        }
    }

    @PutMapping("/{runId}")
    public ResponseWrapper<AgentRunResponse> update(Authentication authentication,
                                                    @PathVariable("runId") String runId,
                                                    @RequestBody(required = false) AgentRunUpdateRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        if (request == null || request.title() == null || request.title().isBlank()) {
            return ResponseWrapper.paramError("title 不能为空");
        }
        String title = request.title().trim();
        if (title.length() > 120) {
            return ResponseWrapper.paramError("title 长度不能超过 120");
        }
        try {
            AgentRunMessage run = agentDubboService.updateRun(
                    UpdateAgentRunRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setTitle(title)
                            .build()
            );
            return ResponseWrapper.success(toRunResponse(run));
        } catch (RpcException e) {
            return handleRpcError(e, "更新 agent run");
        } catch (Exception e) {
            return handleError(e, "更新 agent run");
        }
    }

    /**
     * 删除指定的 Agent Run（运行中的任务禁止删除）。
     * <p>
     * 删除行为说明：
     * <ul>
     *   <li>只能删除属于自己的 run（通过当前登录用户鉴权）</li>
     *   <li>运行中的 run（状态为 RECEIVED/PLANNING/EXECUTING/SUMMARIZING）禁止删除，需先取消或暂停</li>
     *   <li>删除后会同步清理 Redis 中的状态缓存</li>
     * </ul>
     * <p>
     * 异常响应：
     * <ul>
     *   <li>401：用户未登录</li>
     *   <li>404：run 不存在</li>
     *   <li>409：run 正在运行中，需先取消/暂停</li>
     * </ul>
     *
     * @param authentication 当前用户认证信息
     * @param runId          要删除的 run ID（路径参数）
     * @return 删除成功返回 "ok"，失败返回对应错误码
     */
    @DeleteMapping("/{runId}")
    public ResponseWrapper<String> delete(Authentication authentication,
                                          @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            agentDubboService.deleteRun(DeleteAgentRunRequest.newBuilder().setUserId(userId).setId(runId).build());
            return ResponseWrapper.success("ok");
        } catch (RpcException e) {
            return handleRpcError(e, "删除 agent run");
        } catch (Exception e) {
            return handleError(e, "删除 agent run");
        }
    }

    @GetMapping("/{runId}/events")
    public ResponseWrapper<AgentRunEventsPageResponse> events(Authentication authentication,
                                                             @PathVariable("runId") String runId,
                                                             @RequestParam(value = "after_seq", required = false, defaultValue = "0") int afterSeq,
                                                             @RequestParam(value = "limit", required = false, defaultValue = "200") int limit) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            ListAgentRunEventsResponse resp = agentDubboService.listEvents(
                    ListAgentRunEventsRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setAfterSeq(Math.max(0, afterSeq))
                            .setLimit(Math.min(Math.max(1, limit), 500))
                            .build()
            );
            List<AgentRunEventResponse> items = new ArrayList<>();
            for (var e : resp.getItemsList()) {
                items.add(new AgentRunEventResponse(
                        e.getId(),
                        e.getRunId(),
                        e.getSeq(),
                        e.getEventType(),
                        e.getPayloadJson(),
                        e.getCreatedAt()
                ));
            }
            return ResponseWrapper.success(new AgentRunEventsPageResponse(items, resp.getNextAfterSeq(), resp.getHasMore()));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent events");
        } catch (Exception e) {
            return handleError(e, "查询 agent events");
        }
    }

    @PostMapping("/{runId}:cancel")
    public ResponseWrapper<AgentRunResponse> cancel(Authentication authentication,
                                                   @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunMessage run = agentDubboService.cancelRun(CancelAgentRunRequest.newBuilder().setUserId(userId).setId(runId).build());
            return ResponseWrapper.success(toRunResponse(run));
        } catch (RpcException e) {
            return handleRpcError(e, "取消 agent run");
        } catch (Exception e) {
            return handleError(e, "取消 agent run");
        }
    }

    @PostMapping("/{runId}:pause")
    public ResponseWrapper<AgentRunResponse> pause(Authentication authentication,
                                                  @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunMessage run = agentDubboService.pauseRun(PauseAgentRunRequest.newBuilder().setUserId(userId).setId(runId).build());
            return ResponseWrapper.success(toRunResponse(run));
        } catch (RpcException e) {
            return handleRpcError(e, "暂停 agent run");
        } catch (Exception e) {
            return handleError(e, "暂停 agent run");
        }
    }

    @PostMapping("/{runId}:resume")
    public ResponseWrapper<AgentRunResponse> resume(Authentication authentication,
                                                   @PathVariable("runId") String runId,
                                                   @RequestBody(required = false) AgentRunResumeRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            String planOverrideJson = request == null ? "" : nvl(request.planOverrideJson());
            AgentRunMessage run = agentDubboService.resumeRun(ResumeAgentRunRequest.newBuilder()
                    .setUserId(userId)
                    .setId(runId)
                    .setPlanOverrideJson(planOverrideJson)
                    .build());
            return ResponseWrapper.success(toRunResponse(run));
        } catch (RpcException e) {
            return handleRpcError(e, "续做 agent run");
        } catch (Exception e) {
            return handleError(e, "续做 agent run");
        }
    }

    @GetMapping("/{runId}/result")
    public ResponseEntity<ResponseWrapper<AgentRunResultResponse>> result(Authentication authentication,
                                                                          @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在"));
        }
        try {
            AgentRunResultMessage result = agentDubboService.getResult(
                    GetAgentRunResultRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .build()
            );
            AgentRunResultResponse body = new AgentRunResultResponse(
                    result.getId(),
                    result.getStatus(),
                    emptyToNull(result.getAnswer()),
                    emptyToNull(result.getPayloadJson()),
                    emptyToNull(result.getObservabilityJson()),
                    Math.max(0, result.getTotalCreditsConsumed())
            );
            if (!"COMPLETED".equalsIgnoreCase(result.getStatus())) {
                return ResponseEntity.status(202).body(ResponseWrapper.success(body, "任务未完成"));
            }
            return ResponseEntity.ok(ResponseWrapper.success(body));
        } catch (RpcException e) {
            return ResponseEntity.ok(handleRpcError(e, "获取 agent result"));
        } catch (Exception e) {
            return ResponseEntity.ok(handleError(e, "获取 agent result"));
        }
    }

    @GetMapping("/{runId}/status")
    public ResponseWrapper<AgentRunStatusResponse> status(Authentication authentication,
                                                          @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunStatusMessage status = agentDubboService.getStatus(
                    GetAgentRunStatusRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .build()
            );
            return ResponseWrapper.success(new AgentRunStatusResponse(
                    status.getId(),
                    emptyToNull(status.getStatus()),
                    emptyToNull(status.getPhase()),
                    emptyToNull(status.getCurrentTool()),
                    emptyToNull(status.getLastEventType()),
                    emptyToNull(status.getLastEventAt()),
                    emptyToNull(status.getLastEventPayloadJson()),
                    emptyToNull(status.getPlanJson()),
                    emptyToNull(status.getProgressJson()),
                    emptyToNull(status.getObservabilityJson()),
                    Math.max(0, status.getTotalCreditsConsumed())
            ));
        } catch (RpcException e) {
            return handleRpcError(e, "查询 agent status");
        } catch (Exception e) {
            return handleError(e, "查询 agent status");
        }
    }

    @GetMapping("/{runId}/artifacts")
    public ResponseWrapper<List<AgentArtifactResponse>> artifacts(Authentication authentication,
                                                                  @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            boolean isAdmin = isAdmin(authentication);
            ListAgentArtifactsResponse resp = agentDubboService.listArtifacts(
                    ListAgentArtifactsRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setIsAdmin(isAdmin)
                            .build()
            );
            List<AgentArtifactResponse> items = new ArrayList<>();
            for (var a : resp.getItemsList()) {
                items.add(new AgentArtifactResponse(
                        a.getArtifactId(),
                        a.getType(),
                        a.getName(),
                        a.getContentType(),
                        a.getUrl(),
                        emptyToNull(a.getMetaJson()),
                        emptyToNull(a.getCreatedAt()),
                        a.getExpiresAtMillis() <= 0 ? null : a.getExpiresAtMillis()
                ));
            }
            return ResponseWrapper.success(items);
        } catch (RpcException e) {
            return handleRpcError(e, "查询 artifacts");
        } catch (Exception e) {
            return handleError(e, "查询 artifacts");
        }
    }

    @GetMapping("/{runId}/artifacts/{artifactId}/download")
    public ResponseEntity<byte[]> downloadArtifact(Authentication authentication,
                                                   @PathVariable("runId") String runId,
                                                   @PathVariable("artifactId") String artifactId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        if (runId == null || runId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            DownloadAgentArtifactResponse resp = agentDubboService.downloadArtifact(
                    DownloadAgentArtifactRequest.newBuilder()
                            .setUserId(userId)
                            .setArtifactId(artifactId)
                            .setIsAdmin(isAdmin(authentication))
                            .build()
            );
            HttpHeaders headers = new HttpHeaders();
            MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
            try {
                if (resp.getContentType() != null && !resp.getContentType().isBlank()) {
                    mediaType = MediaType.parseMediaType(resp.getContentType());
                }
            } catch (Exception ignore) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
            headers.setContentType(mediaType);
            headers.setContentLength(resp.getContent().size());
            String filename = resp.getFilename() == null || resp.getFilename().isBlank() ? "artifact.bin" : resp.getFilename();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            return ResponseEntity.ok().headers(headers).body(resp.getContent().toByteArray());
        } catch (RpcException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("artifact not found") || msg.contains("run not found")) {
                return ResponseEntity.status(404).build();
            }
            if (msg.contains("artifact too large")) {
                return ResponseEntity.status(422).build();
            }
            log.error("下载 artifact 失败: runId={}, artifactId={}, err={}", runId, artifactId, e.getMessage());
            return ResponseEntity.status(502).build();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("artifact not found") || msg.contains("run not found")) {
                return ResponseEntity.status(404).build();
            }
            if (msg.contains("artifact too large")) {
                return ResponseEntity.status(422).build();
            }
            log.error("下载 artifact 失败: runId={}, artifactId={}", runId, artifactId, e);
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/{runId}/feedback")
    public ResponseWrapper<String> feedback(Authentication authentication,
                                           @PathVariable("runId") String runId,
                                           @RequestBody(required = false) AgentFeedbackRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            int rating = request == null || request.rating() == null ? 0 : request.rating();
            String tagsJson = request == null ? "" : objectMapper.writeValueAsString(request.tags());
            String payloadJson = request == null ? "" : objectMapper.writeValueAsString(request.payload());
            agentDubboService.submitFeedback(
                    SubmitAgentFeedbackRequest.newBuilder()
                            .setUserId(userId)
                            .setId(runId)
                            .setRating(rating)
                            .setComment(nvl(request == null ? null : request.comment()))
                            .setTagsJson(tagsJson == null ? "" : tagsJson)
                            .setPayloadJson(payloadJson == null ? "" : payloadJson)
                            .build()
            );
            return ResponseWrapper.success("ok");
        } catch (RpcException e) {
            return handleRpcError(e, "提交 feedback");
        } catch (Exception e) {
            return handleError(e, "提交 feedback");
        }
    }

    @PostMapping("/{runId}:export")
    public ResponseWrapper<AgentExportResponse> export(Authentication authentication,
                                                      @PathVariable("runId") String runId,
                                                      @RequestBody(required = false) AgentExportRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        String format = request == null || request.format() == null ? "" : request.format().trim();
        try {
            ExportAgentRunResponse resp = agentDubboService.exportRun(
                    ExportAgentRunRequest.newBuilder().setUserId(userId).setId(runId).setFormat(nvl(format)).build()
            );
            return ResponseWrapper.success(new AgentExportResponse(
                    resp.getExportId(),
                    resp.getStatus(),
                    emptyToNull(resp.getUrl()),
                    emptyToNull(resp.getMessage())
            ));
        } catch (RpcException e) {
            return handleRpcError(e, "导出 agent run");
        } catch (Exception e) {
            return handleError(e, "导出 agent run");
        }
    }

    private String resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String username = authentication.getName();
        User user = authService.getUserByUsername(username);
        if (user == null || user.getUserId() == null) {
            return null;
        }
        return String.valueOf(user.getUserId());
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        String username = authentication.getName();
        User user = authService.getUserByUsername(username);
        if (user == null) {
            return false;
        }
        Integer userType = user.getUserType();
        return userType != null && userType == ADMIN_USER_TYPE;
    }

    private int resolveLimit(Integer limit, Integer size, Integer max) {
        if (limit != null && limit > 0) {
            return limit;
        }
        if (max != null && max > 0) {
            return max;
        }
        if (size != null && size > 0) {
            return size;
        }
        return 20;
    }

    private int resolveOffset(Integer offset, Integer page, int limit) {
        if (offset != null && offset >= 0) {
            return offset;
        }
        if (page != null && page > 0 && limit > 0) {
            long candidate = (long) page * (long) limit;
            return candidate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) candidate;
        }
        return 0;
    }

    private AgentRunResponse toRunResponse(AgentRunMessage run) {
        return new AgentRunResponse(
                run.getId(),
                run.getStatus(),
                run.getCurrentStep(),
                run.getMaxSteps(),
                emptyToNull(run.getPlanJson()),
                emptyToNull(run.getSnapshotJson()),
                emptyToNull(run.getLastError()),
                emptyToNull(run.getTtlExpiresAt()),
                emptyToNull(run.getStartedAt()),
                emptyToNull(run.getUpdatedAt()),
                emptyToNull(run.getCompletedAt()),
                emptyToNull(run.getExt())
        );
    }

    private <T> ResponseWrapper<T> handleRpcError(RpcException e, String action) {
        log.error("{}失败: {}", action, e.getMessage());
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("run not found")) {
            return ResponseWrapper.error(ResponseCode.DATA_NOT_FOUND, "run 不存在");
        }
        if (msg.contains("run is running")) {
            return ResponseWrapper.error(ResponseCode.BUSINESS_ERROR, "run 运行中，请先停止后删除");
        }
        if (msg.contains("run expired")) {
            return ResponseWrapper.error(ResponseCode.BUSINESS_ERROR, "run 已过期，不能断点续跑，请新建 run");
        }
        if (msg.contains("snapshot_version_incompatible")) {
            return ResponseWrapper.error(
                    ResponseCode.BUSINESS_ERROR,
                    "断点版本不兼容，建议新建 run。详情: " + (e.getMessage() == null ? "" : e.getMessage())
            );
        }
        if (msg.contains("invalid status filter")) {
            return ResponseWrapper.error(ResponseCode.PARAM_ERROR, "status 参数非法");
        }
        return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, action + "失败");
    }

    private <T> ResponseWrapper<T> handleError(Exception e, String action) {
        log.error("{}失败", action, e);
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("run not found")) {
            return ResponseWrapper.error(ResponseCode.DATA_NOT_FOUND, "run 不存在");
        }
        if (msg.contains("run is running")) {
            return ResponseWrapper.error(ResponseCode.BUSINESS_ERROR, "run 运行中，请先停止后删除");
        }
        if (msg.contains("run expired")) {
            return ResponseWrapper.error(ResponseCode.BUSINESS_ERROR, "run 已过期，不能断点续跑，请新建 run");
        }
        if (msg.contains("snapshot_version_incompatible")) {
            return ResponseWrapper.error(
                    ResponseCode.BUSINESS_ERROR,
                    "断点版本不兼容，建议新建 run。详情: " + (e.getMessage() == null ? "" : e.getMessage())
            );
        }
        if (msg.contains("invalid status filter")) {
            return ResponseWrapper.error(ResponseCode.PARAM_ERROR, "status 参数非法");
        }
        return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, action + "失败");
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private ParsedModelSelection parseModelSelection(String compositeModelId) {
        String normalized = nvl(compositeModelId).trim();
        if (normalized.isBlank()) {
            return new ParsedModelSelection("", "");
        }
        int idx = normalized.lastIndexOf('@');
        if (idx <= 0 || idx >= normalized.length() - 1) {
            return new ParsedModelSelection(normalized, "");
        }
        String modelName = normalized.substring(0, idx).trim();
        String endpointName = normalized.substring(idx + 1).trim();
        return new ParsedModelSelection(modelName, endpointName);
    }

    private record ParsedModelSelection(String modelName, String endpointName) {
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
