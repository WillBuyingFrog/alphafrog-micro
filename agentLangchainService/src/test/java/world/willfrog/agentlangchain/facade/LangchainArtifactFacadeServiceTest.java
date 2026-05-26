package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LangchainArtifactFacadeServiceTest {

    private final LangchainRunReadService readService = mock(LangchainRunReadService.class);
    private final AgentArtifactService artifactService = mock(AgentArtifactService.class);
    private final LangchainArtifactFacadeService service =
            new LangchainArtifactFacadeService(readService, artifactService);

    @Test
    void listArtifactsDelegatesToSharedService() {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        when(readService.requireReadableRun("r1", "u1")).thenReturn(run);
        when(artifactService.listArtifacts(run, false)).thenReturn(List.of(
                AgentArtifactMessage.newBuilder().setArtifactId("a1").build()));

        var response = service.listArtifacts(ListAgentArtifactsRequest.newBuilder()
                .setId("r1")
                .setUserId("u1")
                .build());

        assertEquals(1, response.getItemsCount());
        assertEquals("a1", response.getItems(0).getArtifactId());
    }

    @Test
    void downloadArtifactValidatesOwnershipThroughReadService() {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        when(artifactService.extractRunId("artifact-1")).thenReturn("r1");
        when(readService.requireReadableRun("r1", "u1")).thenReturn(run);
        when(artifactService.loadArtifact(run, false, "artifact-1")).thenReturn(
                new AgentArtifactService.ArtifactContent("artifact-1", "out.csv", "text/csv", "x".getBytes()));

        var response = service.downloadArtifact(DownloadAgentArtifactRequest.newBuilder()
                .setUserId("u1")
                .setArtifactId("artifact-1")
                .build());

        assertEquals("artifact-1", response.getArtifactId());
        assertEquals("out.csv", response.getFilename());
    }
}
