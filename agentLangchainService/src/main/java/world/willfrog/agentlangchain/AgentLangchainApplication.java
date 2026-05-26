package world.willfrog.agentlangchain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import world.willfrog.agent.tools.AgentToolsAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = "world.willfrog.agentlangchain",
        exclude = AgentToolsAutoConfiguration.class
)
public class AgentLangchainApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentLangchainApplication.class, args);
    }
}
