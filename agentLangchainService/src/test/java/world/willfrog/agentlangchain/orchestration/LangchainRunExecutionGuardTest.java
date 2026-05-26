package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentRunStateStore;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainRunExecutionGuardTest {

    @Test
    void stopReasonReturnsRedisCancelingBeforeDbStillRunnable() {
        AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRunStateStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stateStore);
        when(stateStore.loadRunStatus("r1")).thenReturn(Optional.of(AgentRunStatus.CANCELING.name()));
        when(eventService.isRunnable("r1", "u1")).thenReturn(true);

        LangchainRunExecutionGuard guard = new LangchainRunExecutionGuard(provider, eventService, runMapper);

        assertEquals(Optional.of(AgentRunStatus.CANCELING.name()), guard.stopReason("r1", "u1"));
    }

    @Test
    void stopReasonReturnsDbWaitingWhenNotRunnable() {
        AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRunStateStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stateStore);
        when(stateStore.loadRunStatus("r1")).thenReturn(Optional.empty());
        when(eventService.isRunnable("r1", "u1")).thenReturn(false);
        AgentRun waiting = new AgentRun();
        waiting.setStatus(AgentRunStatus.WAITING);
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(waiting);

        LangchainRunExecutionGuard guard = new LangchainRunExecutionGuard(provider, eventService, runMapper);

        assertTrue(guard.shouldStop("r1", "u1"));
        assertEquals(Optional.of("WAITING"), guard.stopReason("r1", "u1"));
    }
}
