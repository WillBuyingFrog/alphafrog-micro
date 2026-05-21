package world.willfrog.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "agent.snapshot")
public class AgentSnapshotProperties {

    /** Default part size in bytes (512KB). */
    private int defaultPartSize = 512 * 1024;

    /** Minimum allowed part size in bytes (64KB). */
    private int minPartSize = 64 * 1024;

    /** Maximum allowed part size in bytes (2MB). */
    private int maxPartSize = 2 * 1024 * 1024;

    /** Redis cache TTL for snapshot parts in seconds. */
    private long cacheTtlSeconds = 3600;

    /** Whether to gzip-compress snapshot payload before splitting. */
    private boolean gzipEnabled = true;
}
