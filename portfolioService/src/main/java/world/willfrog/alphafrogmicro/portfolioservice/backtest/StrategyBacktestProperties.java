package world.willfrog.alphafrogmicro.portfolioservice.backtest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "portfolio.backtest")
public class StrategyBacktestProperties {
    private String topic = "strategy_backtest_run";
    private String consumerGroup = "portfolio-backtest-consumer";
    private boolean producerEnabled = true;
    private boolean consumerEnabled = true;
}
