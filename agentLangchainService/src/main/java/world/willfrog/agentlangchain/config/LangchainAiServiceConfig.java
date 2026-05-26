package world.willfrog.agentlangchain.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({LangchainServiceProperties.class, LangchainAgenticPocProperties.class})
@org.springframework.context.annotation.Import({LangchainToolsConfiguration.class, LangchainRunAsyncConfig.class})
public class LangchainAiServiceConfig {
}
