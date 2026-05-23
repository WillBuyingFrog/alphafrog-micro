package world.willfrog.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.tools.router.ToolWeightedLimitService;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolWeightedLimitServiceTest {

    @Test
    void effectiveWeight_shouldScaleWithBatchItems() {
        AgentLlmProperties properties = baseProperties();
        ToolWeightedLimitService service = new ToolWeightedLimitService(properties, new ObjectMapper());

        int weight = service.previewEffectiveWeight("searchAssetInfo", Map.of("query", "a|b|c"));
        assertEquals(3, weight);
    }

    @Test
    void effectiveWeight_shouldCapByMaxBatchItems() {
        AgentLlmProperties properties = baseProperties();
        ToolWeightedLimitService service = new ToolWeightedLimitService(properties, new ObjectMapper());

        int weight = service.previewEffectiveWeight("searchAssetInfo", Map.of("query", "a|b|c|d|e|f|g|h|i"));
        assertEquals(8, weight);
    }

    @Test
    void tryAcquire_shouldRejectWhenPoolExhausted() {
        AgentLlmProperties properties = baseProperties();
        properties.getRuntime().getParallel().getToolWeightedLimit().setMaxWeight(2);
        ToolWeightedLimitService service = new ToolWeightedLimitService(properties, new ObjectMapper());

        Map<String, Object> params = Map.of("query", "a|b");
        var first = service.tryAcquire("searchAssetInfo", params);
        assertTrue(first.isPresent());
        assertFalse(service.tryAcquire("searchAssetInfo", params).isPresent());

        first.get().release();
        assertTrue(service.tryAcquire("searchAssetInfo", params).isPresent());
        service.tryAcquire("searchAssetInfo", params).ifPresent(ToolWeightedLimitService.WeightLease::release);
    }

    @Test
    void tryAcquire_shouldSkipUnlistedTools() {
        AgentLlmProperties properties = baseProperties();
        ToolWeightedLimitService service = new ToolWeightedLimitService(properties, new ObjectMapper());

        assertTrue(service.tryAcquire("getStockDaily", Map.of("tsCode", "000001.SZ")).isPresent());
        assertEquals(0, service.previewEffectiveWeight("getStockDaily", Map.of("tsCode", "000001.SZ")));
    }

    private AgentLlmProperties baseProperties() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Parallel parallel = new AgentLlmProperties.Parallel();
        AgentLlmProperties.ToolWeightedLimit limit = new AgentLlmProperties.ToolWeightedLimit();
        limit.setEnabled(true);
        limit.setMaxWeight(12);
        limit.setDefaultWeight(2);

        AgentLlmProperties.ToolWeightEntry searchAssetInfo = new AgentLlmProperties.ToolWeightEntry();
        searchAssetInfo.setWeight(1);
        searchAssetInfo.setMaxBatchItems(8);
        Map<String, AgentLlmProperties.ToolWeightEntry> tools = new HashMap<>();
        tools.put("searchAssetInfo", searchAssetInfo);
        limit.setTools(tools);

        parallel.setToolWeightedLimit(limit);
        runtime.setParallel(parallel);
        properties.setRuntime(runtime);
        return properties;
    }
}
