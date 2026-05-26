package world.willfrog.alphafrogmicro.frontend.controller.agent;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.ApplyAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.dto.ResponseWrapper;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCreditsApplyRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCreditsApplyResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCreditsResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;

@RestController
@Slf4j
public class AgentCreditController {

    @DubboReference(group = "langchain", check = false)
    private AgentDubboService agentDubboServiceLangchain;

    @DubboReference(group = "legacy", check = false)
    private AgentDubboService agentDubboServiceLegacy;

    @Autowired
    private HttpServletRequest request;

    private final AuthService authService;

    public AgentCreditController(AuthService authService) {
        this.authService = authService;
    }

    private AgentDubboService resolveService() {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/agent-legacy")) {
            return agentDubboServiceLegacy;
        }
        return agentDubboServiceLangchain;
    }

    @GetMapping("/api/agent/credits")
    public ResponseWrapper<AgentCreditsResponse> credits(Authentication authentication) {
        return creditsInternal(authentication);
    }

    /** @deprecated Use {@code GET /api/agent/credits}. */
    @Deprecated
    @GetMapping("/api/agent-legacy/credits")
    public ResponseWrapper<AgentCreditsResponse> creditsLegacy(Authentication authentication) {
        return creditsInternal(authentication);
    }

    @PostMapping("/api/agent/credits/apply")
    public ResponseWrapper<AgentCreditsApplyResponse> applyCredits(Authentication authentication,
                                                                   @RequestBody(required = false) AgentCreditsApplyRequest request) {
        return applyCreditsInternal(authentication, request);
    }

    /** @deprecated Use {@code POST /api/agent/credits/apply}. */
    @Deprecated
    @PostMapping("/api/agent-legacy/credits/apply")
    public ResponseWrapper<AgentCreditsApplyResponse> applyCreditsLegacy(Authentication authentication,
                                                                         @RequestBody(required = false) AgentCreditsApplyRequest request) {
        return applyCreditsInternal(authentication, request);
    }

    private ResponseWrapper<AgentCreditsResponse> creditsInternal(Authentication authentication) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        try {
            var resp = resolveService().getCredits(
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

    private ResponseWrapper<AgentCreditsApplyResponse> applyCreditsInternal(Authentication authentication,
                                                                            AgentCreditsApplyRequest request) {
        String userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseWrapper.error(ResponseCode.UNAUTHORIZED, "未登录或用户不存在");
        }
        int amount = request == null || request.amount() == null ? 0 : request.amount();
        if (amount <= 0) {
            return ResponseWrapper.paramError("amount 必须大于 0");
        }
        String reason = request == null || request.reason() == null ? "" : request.reason();
        String contact = request == null || request.contact() == null ? "" : request.contact();
        try {
            var resp = resolveService().applyCredits(
                    ApplyAgentCreditsRequest.newBuilder()
                            .setUserId(userId)
                            .setAmount(amount)
                            .setReason(reason)
                            .setContact(contact)
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
}
