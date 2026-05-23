package world.willfrog.agentlangchain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "agent.langchain")
public class LangchainServiceProperties {

    private final Provider provider = new Provider();
    private final Traffic traffic = new Traffic();

    @Data
    public static class Provider {
        /**
         * When false, Dubbo provider bean is not registered (P0 default).
         */
        private boolean enabled = false;
        /** Dubbo provider group to avoid clashing with legacy agent-service. */
        private String dubboGroup = "langchain";
    }

    @Data
    public static class Traffic {
        /** Canary percent [0..100] for consistent-hash routing to langchain provider. */
        private int canaryPercent = 0;
    }
}
