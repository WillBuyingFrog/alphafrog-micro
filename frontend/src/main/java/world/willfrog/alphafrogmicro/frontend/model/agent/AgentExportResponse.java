package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentExportResponse(
        String exportId,
        String status,
        String url,
        String message
) {
}

