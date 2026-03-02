package world.willfrog.alphafrogmicro.frontend.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskProducerRabbitConfig {

    public static final String FETCH_EXCHANGE = "fetch.exchange";
    public static final String FETCH_TASK_ROUTING_KEY = "fetch.task";

    public static final String FETCH_RESULT_EXCHANGE = "fetch.result.exchange";
    public static final String FETCH_RESULT_QUEUE = "fetch.result.queue.frontend";
    public static final String FETCH_RESULT_ROUTING_KEY = "fetch.result";

    @Bean
    public DirectExchange fetchExchange() {
        return new DirectExchange(FETCH_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange fetchResultExchange() {
        return new DirectExchange(FETCH_RESULT_EXCHANGE, true, false);
    }

    @Bean
    public Queue fetchResultQueue() {
        return QueueBuilder.durable(FETCH_RESULT_QUEUE).build();
    }

    @Bean
    public Binding fetchResultBinding(Queue fetchResultQueue, DirectExchange fetchResultExchange) {
        return BindingBuilder.bind(fetchResultQueue).to(fetchResultExchange).with(FETCH_RESULT_ROUTING_KEY);
    }
}
