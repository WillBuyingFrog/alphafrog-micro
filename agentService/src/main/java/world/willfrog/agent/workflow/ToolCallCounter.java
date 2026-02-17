package world.willfrog.agent.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.service.AgentRunStateStore;

@Component
@RequiredArgsConstructor
public class ToolCallCounter {

    private final AgentRunStateStore stateStore;

    public int increment(String runId) {
        return increment(runId, 1);
    }

    public int increment(String runId, int delta) {
        return stateStore.incrementToolCallCount(runId, Math.max(0, delta));
    }

    public int get(String runId) {
        return stateStore.getToolCallCount(runId);
    }

    public void reset(String runId) {
        stateStore.resetToolCallCount(runId);
    }

    public void set(String runId, int value) {
        stateStore.setToolCallCount(runId, Math.max(0, value));
    }

    public boolean isLimitReached(String runId, int maxCalls) {
        if (maxCalls <= 0) {
            return false;
        }
        return get(runId) >= maxCalls;
    }
}
