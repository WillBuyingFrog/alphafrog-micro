package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentModelCatalogService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.service.SnapshotPartService;
import world.willfrog.agentlangchain.routing.LangchainSingleWriterGuard;
import world.willfrog.agentlangchain.tools.LangchainToolCatalogService;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LangchainRunReadServiceTest {

    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
    private final AgentEventService eventService = mock(AgentEventService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final AgentObservabilityService observabilityService = mock(AgentObservabilityService.class);
    private final AgentCreditService creditService = mock(AgentCreditService.class);
    private final AgentModelCatalogService modelCatalogService = mock(AgentModelCatalogService.class);
    private final AgentMessageService messageService = mock(AgentMessageService.class);
    private final SnapshotPartService snapshotPartService = mock(SnapshotPartService.class);
    private final LangchainToolCatalogService toolCatalogService = mock(LangchainToolCatalogService.class);
    private final LangchainSingleWriterGuard guard = new LangchainSingleWriterGuard();
    private final AgentArtifactService artifactService = mock(AgentArtifactService.class);

    private final LangchainRunReadService service = new LangchainRunReadService(
            runMapper,
            eventMapper,
            eventService,
            stateStore,
            observabilityService,
            creditService,
            modelCatalogService,
            messageService,
            snapshotPartService,
            toolCatalogService,
            guard,
            artifactService,
            new ObjectMapper());

    @Test
    void getRunAllowsExistingRunWithoutOwnerMarker() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);

        var message = service.getRun(GetAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("r1", message.getId());
    }

    @Test
    void listEventsPaginatesLikeLegacy() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        AgentRunEvent event1 = event(3, "PLAN_READY");
        AgentRunEvent event2 = event(4, "TODO_STARTED");
        when(eventMapper.listByRunIdAfterSeq("r1", 2, 2)).thenReturn(List.of(event1, event2));

        var response = service.listEvents(ListAgentRunEventsRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .setAfterSeq(2)
                .setLimit(1)
                .build());

        assertEquals(1, response.getItemsCount());
        assertEquals("PLAN_READY", response.getItems(0).getEventType());
        assertEquals(3, response.getNextAfterSeq());
        assertTrue(response.getHasMore());
    }

    @Test
    void getResultExtractsAnswerAndCredits() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        run.setSnapshotJson("{\"answer_markdown\":\"done\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(observabilityService.loadObservabilityJson("r1", run.getSnapshotJson())).thenReturn("{\"summary\":{}}");
        when(eventMapper.listByRunId("r1")).thenReturn(List.of());
        when(creditService.calculateRunTotalCredits(eq(run), anyList(), eq("{\"summary\":{}}"))).thenReturn(7);

        var result = service.getResult(GetAgentRunResultRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("done", result.getAnswerMarkdown());
        assertEquals(7, result.getTotalCreditsConsumed());
    }

    @Test
    void getStatusReturnsLightweightObservabilitySummaryOnly() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        run.setPlanJson("{\"items\":[]}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(stateStore.loadPlan("r1")).thenReturn(java.util.Optional.empty());
        when(stateStore.buildProgressJson("r1", run.getPlanJson())).thenReturn("{\"progress\":true}");
        when(observabilityService.loadObservabilitySummaryJson("r1", run.getSnapshotJson())).thenReturn("{\"summary\":true}");
        when(observabilityService.isFullObservabilityAvailable("r1", run.getSnapshotJson())).thenReturn(true);
        when(eventMapper.listByRunId("r1")).thenReturn(List.of());
        when(eventMapper.findMaxSeq("r1")).thenReturn(4);

        var status = service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("", status.getObservabilityJson());
        assertEquals("{\"summary\":true}", status.getObservabilitySummaryJson());
        assertTrue(status.getObservabilityFullAvailable());
        verify(observabilityService, never()).loadObservabilityJson(anyString(), any());
    }

    private AgentRun run(String ext) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setExt(ext);
        run.setStartedAt(OffsetDateTime.now());
        return run;
    }

    private AgentRunEvent event(int seq, String type) {
        AgentRunEvent event = new AgentRunEvent();
        event.setId((long) seq);
        event.setRunId("r1");
        event.setSeq(seq);
        event.setEventType(type);
        event.setPayloadJson("{}");
        event.setCreatedAt(OffsetDateTime.now());
        return event;
    }
}
