package world.willfrog.alphafrogmicro.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * 配置加载状态上报工具。
 *
 * <p>各服务的本地配置 loader 在成功加载动态配置后调用，用于 Admin 侧聚合副本生效状态。</p>
 */
@Slf4j
public final class ConfigLoadStateReporter {

    private ConfigLoadStateReporter() {
    }

    public static void report(StringRedisTemplate redisTemplate,
                              String serviceName,
                              String instanceId,
                              String dataId,
                              String configPath,
                              byte[] contentBytes) {
        if (redisTemplate == null || isBlank(serviceName) || isBlank(instanceId)
                || isBlank(dataId) || contentBytes == null || contentBytes.length == 0) {
            return;
        }
        try {
            String md5 = ConfigJsonCanonicalizer.md5Hex(contentBytes);
            String safePath = configPath == null ? "" : escapeJson(configPath);
            String key = String.format("config:state:%s:%s:%s", serviceName, instanceId, dataId);
            String value = String.format(
                    "{\"md5\":\"%s\",\"loadedAt\":\"%s\",\"configPath\":\"%s\"}",
                    md5, Instant.now(), safePath);
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("[ConfigLoadStateReporter] 配置状态上报失败 dataId={}", dataId, e);
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
