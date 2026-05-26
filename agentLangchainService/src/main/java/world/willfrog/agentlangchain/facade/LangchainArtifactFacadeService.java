package world.willfrog.agentlangchain.facade;

import com.google.protobuf.ByteString;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;

@Service
@RequiredArgsConstructor
public class LangchainArtifactFacadeService {

    private final LangchainRunReadService readService;
    private final AgentArtifactService artifactService;

    public ListAgentArtifactsResponse listArtifacts(ListAgentArtifactsRequest request) {
        AgentRun run = readService.requireReadableRun(request.getId(), request.getUserId());
        return ListAgentArtifactsResponse.newBuilder()
                .addAllItems(artifactService.listArtifacts(run, request.getIsAdmin()))
                .build();
    }

    public DownloadAgentArtifactResponse downloadArtifact(DownloadAgentArtifactRequest request) {
        String runId = artifactService.extractRunId(request.getArtifactId());
        AgentRun run = readService.requireReadableRun(runId, request.getUserId());
        AgentArtifactService.ArtifactContent artifact = artifactService.loadArtifact(
                run,
                request.getIsAdmin(),
                request.getArtifactId());
        return DownloadAgentArtifactResponse.newBuilder()
                .setArtifactId(artifact.artifactId())
                .setFilename(nvl(artifact.filename()))
                .setContentType(nvl(artifact.contentType()))
                .setContent(ByteString.copyFrom(artifact.content()))
                .build();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
