package world.willfrog.agent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.config.AgentLlmProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentModelCatalogServiceTest {

    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;

    @Test
    void listModels_shouldSupportLegacyTopLevelModelMetadata() {
        AgentLlmProperties properties = new AgentLlmProperties();
        properties.setModels(List.of("openai/gpt-5.2"));

        AgentLlmProperties.Endpoint openrouter = new AgentLlmProperties.Endpoint();
        openrouter.setBaseUrl("https://openrouter.ai/api/v1");
        properties.setEndpoints(Map.of("openrouter", openrouter));

        AgentLlmProperties.ModelMetadata metadata = new AgentLlmProperties.ModelMetadata();
        metadata.setDisplayName("GPT-5.2");
        metadata.setBaseRate(1.0D);
        metadata.setFeatures(List.of("reasoning", "code"));
        properties.setModelMetadata(Map.of("openai/gpt-5.2", metadata));

        AgentLlmProperties.JudgeRoute route = new AgentLlmProperties.JudgeRoute();
        route.setEndpointName("openrouter");
        route.setModels(List.of("openai/gpt-5.2"));
        properties.getRuntime().getJudge().setRoutes(List.of(route));

        when(localConfigLoader.current()).thenReturn(Optional.empty());

        AgentModelCatalogService service = new AgentModelCatalogService(properties, localConfigLoader);
        List<AgentModelCatalogService.ModelCatalogItem> models = service.listModels();

        assertEquals(1, models.size());
        AgentModelCatalogService.ModelCatalogItem item = models.get(0);
        assertEquals("openai/gpt-5.2", item.id());
        assertEquals("openrouter", item.endpoint());
        assertEquals(List.of("reasoning", "code"), item.features());
        assertTrue(item.validProviders().isEmpty());
    }

    @Test
    void listModels_shouldReadEndpointScopedModelsAndValidProviders() {
        AgentLlmProperties properties = new AgentLlmProperties();

        AgentLlmProperties.Endpoint openrouter = new AgentLlmProperties.Endpoint();
        openrouter.setBaseUrl("https://openrouter.ai/api/v1");
        AgentLlmProperties.ModelMetadata kimi = new AgentLlmProperties.ModelMetadata();
        kimi.setDisplayName("Kimi K2.5");
        kimi.setBaseRate(0.3D);
        kimi.setFeatures(List.of("reasoning"));
        kimi.setValidProviders(List.of("moonshotai/int4", "fireworks"));
        openrouter.setModels(Map.of("moonshotai/kimi-k2.5", kimi));

        Map<String, AgentLlmProperties.Endpoint> endpoints = new LinkedHashMap<>();
        endpoints.put("openrouter", openrouter);
        properties.setEndpoints(endpoints);

        when(localConfigLoader.current()).thenReturn(Optional.empty());

        AgentModelCatalogService service = new AgentModelCatalogService(properties, localConfigLoader);
        List<AgentModelCatalogService.ModelCatalogItem> models = service.listModels();

        assertEquals(1, models.size());
        AgentModelCatalogService.ModelCatalogItem item = models.get(0);
        assertEquals("moonshotai/kimi-k2.5", item.id());
        assertEquals("openrouter", item.endpoint());
        assertEquals(List.of("moonshotai/int4", "fireworks"), item.validProviders());
        assertEquals(0.3D, item.baseRate());
    }

    @Test
    void listModels_shouldKeepDashScopeEndpointWhenOnlyRegionConfigured() {
        AgentLlmProperties properties = new AgentLlmProperties();

        AgentLlmProperties.Endpoint dashscope = new AgentLlmProperties.Endpoint();
        dashscope.setRegion("singapore");
        AgentLlmProperties.ModelMetadata qwenPlus = new AgentLlmProperties.ModelMetadata();
        qwenPlus.setDisplayName("Qwen Plus");
        qwenPlus.setBaseRate(0.2D);
        dashscope.setModels(Map.of("qwen-plus", qwenPlus));

        AgentLlmProperties local = new AgentLlmProperties();
        local.setEndpoints(Map.of("dashscope", dashscope));

        when(localConfigLoader.current()).thenReturn(Optional.of(local));

        AgentModelCatalogService service = new AgentModelCatalogService(properties, localConfigLoader);
        List<AgentModelCatalogService.ModelCatalogItem> models = service.listModels();

        assertEquals(1, models.size());
        AgentModelCatalogService.ModelCatalogItem item = models.get(0);
        assertEquals("qwen-plus", item.id());
        assertEquals("dashscope", item.endpoint());
    }

    @Test
    void listModels_shouldStillFilterNonDashScopeEndpointWithoutBaseUrl() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Endpoint openrouter = new AgentLlmProperties.Endpoint();
        openrouter.setBaseUrl("https://openrouter.ai/api/v1");
        properties.setEndpoints(Map.of("openrouter", openrouter));

        AgentLlmProperties.Endpoint custom = new AgentLlmProperties.Endpoint();
        custom.setRegion("singapore");
        AgentLlmProperties.ModelMetadata customModel = new AgentLlmProperties.ModelMetadata();
        customModel.setDisplayName("Custom");
        customModel.setBaseRate(0.2D);
        custom.setModels(Map.of("custom-model", customModel));

        AgentLlmProperties local = new AgentLlmProperties();
        local.setEndpoints(Map.of("custom", custom));

        when(localConfigLoader.current()).thenReturn(Optional.of(local));

        AgentModelCatalogService service = new AgentModelCatalogService(properties, localConfigLoader);
        List<AgentModelCatalogService.ModelCatalogItem> models = service.listModels();

        assertFalse(models.stream().anyMatch(item -> "custom".equals(item.endpoint())));
    }
}
