package world.willfrog.alphafrogmicro.portfolioservice.backtest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StrategyBacktestProperties.class)
public class StrategyBacktestConfig {
}
