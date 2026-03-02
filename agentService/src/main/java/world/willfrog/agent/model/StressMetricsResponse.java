package world.willfrog.agent.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StressMetricsResponse {
    private double cacheHitRate;
    private double latencyP50;
    private double latencyP95;
    private double latencyP99;
    private int activeRuns;
    private long timestamp;
}
