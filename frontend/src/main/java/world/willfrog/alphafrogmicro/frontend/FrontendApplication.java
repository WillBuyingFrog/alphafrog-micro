package world.willfrog.alphafrogmicro.frontend;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo
@MapperScan("world.willfrog.alphafrogmicro.common.dao")
public class FrontendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FrontendApplication.class, args);
    }

}
