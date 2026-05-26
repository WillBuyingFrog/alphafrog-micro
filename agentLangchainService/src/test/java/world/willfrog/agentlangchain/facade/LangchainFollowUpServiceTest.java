package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LangchainFollowUpServiceTest {

    private final LangchainRunReadService readService = mock(LangchainRunReadService.class);
    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentEventService eventService = mock(AgentEventService.class);
    private final AgentMessageService messageService = mock(AgentMessageService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final LangchainLinearRunPipeline pipeline = mock(LangchainLinearRunPipeline.class);
    private final LangchainFollowUpService service = new LangchainFollowUpService(
            readService, runMapper, eventService, messageService, stateStore, pipeline);

    @Test
    void sendMessageRejectsNonCompletedRun() {
        AgentRun running = run(AgentRunStatus.EXECUTING);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);

        var response = service.sendMessage(SendAgentMessageRequest.newBuilder()
                .setUserId("u1")
                .setRunId("r1")
                .setContent("follow up")
                .build());

        assertEquals("rejected", response.getStatus());
        verify(pipeline, never()).launchAsync(any());
    }

    @Test
    void sendMessageAcceptsCompletedRunAndRelaunchesPipeline() {
        AgentRun completed = run(AgentRunStatus.COMPLETED);
        AgentRun received = run(AgentRunStatus.RECEIVED);
        AgentRunMessage userMessage = new AgentRunMessage();
        userMessage.setId(99L);
        userMessage.setSeq(2);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(completed);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(received);
        when(eventService.shouldMarkExpired(completed)).thenReturn(false);
        when(messageService.buildMetaJson(any(), any(), any(), any())).thenReturn("{}");
        when(messageService.createUserMessage(eq("r1"), eq("follow up"), any())).thenReturn(userMessage);
        when(eventService.nextTtlExpiresAt()).thenReturn(OffsetDateTime.now().plusHours(1));

        var response = service.sendMessage(SendAgentMessageRequest.newBuilder()
                .setUserId("u1")
                .setRunId("r1")
                .setContent("follow up")
                .build());

        assertEquals("accepted", response.getStatus());
        verify(runMapper).resetForResume(eq("r1"), eq("u1"), any());
        verify(pipeline).launchAsync(received);
    }

    private AgentRun run(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setStatus(status);
        run.setExt("{}");
        return run;
    }
}
