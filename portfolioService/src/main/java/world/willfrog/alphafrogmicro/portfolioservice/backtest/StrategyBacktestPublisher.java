package world.willfrog.alphafrogmicro.portfolioservice.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StrategyBacktestPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final StrategyBacktestProperties properties;

    public StrategyBacktestPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                     ObjectMapper objectMapper,
                                     StrategyBacktestProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(StrategyBacktestRunEvent event) throws Exception {
        if (!properties.isProducerEnabled()) {
            log.info("Backtest producer disabled, skip publish runId={}", event.runId());
            return;
        }
        String payload = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(properties.getTopic(), payload);
    }
}
