package world.willfrog.alphafrogmicro.frontend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.frontend.config.TaskProducerRabbitConfig;

import java.util.Properties;

@Service
@RequiredArgsConstructor
@Slf4j
public class FetchQueueService {

    private static final String FETCH_TASK_QUEUE = "fetch.task.queue.domesticFetchService";

    private final RabbitTemplate rabbitTemplate;

    public FetchQueueStats getFetchQueueStats() {
        try {
            Properties props = rabbitTemplate.execute(channel -> {
                var declareOk = channel.queueDeclarePassive(FETCH_TASK_QUEUE);
                Properties p = new Properties();
                p.put("messageCount", declareOk.getMessageCount());
                p.put("consumerCount", declareOk.getConsumerCount());
                return p;
            });
            int messageCount = props != null ? (int) props.get("messageCount") : 0;
            int consumerCount = props != null ? (int) props.get("consumerCount") : 0;
            return new FetchQueueStats(FETCH_TASK_QUEUE, messageCount, consumerCount);
        } catch (Exception e) {
            log.error("Failed to fetch queue stats", e);
            throw new RuntimeException("Failed to fetch queue stats", e);
        }
    }

    public record FetchQueueStats(String queue, long pending, int consumers) {
    }
}
