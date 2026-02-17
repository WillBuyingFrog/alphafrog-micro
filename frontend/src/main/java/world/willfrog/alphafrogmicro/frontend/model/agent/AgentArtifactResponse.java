package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentArtifactResponse(
        String artifactId,
        String type,
        String name,
        String contentType,
        String url,
        String metaJson,
        String createdAt,
        Long expiresAtMillis
) {
}
