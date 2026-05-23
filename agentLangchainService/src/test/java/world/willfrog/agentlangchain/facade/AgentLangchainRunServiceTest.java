package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentLangchainRunServiceTest {

    @Mock
    private ObjectProvider<AgentEventService> eventServiceProvider;
    @Mock
    private ObjectProvider<LangchainLinearRunPipeline> pipelineProvider;
    @Mock
    private AgentEventService eventService;
    @Mock
    private LangchainLinearRunPipeline pipeline;

    private AgentLangchainRunService runService;

    @BeforeEach
    void setUp() {
        runService = new AgentLangchainRunService(eventServiceProvider, pipelineProvider);
    }

    @Test
    void createRunLaunchesLinearPipeline() {
        when(eventServiceProvider.getIfAvailable()).thenReturn(eventService);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);

        AgentRun run = new AgentRun();
        run.setId("run123");
        run.setUserId("u1");
        run.setStatus(AgentRunStatus.RECEIVED);
        when(eventService.createRun(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any())).thenReturn(run);

        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setMessage("analyze stocks")
                .build();

        var message = runService.createRun(request);
        assertEquals("run123", message.getId());
        verify(pipeline).launchAsync(run);
    }

    @Test
    void createRunRequiresUserId() {
        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setMessage("hello")
                .build();
        assertThrows(IllegalArgumentException.class, () -> runService.createRun(request));
    }
}
