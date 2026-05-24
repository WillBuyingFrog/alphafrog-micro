package world.willfrog.agentlangchain.routing;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;

/**
 * Basic run access guard. With legacy agentService deprecated, langchain owns
 * the default AgentDubboService path and can read/write existing runs.
 */
@Component
public class LangchainSingleWriterGuard {

    public AgentRun requireReadable(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run not found");
        }
        return run;
    }

    public AgentRun requireWritable(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run not found");
        }
        return run;
    }

    public AgentRun markLangchainOwner(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run not found");
        }
        return run;
    }
}
