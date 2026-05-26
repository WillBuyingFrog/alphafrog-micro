package world.willfrog.alphafrogmicro.frontend;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootTest(
        classes = FrontendApplicationTest.TestFrontendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "AF_DB_MAIN_HOST=127.0.0.1",
                "AF_DB_MAIN_PORT=5432",
                "AF_DB_MAIN_DATABASE=alphafrog_test",
                "AF_DB_MAIN_USER=alphafrog",
                "AF_DB_MAIN_PASSWORD=alphafrog",
                "AF_CONFIG_NACOS_ENABLED=false",
                "alphafrog.config.nacos.enabled=false",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.rabbitmq.listener.direct.auto-startup=false",
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=6379",
                "spring.data.redis.password=",
                "spring.autoconfigure.exclude=" +
                        "org.apache.dubbo.spring.boot.autoconfigure.DubboAutoConfiguration," +
                        "org.apache.dubbo.spring.boot.autoconfigure.DubboRelaxedBindingAutoConfiguration," +
                        "org.apache.dubbo.spring.boot.autoconfigure.DubboRelaxedBinding2AutoConfiguration," +
                        "org.apache.dubbo.spring.boot.autoconfigure.DubboListenerAutoConfiguration",
                "dubbo.consumer.check=false",
                "dubbo.enabled=false",
                "dubbo.registry.address=N/A",
                "dubbo.registry.register=false",
                "dubbo.application.qos-enable=false"
        }
)
class FrontendApplicationTest {

    @Test
    void contextLoads() {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "world.willfrog.alphafrogmicro.frontend",
            "world.willfrog.alphafrogmicro.common"
    }, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = FrontendApplication.class))
    @MapperScan("world.willfrog.alphafrogmicro.common.dao")
    static class TestFrontendApplication {
    }
}
