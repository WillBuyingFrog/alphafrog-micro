package world.willfrog.alphafrogmicro.frontend.service.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentDubboRoutingServiceTest {

    private AgentDubboService langchainAgentDubboService;
    private AgentDubboRoutingService routingService;

    @BeforeEach
    void setUp() {
        langchainAgentDubboService = mock(AgentDubboService.class);
        routingService = new AgentDubboRoutingService();
        ReflectionTestUtils.setField(routingService, "langchainAgentDubboService", langchainAgentDubboService);
    }

    @Test
    void createRun_shouldAlwaysUseLangchainProvider() {
        AgentRunMessage expected = AgentRunMessage.newBuilder().setId("run-1").build();
        when(langchainAgentDubboService.createRun(any(CreateAgentRunRequest.class))).thenReturn(expected);

        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setUserId("user-1")
                .setMessage("hello")
                .build();

        AgentRunMessage actual = routingService.createRun(request);

        assertSame(expected, actual);
        verify(langchainAgentDubboService).createRun(request);
    }
}
