package world.willfrog.alphafrogmicro.frontend.service.agent;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;

/**
 * Langchain Dubbo client for {@code createRun} on {@code /api/agent/*} paths.
 */
@Service
@Slf4j
public class AgentDubboRoutingService {

    @DubboReference(group = "${agent.langchain.provider.dubbo-group:langchain}", check = false)
    private AgentDubboService langchainAgentDubboService;

    public AgentRunMessage createRun(CreateAgentRunRequest request) {
        log.info("Routing createRun to langchain provider: userId={}", request.getUserId());
        return langchainAgentDubboService.createRun(request);
    }
}
