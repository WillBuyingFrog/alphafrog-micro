package world.willfrog.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AgentRunAsyncConfig {

    @Bean(name = "agentRunTaskExecutor")
    public Executor agentRunTaskExecutor(
            @Value("${agent.run.executor.core-pool-size:10}") int corePoolSize,
            @Value("${agent.run.executor.max-pool-size:10}") int maxPoolSize,
            @Value("${agent.run.executor.queue-capacity:100}") int queueCapacity,
            @Value("${agent.run.executor.thread-name-prefix:agent-run-}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
