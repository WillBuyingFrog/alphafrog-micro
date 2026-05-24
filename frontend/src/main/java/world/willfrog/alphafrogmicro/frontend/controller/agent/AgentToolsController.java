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
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsRequest;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentConfigResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentToolResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AgentToolsController {

    private static final int ADMIN_USER_TYPE = 1127;

    @DubboReference(check = false)
    private AgentDubboService agentDubboService;

    private final AuthService authService;

    @GetMapping("/api/agent/tools")
    public ResponseWrapper<List<AgentToolResponse>> tools(Authentication authentication) {
        return toolsInternal(authentication);
    }

    /** @deprecated Use {@code GET /api/agent/tools}. */
    @Deprecated
    @GetMapping("/api/agent-legacy/tools")
    public ResponseWrapper<List<AgentToolResponse>> toolsLegacy(Authentication authentication) {
        return toolsInternal(authentication);
    }

    @GetMapping("/api/agent/config")
    public ResponseWrapper<AgentConfigResponse> config(Authentication authentication) {
        return configInternal(authentication);
    }

    /** @deprecated Use {@code GET /api/agent/config}. */
    @Deprecated
    @GetMapping("/api/agent-legacy/config")
    public ResponseWrapper<AgentConfigResponse> configLegacy(Authentication authentication) {
        return configInternal(authentication);
    }

    @GetMapping("/api/agent/artifacts/{artifactId}/download")
    public ResponseEntity<byte[]> download(Authentication authentication,
                                           @PathVariable("artifactId") String artifactId) {
        return downloadInternal(authentication, artifactId);
    }

    /** @deprecated Use {@code GET /api/agent/artifacts/{artifactId}/download}. */
    @Deprecated
    @GetMapping("/api/agent-legacy/artifacts/{artifactId}/download")
    public ResponseEntity<byte[]> downloadLegacy(Authentication authentication,
                                                 @PathVariable("artifactId") String artifactId) {
        return downloadInternal(authentication, artifactId);
    }

    private ResponseWrapper<List<AgentToolResponse>> toolsInternal(Authentication authentication) {
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

    private ResponseWrapper<AgentConfigResponse> configInternal(Authentication authentication) {
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

    private ResponseEntity<byte[]> downloadInternal(Authentication authentication, String artifactId) {
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
