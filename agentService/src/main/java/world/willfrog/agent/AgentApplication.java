package world.willfrog.agent;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import world.willfrog.alphafrogmicro.common.config.nacos.NacosConfigBridge;

@SpringBootApplication
@EnableDubbo
@EnableAsync
@EnableScheduling
@Import(NacosConfigBridge.class)
@MapperScan({"world.willfrog.agent.mapper", "world.willfrog.alphafrogmicro.common.dao"})
public class AgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
