package world.willfrog.alphafrogmicro.portfolioservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;

@SpringBootApplication
@MapperScan("world.willfrog.alphafrogmicro.portfolioservice.mapper")
@EnableDubbo
public class PortfolioServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortfolioServiceApplication.class, args);
    }
}
