package world.willfrog.alphafrogmicro.frontend;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo
@MapperScan("world.willfrog.alphafrogmicro.common.dao")
//@ComponentScan("world.willfrog.alphafrogmicro.frontend.controller.domestic")
//@ComponentScan("world.willfrog.alphafrogmicro.frontend.controller")
//@ComponentScan("world.willfrog.alphafrogmicro.frontend.filter")
public class FrontendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FrontendApplication.class, args);
    }

}
