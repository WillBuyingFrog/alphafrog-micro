package world.willfrog.alphafrogmicro.portfolioservice.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "portfolio.backtest.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class StrategyBacktestConsumer {

    private final ObjectMapper objectMapper;
    private final StrategyBacktestExecutor executor;

    public StrategyBacktestConsumer(ObjectMapper objectMapper, StrategyBacktestExecutor executor) {
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @RabbitListener(queues = "${portfolio.backtest.queue}")
    public void onMessage(String message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        boolean success = false;
        try {
            // 消费到回测事件后交给执行器完成具体计算
            StrategyBacktestRunEvent event = objectMapper.readValue(message, StrategyBacktestRunEvent.class);
            executor.execute(event);
            success = true;
        } catch (Exception e) {
            log.error("Failed to consume backtest message: {}", message, e);
        } finally {
            try {
                if (success) {
                    channel.basicAck(tag, false);
                } else {
                    channel.basicNack(tag, false, false);
                }
            } catch (Exception ackException) {
                log.error("Failed to ack/nack backtest message", ackException);
            }
        }
    }
}
