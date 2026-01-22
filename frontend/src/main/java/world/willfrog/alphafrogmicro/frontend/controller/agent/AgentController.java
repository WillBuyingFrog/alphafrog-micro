package world.willfrog.alphafrogmicro.frontend.controller.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ExportAgentRunResponse;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;
import world.willfrog.alphafrogmicro.agent.idl.SubmitAgentFeedbackRequest;
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
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunResultResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/agent/runs")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

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
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseWrapper.paramError("message 不能为空");
        }
        try {
            String contextJson = request.context() == null ? "" : objectMapper.writeValueAsString(request.context());
            AgentRunMessage run = agentDubboService.createRun(
                    CreateAgentRunRequest.newBuilder()
                            .setUserId(userId)
                            .setMessage(request.message())
                            .setContextJson(contextJson)
                            .setIdempotencyKey(nvl(request.idempotencyKey()))
                            .build()
            );
            return ResponseWrapper.success(toRunResponse(run));
        } catch (RpcException e) {
            return handleRpcError(e, "创建 agent run");
        } catch (Exception e) {
            return handleError(e, "创建 agent run");
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

    @PostMapping("/{runId}:resume")
    public ResponseWrapper<AgentRunResponse> resume(Authentication authentication,
                                                   @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            AgentRunMessage run = agentDubboService.resumeRun(ResumeAgentRunRequest.newBuilder().setUserId(userId).setId(runId).build());
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
                    emptyToNull(result.getPayloadJson())
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

    @GetMapping("/{runId}/artifacts")
    public ResponseWrapper<List<AgentArtifactResponse>> artifacts(Authentication authentication,
                                                                  @PathVariable("runId") String runId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            ListAgentArtifactsResponse resp = agentDubboService.listArtifacts(
                    ListAgentArtifactsRequest.newBuilder().setUserId(userId).setId(runId).build()
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
                        emptyToNull(a.getCreatedAt())
                ));
            }
            return ResponseWrapper.success(items);
        } catch (RpcException e) {
            return handleRpcError(e, "查询 artifacts");
        } catch (Exception e) {
            return handleError(e, "查询 artifacts");
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
        return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, action + "失败");
    }

    private <T> ResponseWrapper<T> handleError(Exception e, String action) {
        log.error("{}失败", action, e);
        return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, action + "失败");
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
