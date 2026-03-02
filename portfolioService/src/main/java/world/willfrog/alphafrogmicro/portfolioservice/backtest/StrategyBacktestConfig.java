package world.willfrog.alphafrogmicro.portfolioservice.backtest;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StrategyBacktestProperties.class)
public class StrategyBacktestConfig {

    @Bean
    public DirectExchange backtestExchange(StrategyBacktestProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public Queue backtestQueue(StrategyBacktestProperties properties) {
        return QueueBuilder.durable(properties.getQueue()).build();
    }

    @Bean
    public Binding backtestBinding(Queue backtestQueue, DirectExchange backtestExchange,
                                   StrategyBacktestProperties properties) {
        return BindingBuilder.bind(backtestQueue).to(backtestExchange).with(properties.getRoutingKey());
    }
}
