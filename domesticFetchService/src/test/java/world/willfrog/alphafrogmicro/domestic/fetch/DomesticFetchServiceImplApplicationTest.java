package world.willfrog.alphafrogmicro.domestic.fetch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = DomesticFetchServiceImplApplication.class,
        properties = {
                "AF_DB_MAIN_HOST=127.0.0.1",
                "AF_DB_MAIN_PORT=5432",
                "AF_DB_MAIN_DATABASE=alphafrog_test",
                "AF_DB_MAIN_USER=alphafrog",
                "AF_DB_MAIN_PASSWORD=alphafrog",
                "TUSHARE_TOKEN=test-token",
                "alphafrog.config.nacos.enabled=false",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.rabbitmq.listener.direct.auto-startup=false",
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=6379",
                "spring.data.redis.password=",
                "dubbo.registry.register=false",
                "dubbo.registry.address=N/A",
                "dubbo.consumer.check=false",
                "dubbo.provider.delay=-1",
                "dubbo.application.qos-enable=false"
        }
)
class DomesticFetchServiceImplApplicationTest {

    @Test
    void contextLoads() {
    }
}
