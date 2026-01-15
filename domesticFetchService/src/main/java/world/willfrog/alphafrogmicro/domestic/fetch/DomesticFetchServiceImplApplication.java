package world.willfrog.alphafrogmicro.domestic.fetch;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
@EnableDubbo
@MapperScan("world.willfrog.alphafrogmicro.common.dao")
public class DomesticFetchServiceImplApplication {
    public static void main(String[] args) {
        SpringApplication.run(DomesticFetchServiceImplApplication.class, args);
    }
}
