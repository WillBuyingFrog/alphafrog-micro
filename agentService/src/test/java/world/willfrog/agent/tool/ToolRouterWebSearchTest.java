package world.willfrog.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.config.StressTestProperties;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.service.AgentObservabilityService;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolRouterWebSearchTest {

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRejectSearchWebWhenCapabilityDisabled() {
        SearchTools searchTools = mock(SearchTools.class);
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
                searchTools,
                mock(PythonSandboxTools.class),
                cacheService,
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta("searchWeb", Map.of("query", "q"));

        assertFalse(result.isSuccess());
        verify(searchTools, never()).searchWeb(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), anyString(), anyInt());
    }
}
