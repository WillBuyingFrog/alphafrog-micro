package world.willfrog.alphafrogmicro.portfolioservice.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StrategyBacktestPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final StrategyBacktestProperties properties;

    public StrategyBacktestPublisher(RabbitTemplate rabbitTemplate,
                                     ObjectMapper objectMapper,
                                     StrategyBacktestProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(StrategyBacktestRunEvent event) throws Exception {
        // 允许在配置中关闭回测事件的发送
        if (!properties.isProducerEnabled()) {
            log.info("Backtest producer disabled, skip publish runId={}", event.runId());
            return;
        }
        String payload = objectMapper.writeValueAsString(event);
        // 仅发送 runId/strategyId/userId 供消费端拉取明细
        rabbitTemplate.convertAndSend(properties.getExchange(), properties.getRoutingKey(), payload);
    }
}
