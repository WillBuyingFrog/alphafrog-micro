package world.willfrog.alphafrogmicro.domestic.fetch;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import world.willfrog.alphafrogmicro.common.config.nacos.NacosConfigBridge;


@SpringBootApplication(scanBasePackages = {
        "world.willfrog.alphafrogmicro.domestic.fetch"
})
@EnableDubbo
@EnableScheduling
@Import(NacosConfigBridge.class)
@MapperScan("world.willfrog.alphafrogmicro.common.dao")
public class DomesticFetchServiceImplApplication {
    public static void main(String[] args) {
        SpringApplication.run(DomesticFetchServiceImplApplication.class, args);
    }
}
