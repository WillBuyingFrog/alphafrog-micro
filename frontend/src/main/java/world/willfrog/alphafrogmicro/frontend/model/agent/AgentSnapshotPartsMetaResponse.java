package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentSnapshotPartsMetaResponse(
        String runId,
        int partSize,
        int totalParts,
        long uncompressedSize,
        long compressedSize,
        String compression,
        String checksum
) {
}
