package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.RequiredArgsConstructor;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j {@link ToolProvider} adapter: exposes legacy {@link ToolRouter} tools to AiServices
 * while preserving budget/cache/observability execution semantics.
 */
@RequiredArgsConstructor
public class ToolRouterToolProvider implements ToolProvider {

    private final ToolRouter toolRouter;
    private final MarketDataTools marketDataTools;
    private final RagTools ragTools;
    private final SearchTools searchTools;
    private final PythonSandboxTools pythonSandboxTools;
    private final ObjectMapper objectMapper;

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        LangchainRunContextBridge.apply(request);

        boolean webSearchEnabled = resolveBoolean(
                request.invocationParameters(),
                LangchainToolInvocationKeys.WEB_SEARCH_ENABLED,
                AgentContext.isWebSearchEnabled(),
                false
        );
        boolean codeInterpreterEnabled = resolveBoolean(
                request.invocationParameters(),
                LangchainToolInvocationKeys.CODE_INTERPRETER_ENABLED,
                true,
                true
        );

        List<ToolSpecification> specifications = ToolCatalogBuilder.buildSpecifications(
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                webSearchEnabled,
                codeInterpreterEnabled
        );

        ToolExecutor executor = new ToolRouterToolExecutor(toolRouter, objectMapper);
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        for (ToolSpecification specification : specifications) {
            tools.put(specification, executor);
        }
        return ToolProviderResult.builder()
                .addAll(tools)
                .build();
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    private static boolean resolveBoolean(InvocationParameters parameters,
                                            String key,
                                            boolean contextFallback,
                                            boolean defaultValue) {
        if (parameters != null && parameters.containsKey(key)) {
            Boolean value = parameters.get(key);
            return value != null ? value : defaultValue;
        }
        if (AgentContext.getRunId() != null) {
            return contextFallback;
        }
        return defaultValue;
    }
}
