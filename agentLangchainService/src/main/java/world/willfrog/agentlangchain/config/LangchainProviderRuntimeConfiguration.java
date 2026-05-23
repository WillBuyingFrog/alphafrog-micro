package world.willfrog.agentlangchain.config;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import world.willfrog.agent.tools.AgentToolsAutoConfiguration;
import world.willfrog.alphafrogmicro.common.config.nacos.NacosConfigBridge;

@Configuration
@ConditionalOnProperty(prefix = "agent.langchain.provider", name = "enabled", havingValue = "true")
@EnableDubbo
@EnableScheduling
@EnableAsync
@MapperScan({
        "world.willfrog.agent.platform.mapper",
        "world.willfrog.alphafrogmicro.common.dao"
})
@ComponentScan(basePackages = "world.willfrog.agent.platform")
@Import({AgentToolsAutoConfiguration.class, NacosConfigBridge.class})
public class LangchainProviderRuntimeConfiguration {
}
