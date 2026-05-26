package world.willfrog.agentlangchain.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentRunStateStore;

import java.util.Optional;

/**
 * Cooperative stop checks for langchain pipeline/executors (cancel/pause must not be overwritten).
 */
@Component
@RequiredArgsConstructor
public class LangchainRunExecutionGuard {

    private final ObjectProvider<AgentRunStateStore> stateStoreProvider;
    private final AgentEventService eventService;
    private final AgentRunMapper runMapper;

    public boolean shouldStop(String runId, String userId) {
        return stopReason(runId, userId).isPresent();
    }

    /**
     * @return Redis or DB status that means execution must not continue or overwrite terminal control state
     */
    public Optional<String> stopReason(String runId, String userId) {
        if (isBlank(runId)) {
            return Optional.empty();
        }
        AgentRunStateStore stateStore = stateStoreProvider.getIfAvailable();
        if (stateStore != null) {
            Optional<String> redisStatus = stateStore.loadRunStatus(runId);
            if (redisStatus.isPresent() && isControlStopStatus(redisStatus.get())) {
                return redisStatus;
            }
        }
        if (!isBlank(userId) && !eventService.isRunnable(runId, userId)) {
            AgentRun run = runMapper.findByIdAndUser(runId, userId);
            if (run != null && run.getStatus() != null) {
                return Optional.of(run.getStatus().name());
            }
            return Optional.of("NOT_RUNNABLE");
        }
        return Optional.empty();
    }

    private static boolean isControlStopStatus(String status) {
        return AgentRunStatus.CANCELING.name().equals(status)
                || AgentRunStatus.CANCELED.name().equals(status)
                || AgentRunStatus.WAITING.name().equals(status);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
