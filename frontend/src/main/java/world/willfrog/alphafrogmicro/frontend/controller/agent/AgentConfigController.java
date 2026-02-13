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
import world.willfrog.alphafrogmicro.agent.idl.ListAgentModelsRequest;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentModelListResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentModelResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentConfigController {

    @DubboReference
    private AgentDubboService agentDubboService;

    private final AuthService authService;

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

    @GetMapping("/config/search-sources")
    public ResponseWrapper<List<SearchSourceResponse>> searchSources(Authentication authentication) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        List<SearchSourceResponse> items = List.of(
                new SearchSourceResponse("ah_market", "AH市场", "A股及港股相关资讯"),
                new SearchSourceResponse("us_market", "美国市场", "美股及美股中概相关资讯"),
                new SearchSourceResponse("apac_market", "亚太市场", "日韩东南亚等市场资讯"),
                new SearchSourceResponse("eu_market", "欧洲市场", "欧洲主要市场资讯"),
                new SearchSourceResponse("emerging_market", "其他新兴市场", "拉美、中东、非洲等市场")
        );
        return ResponseWrapper.success(items);
    }

    @GetMapping("/config/retrieval-sources")
    public ResponseWrapper<List<RetrievalSourceResponse>> retrievalSources(Authentication authentication) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        List<RetrievalSourceResponse> items = List.of(
                new RetrievalSourceResponse("news", "新闻资讯", 2),
                new RetrievalSourceResponse("announcement", "公告信息", 5),
                new RetrievalSourceResponse("report", "券商研报", 10)
        );
        return ResponseWrapper.success(items);
    }

    private String resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        User user = authService.getUserByUsername(authentication.getName());
        if (user == null || user.getUserId() == null) {
            return null;
        }
        return String.valueOf(user.getUserId());
    }

    private record SearchSourceResponse(String id, String name, String desc) {
    }

    private record RetrievalSourceResponse(String id, String name, Integer cost) {
    }
}
