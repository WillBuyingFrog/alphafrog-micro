package world.willfrog.agent.model;

import lombok.Data;

@Data
public class StressConfigRequest {
    private Boolean toolLatencyEnabled;
    private Long toolLatencyMs;
    private Double toolFailureRate;
}
