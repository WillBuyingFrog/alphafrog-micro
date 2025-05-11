package world.willfrog.alphafrogmicro.portfolioservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
// It's good practice to specify where to scan for JPA entities, especially if they are in a different module (like 'common')
@EntityScan(basePackages = {"world.willfrog.alphafrogmicro.common.pojo"})
public class PortfolioServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortfolioServiceApplication.class, args);
    }

} 