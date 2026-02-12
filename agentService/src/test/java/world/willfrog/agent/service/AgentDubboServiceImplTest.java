package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.entity.AgentRunEvent;
import world.willfrog.agent.mapper.AgentRunEventMapper;
import world.willfrog.agent.mapper.AgentRunMapper;
import world.willfrog.agent.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentConfigResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentDubboServiceImplTest {

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunEventMapper eventMapper;
    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentRunExecutor executor;
    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentObservabilityService observabilityService;
    @Mock
    private AgentLlmResolver llmResolver;
    @Mock
    private AgentArtifactService artifactService;

    private AgentDubboServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentDubboServiceImpl(
                runMapper,
                eventMapper,
                eventService,
                executor,
                stateStore,
                observabilityService,
                llmResolver,
                artifactService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "checkpointVersion", "v2");
        ReflectionTestUtils.setField(service, "artifactRetentionNormalDays", 7);
        ReflectionTestUtils.setField(service, "artifactRetentionAdminDays", 30);
        ReflectionTestUtils.setField(service, "maxPollingIntervalSeconds", 3);

        lenient().when(eventService.shouldMarkExpired(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        lenient().when(stateStore.loadPlan(anyString())).thenReturn(java.util.Optional.empty());
        lenient().when(observabilityService.loadObservabilityJson(anyString(), anyString())).thenReturn("{}");
    }

    @Test
    void getConfig_shouldReturnParallelDisabled() {
        GetAgentConfigResponse response = service.getConfig(GetAgentConfigRequest.newBuilder().setUserId("u1").build());
        assertEquals(false, response.getFeatures().getParallelExecution());
        assertEquals(true, response.getFeatures().getPauseResume());
    }

    @Test
    void resumeRun_shouldRejectCheckpointV1() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("u1");
        run.setStatus(AgentRunStatus.FAILED);
        run.setExt("{\"checkpoint_version\":\"v1\",\"user_goal\":\"hello\"}");
        when(runMapper.findByIdAndUser("run-1", "u1")).thenReturn(run);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.resumeRun(ResumeAgentRunRequest.newBuilder().setUserId("u1").setId("run-1").build())
        );
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("SNAPSHOT_VERSION_INCOMPATIBLE"));
    }

    @Test
    void getStatus_shouldMapTodoStartedToExecutingPhase() {
        AgentRun run = new AgentRun();
        run.setId("run-2");
        run.setUserId("u1");
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setPlanJson("{\"items\":[{\"id\":\"todo_1\"}]}");
        run.setSnapshotJson("{}");
        when(runMapper.findByIdAndUser("run-2", "u1")).thenReturn(run);

        AgentRunEvent event = new AgentRunEvent();
        event.setEventType("TODO_STARTED");
        event.setPayloadJson("{\"todo_id\":\"todo_1\"}");
        when(eventMapper.findLatestByRunId("run-2")).thenReturn(event);
        when(stateStore.buildProgressJson("run-2", run.getPlanJson())).thenReturn("{}");

        var status = service.getStatus(GetAgentRunStatusRequest.newBuilder().setUserId("u1").setId("run-2").build());
        assertEquals("EXECUTING", status.getPhase());
    }
}
