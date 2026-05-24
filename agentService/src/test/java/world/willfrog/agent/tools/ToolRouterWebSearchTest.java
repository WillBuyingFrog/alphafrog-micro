package world.willfrog.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.router.PythonStaticPrecheckService;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.tools.router.ToolResultCacheService;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

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
                new PythonStaticPrecheckService(),
                new AgentLlmProperties(),
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

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldAllowSearchWebWhenCapabilityEnabled() {
        AgentContext.setWebSearchEnabled(true);
        SearchTools searchTools = mock(SearchTools.class);
        when(searchTools.searchWeb(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), anyString(), anyInt()))
                .thenReturn("{\"ok\":true,\"tool\":\"searchWeb\",\"data\":{\"items\":[]},\"error\":null}");
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
                new PythonStaticPrecheckService(),
                new AgentLlmProperties(),
                cacheService,
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta("searchWeb", Map.of("query", "q"));

        assertTrue(result.isSuccess());
        verify(searchTools).searchWeb(eq("q"), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), anyString(), anyInt());
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRouteCheckParallelLimits() {
        MarketDataTools marketDataTools = mock(MarketDataTools.class);
        when(marketDataTools.checkParallelLimits())
                .thenReturn("{\"ok\":true,\"tool\":\"checkParallelLimits\",\"data\":{\"search\":{\"maxItems\":3}},\"error\":null}");
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
        AgentObservabilityService observabilityService = mock(AgentObservabilityService.class);

        ToolRouter router = new ToolRouter(
                marketDataTools,
                mock(RagTools.class),
                mock(SearchTools.class),
                mock(PythonSandboxTools.class),
                new PythonStaticPrecheckService(),
                new AgentLlmProperties(),
                cacheService,
                observabilityService,
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta("checkParallelLimits", Map.of());

        assertTrue(result.isSuccess());
        verify(marketDataTools).checkParallelLimits();
        verify(observabilityService, never()).recordToolCall(
                anyString(), anyString(), anyString(), any(), anyString(), anyLong(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyString(), anyString(), anyLong(), anyLong(), anyString()
        );
    }
}
