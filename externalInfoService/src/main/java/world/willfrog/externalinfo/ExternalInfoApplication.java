package world.willfrog.externalinfo;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import world.willfrog.alphafrogmicro.common.config.nacos.NacosConfigBridge;

@SpringBootApplication
@EnableDubbo
@EnableScheduling
@Import(NacosConfigBridge.class)
public class ExternalInfoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExternalInfoApplication.class, args);
    }
}
