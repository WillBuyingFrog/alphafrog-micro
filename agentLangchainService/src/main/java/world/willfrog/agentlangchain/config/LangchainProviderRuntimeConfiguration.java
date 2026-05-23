package world.willfrog.agentlangchain.config;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import world.willfrog.agent.tools.AgentToolsAutoConfiguration;

@Configuration
@ConditionalOnProperty(prefix = "agent.langchain.provider", name = "enabled", havingValue = "true")
@EnableDubbo
@MapperScan("world.willfrog.agent.platform.mapper")
@ComponentScan(basePackages = {
        "world.willfrog.agent.platform",
        "world.willfrog.alphafrogmicro.common.dao"
})
@Import(AgentToolsAutoConfiguration.class)
public class LangchainProviderRuntimeConfiguration {
}
