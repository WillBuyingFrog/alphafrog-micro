package world.willfrog.alphafrogmicro.frontend.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class RateLimitingConfig {

    private final SecurityProperties securityProperties;

    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.ofDefaults();
    }

    @Bean
    public RateLimiter authRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(securityProperties.getRateLimit().getLoginAttempts())
                .limitRefreshPeriod(Duration.ofMinutes(securityProperties.getRateLimit().getLoginWindowMinutes()))
                .timeoutDuration(Duration.ofMillis(100))
                .build();
        return RateLimiter.of("auth", config);
    }

    @Bean
    public RateLimiter taskRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(securityProperties.getRateLimit().getTaskCreationAttempts())
                .limitRefreshPeriod(Duration.ofMinutes(securityProperties.getRateLimit().getTaskCreationWindowMinutes()))
                .timeoutDuration(Duration.ofMillis(100))
                .build();
        return RateLimiter.of("task", config);
    }
}