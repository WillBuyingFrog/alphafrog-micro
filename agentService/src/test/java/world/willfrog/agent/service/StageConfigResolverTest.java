package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.config.StageLlmConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StageConfigResolverTest {

    @Test
    void mergeStageLlmConfigShouldFallbackByField() {
        StageConfigResolver resolver = new StageConfigResolver(
                new AgentLlmProperties(),
                null,
                new ObjectMapper()
        );

        StageLlmConfig client = new StageLlmConfig();
        client.setModelName("client-model");
        client.setTemperature(0.3D);

        StageLlmConfig local = new StageLlmConfig();
        local.setEndpointName("local-endpoint");
        local.setModelName("local-model");
        local.setReasoningEffort("high");
        local.setTemperature(0.8D);
        local.setMaxTokens(2048);

        StageLlmConfig merged = ReflectionTestUtils.invokeMethod(
                resolver,
                "mergeStageLlmConfig",
                client,
                local
        );

        assertEquals("local-endpoint", merged.getEndpointName());
        assertEquals("client-model", merged.getModelName());
        assertEquals("high", merged.getReasoningEffort());
        assertEquals(0.3D, merged.getTemperature());
        assertEquals(2048, merged.getMaxTokens());
        assertNull(client.getEndpointName());
        assertNull(client.getReasoningEffort());
        assertNull(client.getMaxTokens());
    }

    @Test
    void resolveShouldTreatOptionalOnlyStageAsExplicitPartialConfig() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Planning planning = new AgentLlmProperties.Planning();
        planning.setEndpointName("base-endpoint");
        planning.setModelName("base-model");
        runtime.setPlanning(planning);
        properties.setRuntime(runtime);

        AgentLlmLocalConfigLoader localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(java.util.Optional.empty());

        StageConfigResolver resolver = new StageConfigResolver(
                properties,
                localConfigLoader,
                new ObjectMapper()
        );

        RunStageConfigAccessor accessor = new RunStageConfigAccessor(resolver.resolve("""
                {"stage_config_json":{"planning":{"temperature":0.2,"maxTokens":1024}}}
                """));

        assertEquals("base-endpoint", accessor.planning().getEndpointName());
        assertEquals("base-model", accessor.planning().getModelName());
        assertEquals(0.2D, accessor.planning().getTemperature());
        assertEquals(1024, accessor.planning().getMaxTokens());
    }

    @Test
    void resolveShouldParseStageProviderOrderArrayAndCommaString() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmLocalConfigLoader localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(java.util.Optional.empty());

        StageConfigResolver resolver = new StageConfigResolver(
                properties,
                localConfigLoader,
                new ObjectMapper()
        );

        var config = resolver.resolve("""
                {"stage_config_json":{
                  "planning":{"endpointName":"openrouter","modelName":"planner","providerOrder":["moonshotai/int4","novita"]},
                  "execution":{"endpointName":"openrouter","modelName":"executor","provider_order":"fireworks, deepinfra"},
                  "final_answer":{"endpointName":"openrouter","modelName":"final","providers":["novita"]}
                }}
                """);

        assertEquals(List.of("moonshotai/int4", "novita"), config.getPlanning().getProviderOrder());
        assertEquals(List.of("fireworks", "deepinfra"), config.getExecution().getProviderOrder());
        assertEquals(List.of("novita"), config.getFinalAnswer().getProviderOrder());
    }

    private record RunStageConfigAccessor(world.willfrog.agent.config.RunStageConfig config) {
        StageLlmConfig planning() {
            return config.getPlanning();
        }
    }
}
