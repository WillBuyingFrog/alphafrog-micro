package world.willfrog.alphafrogmicro.domestic.stock;


import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo
@MapperScan("world.willfrog.alphafrogmicro.common.dao")
public class DomesticStockServiceImplApplication {
    public static void main(String[] args) {
        SpringApplication.run(DomesticStockServiceImplApplication.class, args);
    }
}
