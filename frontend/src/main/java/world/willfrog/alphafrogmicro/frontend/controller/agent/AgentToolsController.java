package world.willfrog.alphafrogmicro.frontend.controller.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentModelsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsRequest;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCreditsApplyRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCreditsApplyResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCreditsResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentConfigResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentModelListResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentModelResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentToolResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentToolsController {

    private static final int ADMIN_USER_TYPE = 1127;

    @DubboReference
    private AgentDubboService agentDubboService;

    private final AuthService authService;

    @GetMapping("/tools")
    public ResponseWrapper<List<AgentToolResponse>> tools(Authentication authentication) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            var resp = agentDubboService.listTools(ListAgentToolsRequest.newBuilder().setUserId(userId).build());
            List<AgentToolResponse> tools = new ArrayList<>();
            for (var t : resp.getItemsList()) {
                tools.add(new AgentToolResponse(t.getName(), t.getDescription(), t.getParametersJson()));
            }
            return ResponseWrapper.success(tools);
        } catch (RpcException e) {
            log.error("查询 tools 失败: {}", e.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, "查询 tools 失败");
        } catch (Exception e) {
            log.error("查询 tools 失败", e);
            return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "查询 tools 失败");
        }
    }

    @GetMapping("/config")
    public ResponseWrapper<AgentConfigResponse> config(Authentication authentication) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            var resp = agentDubboService.getConfig(
                    GetAgentConfigRequest.newBuilder()
                            .setUserId(userId)
                            .build()
            );
            AgentConfigResponse body = new AgentConfigResponse(
                    new AgentConfigResponse.RetentionDays(
                            resp.getRetentionDays().getNormalDays(),
                            resp.getRetentionDays().getAdminDays()
                    ),
                    resp.getMaxPollingInterval(),
                    new AgentConfigResponse.Features(
                            resp.getFeatures().getParallelExecution(),
                            resp.getFeatures().getPauseResume()
                    )
            );
            return ResponseWrapper.success(body);
        } catch (RpcException e) {
            log.error("查询 agent config 失败: {}", e.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, "查询 agent config 失败");
        } catch (Exception e) {
            log.error("查询 agent config 失败", e);
            return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "查询 agent config 失败");
        }
    }

    @GetMapping("/models")
    public ResponseWrapper<AgentModelListResponse> models(Authentication authentication) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            var resp = agentDubboService.listModels(
                    ListAgentModelsRequest.newBuilder().setUserId(userId).build()
            );
            List<AgentModelResponse> models = new ArrayList<>();
            for (var model : resp.getModelsList()) {
                models.add(new AgentModelResponse(
                        model.getId(),
                        model.getDisplayName(),
                        model.getEndpoint(),
                        model.getCompositeId(),
                        model.getBaseRate(),
                        model.getFeaturesList()
                ));
            }
            return ResponseWrapper.success(new AgentModelListResponse(models));
        } catch (RpcException e) {
            log.error("查询模型列表失败: {}", e.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, "查询模型列表失败");
        } catch (Exception e) {
            log.error("查询模型列表失败", e);
            return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "查询模型列表失败");
        }
    }

    @GetMapping("/credits")
    public ResponseWrapper<AgentCreditsResponse> credits(Authentication authentication) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            var resp = agentDubboService.getCredits(
                    GetAgentCreditsRequest.newBuilder().setUserId(userId).build()
            );
            return ResponseWrapper.success(new AgentCreditsResponse(
                    resp.getTotalCredits(),
                    resp.getRemainingCredits(),
                    resp.getUsedCredits(),
                    resp.getResetCycle(),
                    resp.getNextResetAt()
            ));
        } catch (RpcException e) {
            log.error("查询 credit 失败: {}", e.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, "查询 credit 失败");
        } catch (Exception e) {
            log.error("查询 credit 失败", e);
            return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "查询 credit 失败");
        }
    }

    @PostMapping("/credits/apply")
    public ResponseWrapper<AgentCreditsApplyResponse> applyCredits(Authentication authentication,
                                                                   @RequestBody(required = false) AgentCreditsApplyRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        int amount = request == null || request.amount() == null ? 0 : request.amount();
        if (amount <= 0) {
            return ResponseWrapper.paramError("amount 必须大于 0");
        }
        try {
            var resp = agentDubboService.applyCredits(
                    ApplyAgentCreditsRequest.newBuilder()
                            .setUserId(userId)
                            .setAmount(amount)
                            .setReason(request.reason() == null ? "" : request.reason())
                            .setContact(request.contact() == null ? "" : request.contact())
                            .build()
            );
            return ResponseWrapper.success(new AgentCreditsApplyResponse(
                    resp.getApplicationId(),
                    resp.getTotalCredits(),
                    resp.getRemainingCredits(),
                    resp.getUsedCredits(),
                    resp.getStatus(),
                    resp.getAppliedAt()
            ));
        } catch (RpcException e) {
            log.error("申请额度失败: {}", e.getMessage());
            return ResponseWrapper.error(ResponseCode.EXTERNAL_SERVICE_ERROR, "申请额度失败");
        } catch (Exception e) {
            log.error("申请额度失败", e);
            return ResponseWrapper.error(ResponseCode.SYSTEM_ERROR, "申请额度失败");
        }
    }

    @GetMapping("/artifacts/{artifactId}/download")
    public ResponseEntity<byte[]> download(Authentication authentication,
                                           @PathVariable("artifactId") String artifactId) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).build();
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
            log.error("下载 artifact 失败: {}", e.getMessage());
            return ResponseEntity.status(502).build();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("artifact not found") || msg.contains("run not found")) {
                return ResponseEntity.status(404).build();
            }
            if (msg.contains("artifact too large")) {
                return ResponseEntity.status(422).build();
            }
            log.error("下载 artifact 失败", e);
            return ResponseEntity.status(500).build();
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
}
