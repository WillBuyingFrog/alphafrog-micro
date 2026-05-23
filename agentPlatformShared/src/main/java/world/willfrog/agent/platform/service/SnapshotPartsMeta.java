package world.willfrog.agent.platform.service;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SnapshotPartsMeta {

    String runId;
    int partSize;
    int totalParts;
    long uncompressedSize;
    long compressedSize;
    String compression;
    String checksum;
}
