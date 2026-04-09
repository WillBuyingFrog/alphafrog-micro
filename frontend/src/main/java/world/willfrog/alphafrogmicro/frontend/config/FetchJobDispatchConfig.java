package world.willfrog.alphafrogmicro.frontend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Admin Fetch Job 异步派发线程池配置
 */
@Configuration
@EnableAsync
public class FetchJobDispatchConfig {

    @Bean(name = "fetchJobDispatchExecutor")
    public Executor fetchJobDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("fetch-dispatch-");
        executor.initialize();
        return executor;
    }
}
