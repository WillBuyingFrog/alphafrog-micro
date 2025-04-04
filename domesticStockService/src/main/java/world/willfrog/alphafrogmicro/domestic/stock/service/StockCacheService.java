package world.willfrog.alphafrogmicro.domestic.stock.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@Slf4j
public class StockCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public StockCacheService(RedisTemplate<String, Object> redisTemplate,
                             ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 从缓存获取数据，如果缓存中不存在则从数据库获取并缓存
     * @param key 缓存键
     * @param dbFallback 从数据库获取数据的回调函数
     * @param expireTimestamp 过期时间
     * @param timeUnit 时间单位
     * @return 查询结果
     */
    public <T> T getWithCache(String key, Supplier<T> dbFallback, long expireTimestamp, TimeUnit timeUnit,
                              TypeReference<T> typeRef) {
        // 从缓存获取 JSON 字符串
        String cachedJson = (String) redisTemplate.opsForValue().get(key);
        if (cachedJson != null) {
            // 使用 TypeReference 反序列化
            return deserializeFromJson(cachedJson, typeRef);
        }

        T value = dbFallback.get();
        if (value != null) {
            // 序列化为 JSON 存储
            String json = serializeToJson(value);

            // 处理异常情况
            if (json == null) {
                log.error("Serialization failed. See previous logs for details.");
                return null;
            }
            redisTemplate.opsForValue().set(key, json, expireTimestamp, timeUnit);
        }
        return value;
    }


    /**
     * 从缓存获取列表数据
     */
    public <T> List<T> getListWithCache(String key, Supplier<List<T>> dbFallback, long expireTimestamp, TimeUnit timeUnit, Class<T> elementType) {
        // 从缓存获取JSON字符串
        String cachedJson = (String) redisTemplate.opsForValue().get(key);
        if (cachedJson != null) {
            try {
                // 使用TypeFactory指定具体的集合类型
                JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
                return objectMapper.readValue(cachedJson, listType);
            } catch (JsonProcessingException e) {
                log.error("Deserialization failed", e);
                return null;
            }
        }

        // 从数据库获取数据
        List<T> value = dbFallback.get();
        if (value != null) {
            String json = serializeToJson(value);
            if (json != null) {
                long finalExpireTimestamp = expireTimestamp + ThreadLocalRandom.current().nextInt(0, 10);
                redisTemplate.opsForValue().set(key, json, finalExpireTimestamp, timeUnit);
            }
        }
        return value;
    }

    // 序列化工具方法
    private String serializeToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("Serialization failed", e);
            return null;
        }
    }

    // 反序列化工具方法
    private <T> T deserializeFromJson(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.error("Deserialization failed", e);
            return null;
        }
    }
}
