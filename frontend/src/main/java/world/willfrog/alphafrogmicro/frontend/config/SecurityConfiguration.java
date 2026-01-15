package world.willfrog.alphafrogmicro.frontend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({RateLimitingConfig.class, SecurityConfig.class})
public class SecurityConfiguration {
}