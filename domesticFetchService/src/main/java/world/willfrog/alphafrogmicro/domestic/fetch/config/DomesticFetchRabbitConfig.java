package world.willfrog.alphafrogmicro.domestic.fetch.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomesticFetchRabbitConfig {

    @Value("${af.fetch.concurrency:3}")
    private int fetchConcurrency;

    public static final String FETCH_EXCHANGE = "fetch.exchange";
    public static final String FETCH_TASK_QUEUE = "fetch.task.queue.domesticFetchService";
    public static final String FETCH_TASK_ROUTING_KEY = "fetch.task";

    public static final String FETCH_RESULT_EXCHANGE = "fetch.result.exchange";
    public static final String FETCH_RESULT_ROUTING_KEY = "fetch.result";

    @Bean
    public DirectExchange fetchExchange() {
        return new DirectExchange(FETCH_EXCHANGE, true, false);
    }

    @Bean
    public Queue fetchTaskQueue() {
        return QueueBuilder.durable(FETCH_TASK_QUEUE).build();
    }

    @Bean
    public Binding fetchTaskBinding(Queue fetchTaskQueue, DirectExchange fetchExchange) {
        return BindingBuilder.bind(fetchTaskQueue).to(fetchExchange).with(FETCH_TASK_ROUTING_KEY);
    }

    @Bean
    public DirectExchange fetchResultExchange() {
        return new DirectExchange(FETCH_RESULT_EXCHANGE, true, false);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        return new RabbitTemplate(connectionFactory);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(fetchConcurrency);
        return factory;
    }
}
