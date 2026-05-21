package world.willfrog.alphafrogmicro.frontend.service.agent;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;

import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class AgentRunResultCacheService {

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @DubboReference
    private AgentDubboService agentDubboService;

    @Value("${agent.run-result.cache-ttl-seconds:30}")
    private long cacheTtlSeconds;

    public AgentRunResultMessage getRunResult(String userId, String runId) {
        if (userId == null || userId.isBlank() || runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("userId and runId are required");
        }
        long ttlMs = Math.max(1L, cacheTtlSeconds) * 1000L;
        long now = System.currentTimeMillis();
        String key = cacheKey(userId, runId);
        CacheEntry hit = cache.get(key);
        if (hit != null && hit.expiresAtMs() > now) {
            return hit.result();
        }
        AgentRunResultMessage result = agentDubboService.getResult(
                GetAgentRunResultRequest.newBuilder()
                        .setUserId(userId)
                        .setId(runId)
                        .build()
        );
        cache.put(key, new CacheEntry(result, now + ttlMs));
        return result;
    }

    void clearForTest() {
        cache.clear();
    }

    private static String cacheKey(String userId, String runId) {
        return userId + ":" + runId;
    }

    private record CacheEntry(AgentRunResultMessage result, long expiresAtMs) {
    }
}
