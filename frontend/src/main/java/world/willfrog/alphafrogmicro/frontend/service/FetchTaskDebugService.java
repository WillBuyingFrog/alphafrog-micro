package world.willfrog.alphafrogmicro.frontend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 从 Redis 读取失败任务的调试请求记录，供前端任务详情展示。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FetchTaskDebugService {

    private final StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "fetch:task:debug:";

    /**
     * 获取指定任务暂存的调试请求列表（JSON 数组字符串）的 Base64 编码。
     * 若 Redis 中无记录或已过期，返回 null。
     */
    public String getDebugRequestsBase64(String taskUuid) {
        if (taskUuid == null || taskUuid.isBlank()) {
            return null;
        }
        try {
            String key = KEY_PREFIX + taskUuid;
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("从 Redis 读取调试请求失败，taskUuid={}", taskUuid, e);
            return null;
        }
    }
}
