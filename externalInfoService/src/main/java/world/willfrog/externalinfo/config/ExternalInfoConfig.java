package world.willfrog.externalinfo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        SearchLlmProperties.class
})
public class ExternalInfoConfig {
}
