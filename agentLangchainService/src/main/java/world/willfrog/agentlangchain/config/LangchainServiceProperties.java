package world.willfrog.agentlangchain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "agent.langchain")
public class LangchainServiceProperties {

    private final Provider provider = new Provider();

    @Data
    public static class Provider {
        /**
         * When false, Dubbo provider bean is not registered (P0 default).
         */
        private boolean enabled = false;
    }
}
