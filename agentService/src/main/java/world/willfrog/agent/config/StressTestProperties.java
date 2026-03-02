package world.willfrog.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stress.test")
@Data
public class StressTestProperties {
    private boolean enabled = false;
    private boolean toolLatencyEnabled = false;
    private long toolLatencyMs = 0;
    private double toolFailureRate = 0.0;
}
