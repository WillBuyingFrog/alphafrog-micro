package world.willfrog.alphafrogmicro.frontend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "security")
@Data
public class SecurityProperties {
    
    private RateLimit rateLimit = new RateLimit();
    
    @Data
    public static class RateLimit {
        private int loginAttempts = 5;
        private int loginWindowMinutes = 10;
        private int taskCreationAttempts = 10;
        private int taskCreationWindowMinutes = 60;
    }
}