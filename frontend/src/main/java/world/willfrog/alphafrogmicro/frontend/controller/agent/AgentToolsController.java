package world.willfrog.alphafrogmicro.frontend.controller.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentToolsRequest;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentToolResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentToolsController {

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
}

