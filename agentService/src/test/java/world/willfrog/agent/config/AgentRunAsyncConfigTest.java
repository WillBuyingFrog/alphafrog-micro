package world.willfrog.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AgentRunAsyncConfigTest {

    @Test
    void agentRunTaskExecutor_shouldUseAbortPolicyAndConfiguredPool() {
        AgentRunAsyncConfig config = new AgentRunAsyncConfig();
        Executor executor = config.agentRunTaskExecutor(2, 4, 50, "agent-run-test-");

        ThreadPoolTaskExecutor taskExecutor = assertInstanceOf(ThreadPoolTaskExecutor.class, executor);
        assertEquals(2, taskExecutor.getCorePoolSize());
        assertEquals(4, taskExecutor.getMaxPoolSize());
        assertEquals(50, taskExecutor.getQueueCapacity());
        assertEquals("agent-run-test-", taskExecutor.getThreadNamePrefix());
    }
}
