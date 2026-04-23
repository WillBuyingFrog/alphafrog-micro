package world.willfrog.alphafrogmicro.common.config.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Nacos Config Client 配置。
 * 供 adminService、frontend 等需要发布/订阅配置的服务使用。
 */
@Configuration
@ConditionalOnProperty(prefix = "alphafrog.config.nacos", name = "enabled", havingValue = "true")
public class NacosConfigClientConfig {

    @Value("${alphafrog.config.nacos.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${alphafrog.config.nacos.namespace:}")
    private String namespace;

    @Bean(destroyMethod = "shutDown")
    public ConfigService nacosConfigService() throws Exception {
        Properties properties = new Properties();
        properties.put("serverAddr", serverAddr);
        if (namespace != null && !namespace.isBlank()) {
            properties.put("namespace", namespace);
        }
        return NacosFactory.createConfigService(properties);
    }
}
