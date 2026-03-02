package world.willfrog.alphafrogmicro.portfolioservice.backtest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "portfolio.backtest")
public class StrategyBacktestProperties {
    private String exchange = "backtest.exchange";
    private String queue = "backtest.queue.portfolio";
    private String routingKey = "backtest.run";
    private boolean producerEnabled = true;
    private boolean consumerEnabled = true;
}
