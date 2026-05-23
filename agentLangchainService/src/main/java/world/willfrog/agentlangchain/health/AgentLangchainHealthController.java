package world.willfrog.agentlangchain.health;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.agent.platform.PlatformModuleMarker;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agentlangchain.config.LangchainServiceProperties;
import world.willfrog.agentlangchain.orchestration.AgentLangchainOrchestrator;
import world.willfrog.agentlangchain.routing.LangchainTrafficRouter;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/agent-langchain")
@RequiredArgsConstructor
public class AgentLangchainHealthController {

    private final LangchainServiceProperties properties;
    private final AgentLangchainOrchestrator orchestrator;
    private final LangchainTrafficRouter trafficRouter;

    @Value("${agent.langchain.service.version:P0-skeleton}")
    private String serviceVersion;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "agentLangchainService");
        body.put("version", serviceVersion);
        body.put("providerEnabled", properties.getProvider().isEnabled());
        body.put("dubboGroup", properties.getProvider().getDubboGroup());
        body.put("canaryPercent", properties.getTraffic().getCanaryPercent());
        body.put("orchestrationStatus", orchestrator.unimplementedStatus());
        body.put("platformSharedLoaded", isClassLoaded(PlatformModuleMarker.class));
        body.put("toolsSharedLoaded", isClassLoaded(ToolRouter.class));
        body.put("status", "UP");
        return body;
    }

    private static boolean isClassLoaded(Class<?> type) {
        return type != null;
    }
}
