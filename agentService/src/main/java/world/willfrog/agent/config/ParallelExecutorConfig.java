package world.willfrog.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ParallelExecutorConfig {

    @Bean
    public ExecutorService parallelExecutor(@Value("${agent.flow.parallel.max-concurrency:4}") int maxConcurrency) {
        return Executors.newFixedThreadPool(Math.max(1, maxConcurrency));
    }
}
