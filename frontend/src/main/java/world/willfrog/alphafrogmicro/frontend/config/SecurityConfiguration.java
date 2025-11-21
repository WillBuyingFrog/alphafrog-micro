package world.willfrog.alphafrogmicro.frontend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
@Import({RateLimitingConfig.class, SecurityConfig.class})
public class SecurityConfiguration {
}