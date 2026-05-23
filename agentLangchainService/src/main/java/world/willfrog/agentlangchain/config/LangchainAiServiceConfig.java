package world.willfrog.agentlangchain.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LangchainServiceProperties.class)
public class LangchainAiServiceConfig {
}
