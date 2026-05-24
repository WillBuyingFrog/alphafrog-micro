package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.SearchEvidenceJudgeService;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRouterToolProviderTest {

    @Mock
    private ToolRouter toolRouter;

    private ToolRouterToolProvider provider;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        MarketDataTools marketDataTools = new MarketDataTools(
                mock(DatasetWriter.class),
                mock(DatasetRegistry.class),
                null,
                new AgentLlmProperties(),
                objectMapper
        );
        RagTools ragTools = new RagTools(objectMapper);
        SearchTools searchTools = new SearchTools(objectMapper, mock(SearchEvidenceJudgeService.class));
        PythonSandboxTools pythonSandboxTools = new PythonSandboxTools(objectMapper);

        provider = new ToolRouterToolProvider(
                toolRouter,
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                objectMapper
        );
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
        LangchainDatasetRefContext.clear();
        LangchainRepeatedToolCallContext.clear();
    }

    @Test
    void provideTools_shouldHideSearchWhenWebSearchDisabled() {
        ToolProviderResult result = provider.provideTools(request(Map.of(
                LangchainToolInvocationKeys.WEB_SEARCH_ENABLED, false,
                LangchainToolInvocationKeys.CODE_INTERPRETER_ENABLED, false
        )));

        Set<String> toolNames = result.tools().keySet().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());

        assertFalse(toolNames.contains("searchWeb"));
        assertFalse(toolNames.contains("executePython"));
        assertTrue(toolNames.contains("getStockInfo"));
        assertTrue(toolNames.contains("ragSearch"));
    }

    @Test
    void provideTools_shouldExposeOptionalToolsWhenEnabled() {
        ToolProviderResult result = provider.provideTools(request(Map.of(
                LangchainToolInvocationKeys.WEB_SEARCH_ENABLED, true,
                LangchainToolInvocationKeys.CODE_INTERPRETER_ENABLED, true
        )));

        Set<String> toolNames = result.tools().keySet().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());

        assertTrue(toolNames.contains("searchWeb"));
        assertTrue(toolNames.contains("executePython"));
    }

    @Test
    void toolExecutor_shouldDelegateToToolRouter() {
        when(toolRouter.invokeWithMeta(eq("searchWeb"), anyMap()))
                .thenReturn(ToolRouter.ToolInvocationResult.builder()
                        .output("{\"ok\":true}")
                        .success(true)
                        .durationMs(1)
                        .build());

        ToolProviderResult result = provider.provideTools(request(Map.of(
                LangchainToolInvocationKeys.WEB_SEARCH_ENABLED, true
        )));

        ToolExecutor executor = result.toolExecutorByName("searchWeb");
        assertNotNull(executor);

        String output = executor.execute(
                ToolExecutionRequest.builder()
                        .name("searchWeb")
                        .arguments("{\"query\":\"512800\"}")
                        .build(),
                "memory-1"
        );

        assertEquals("{\"ok\":true}", output);
        verify(toolRouter).invokeWithMeta(eq("searchWeb"), eq(Map.of("query", "512800")));
    }

    @Test
    void toolExecutor_shouldAppendDatasetRetryHintForInvalidExecutePythonDataset() {
        LangchainDatasetRefContext.set(new java.util.LinkedHashMap<>(Map.of(
                "dataset-hs300", "/sandbox/input/dataset-hs300",
                "dataset-zz500", "/sandbox/input/dataset-zz500"
        )));
        when(toolRouter.invokeWithMeta(eq("executePython"), anyMap()))
                .thenReturn(ToolRouter.ToolInvocationResult.builder()
                        .output("{\"ok\":false,\"error\":{\"code\":\"TASK_FAILED\",\"message\":\"dataset_id directory not found\"}}")
                        .success(false)
                        .durationMs(1)
                        .build());

        ToolProviderResult result = provider.provideTools(request(Map.of(
                LangchainToolInvocationKeys.CODE_INTERPRETER_ENABLED, true
        )));

        ToolExecutor executor = result.toolExecutorByName("executePython");
        assertNotNull(executor);

        String output = executor.execute(
                ToolExecutionRequest.builder()
                        .name("executePython")
                        .arguments("{\"dataset_ids\":\"placeholder\",\"code\":\"print(1)\"}")
                        .build(),
                "memory-1"
        );

        assertTrue(output.contains("_retry_hint_"));
        assertTrue(output.contains("dataset-hs300"));
        assertTrue(output.contains("dataset-zz500"));
        assertTrue(output.contains("placeholder/data/test"));
    }

    @Test
    void toolExecutor_shouldWarnThenBlockRepeatedIdenticalToolCalls() {
        when(toolRouter.invokeWithMeta(eq("searchWeb"), anyMap()))
                .thenReturn(ToolRouter.ToolInvocationResult.builder()
                        .output("{\"ok\":true}")
                        .success(true)
                        .durationMs(1)
                        .build());

        ToolProviderResult result = provider.provideTools(request(Map.of(
                LangchainToolInvocationKeys.WEB_SEARCH_ENABLED, true
        )));
        ToolExecutor executor = result.toolExecutorByName("searchWeb");
        assertNotNull(executor);
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .name("searchWeb")
                .arguments("{\"query\":\"512800\",\"limit\":3}")
                .build();

        String first = executor.execute(toolRequest, "memory-1");
        String second = executor.execute(toolRequest, "memory-1");
        String third = executor.execute(toolRequest, "memory-1");

        assertEquals("{\"ok\":true}", first);
        assertTrue(second.contains("_retry_hint_"));
        assertTrue(second.contains("Do not call searchWeb again with identical arguments"));
        assertTrue(third.contains("\"code\":\"REPEATED_TOOL_CALL\""));
        assertTrue(third.contains("_retry_hint_"));
        verify(toolRouter, times(2)).invokeWithMeta(eq("searchWeb"), eq(Map.of("query", "512800", "limit", 3)));
    }

    @Test
    void isDynamic_shouldBeTrue() {
        assertTrue(provider.isDynamic());
    }

    private static ToolProviderRequest request(Map<String, Object> params) {
        return ToolProviderRequest.builder()
                .userMessage(UserMessage.from("test"))
                .invocationContext(InvocationContext.builder()
                        .userMessage(UserMessage.from("test"))
                        .invocationParameters(InvocationParameters.from(params))
                        .timestampNow()
                        .build())
                .build();
    }
}
