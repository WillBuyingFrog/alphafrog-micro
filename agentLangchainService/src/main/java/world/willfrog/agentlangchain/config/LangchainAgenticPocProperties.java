package world.willfrog.agentlangchain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flag for experimental agentic POC (compiled only with {@code -Pagentic-poc} implementation).
 */
@Data
@ConfigurationProperties(prefix = "agent.langchain.agentic-poc")
public class LangchainAgenticPocProperties {
    private boolean enabled = false;
}
