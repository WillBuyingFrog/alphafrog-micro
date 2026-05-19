package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.config.AgentLlmProperties;
import world.willfrog.agent.config.StressTestProperties;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.workflow.PythonStaticPrecheckService;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolRouterExecutePythonPrecheckTest {

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRejectExecutePythonBeforeSandboxWhenDatasetIdsMissing() throws Exception {
        PythonSandboxTools pythonSandboxTools = mock(PythonSandboxTools.class);
        ToolResultCacheService cacheService = mock(ToolResultCacheService.class);
        when(cacheService.executeWithCache(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            Supplier<ToolResultCacheService.ToolExecutionOutcome> supplier = inv.getArgument(3);
            ToolResultCacheService.ToolExecutionOutcome outcome = supplier.get();
            return ToolResultCacheService.CachedToolCallResult.builder()
                    .result(outcome.getResult())
                    .durationMs(outcome.getDurationMs())
                    .success(outcome.isSuccess())
                    .build();
        });

        ToolRouter router = new ToolRouter(
                mock(MarketDataTools.class),
                mock(RagTools.class),
                mock(SearchTools.class),
                pythonSandboxTools,
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "executePython",
                Map.of("code", "print(1)")
        );

        assertFalse(result.isSuccess());
        JsonNode json = new ObjectMapper().readTree(result.getOutput());
        assertEquals("MISSING_DATASET_IDS", json.path("error").path("code").asText());
        assertTrue(json.path("error").path("details").path("pre_validation_failed").asBoolean());
        verify(pythonSandboxTools, never()).executePython(anyString(), anyString(), anyString(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldCallSandboxWhenPrecheckPasses() {
        PythonSandboxTools pythonSandboxTools = mock(PythonSandboxTools.class);
        when(pythonSandboxTools.executePython(eq("print(1)"), eq("ds-1"), anyString(), any()))
                .thenReturn("{\"ok\":true,\"tool\":\"executePython\",\"data\":{},\"error\":null}");
        ToolResultCacheService cacheService = mock(ToolResultCacheService.class);
        when(cacheService.executeWithCache(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            Supplier<ToolResultCacheService.ToolExecutionOutcome> supplier = inv.getArgument(3);
            ToolResultCacheService.ToolExecutionOutcome outcome = supplier.get();
            return ToolResultCacheService.CachedToolCallResult.builder()
                    .result(outcome.getResult())
                    .durationMs(outcome.getDurationMs())
                    .success(outcome.isSuccess())
                    .build();
        });

        ToolRouter router = new ToolRouter(
                mock(MarketDataTools.class),
                mock(RagTools.class),
                mock(SearchTools.class),
                pythonSandboxTools,
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "executePython",
                Map.of("code", "print(1)", "dataset_ids", "ds-1")
        );

        assertTrue(result.isSuccess());
        verify(pythonSandboxTools).executePython(eq("print(1)"), eq("ds-1"), anyString(), any());
    }

    private AgentLlmProperties llmPropertiesWithStaticPrecheck(boolean enabled) {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Execution execution = new AgentLlmProperties.Execution();
        execution.setStaticPrecheckEnabled(enabled);
        runtime.setExecution(execution);
        properties.setRuntime(runtime);
        return properties;
    }
}
