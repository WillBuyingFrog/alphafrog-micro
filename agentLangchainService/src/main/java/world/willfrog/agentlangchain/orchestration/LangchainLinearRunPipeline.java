package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agent.platform.entity.AgentRun;

/**
 * P1 linear execution hook implemented by Codex B1 ({@code sequenceBuilder} path).
 */
public interface LangchainLinearRunPipeline {

    void launchAsync(AgentRun run);
}
