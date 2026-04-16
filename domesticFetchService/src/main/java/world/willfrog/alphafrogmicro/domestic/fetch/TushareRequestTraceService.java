package world.willfrog.alphafrogmicro.domestic.fetch;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 收集并暂存单个叶子任务向 TuShare 发送的所有 HTTP 请求体。
 * 任务成功时丢弃，任务失败时写入 Redis（TTL 12 小时），供前端调试使用。
 */
@Service
@Slf4j
public class TushareRequestTraceService {

    private final StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "fetch:task:debug:";
    private static final long TTL_HOURS = 12;

    private static final ThreadLocal<List<String>> REQUESTS_HOLDER = ThreadLocal.withInitial(ArrayList::new);

    public TushareRequestTraceService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void start(String taskUuid) {
        REQUESTS_HOLDER.remove();
    }

    public void record(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return;
        }
        REQUESTS_HOLDER.get().add(requestBody);
    }

    public void persistOnFailure(String taskUuid) {
        List<String> requests = REQUESTS_HOLDER.get();
        if (requests == null || requests.isEmpty()) {
            return;
        }
        try {
            String value = JSON.toJSONString(requests);
            String key = KEY_PREFIX + taskUuid;
            redisTemplate.opsForValue().set(key, value, TTL_HOURS, TimeUnit.HOURS);
            log.debug("已暂存 {} 条调试请求到 Redis，taskUuid={}", requests.size(), taskUuid);
        } catch (Exception e) {
            log.error("暂存调试请求到 Redis 失败，taskUuid={}", taskUuid, e);
        }
    }

    public void clear() {
        REQUESTS_HOLDER.remove();
    }
}
