package world.willfrog.agentlangchain.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the run-scoped tool catalog, mirroring legacy {@code AgentRunExecutor} capability filtering.
 */
final class ToolCatalogBuilder {

    private ToolCatalogBuilder() {
    }

    static List<ToolSpecification> buildSpecifications(MarketDataTools marketDataTools,
                                                       RagTools ragTools,
                                                       SearchTools searchTools,
                                                       PythonSandboxTools pythonSandboxTools,
                                                       boolean webSearchEnabled,
                                                       boolean codeInterpreterEnabled) {
        List<ToolSpecification> specifications = new ArrayList<>();
        specifications.addAll(ToolSpecifications.toolSpecificationsFrom(marketDataTools));
        specifications.addAll(ToolSpecifications.toolSpecificationsFrom(ragTools));
        if (webSearchEnabled) {
            specifications.addAll(ToolSpecifications.toolSpecificationsFrom(searchTools));
        }
        if (codeInterpreterEnabled) {
            specifications.addAll(ToolSpecifications.toolSpecificationsFrom(pythonSandboxTools));
        }
        return dedupeByName(specifications);
    }

    private static List<ToolSpecification> dedupeByName(List<ToolSpecification> specifications) {
        Map<String, ToolSpecification> byName = new LinkedHashMap<>();
        for (ToolSpecification specification : specifications) {
            byName.putIfAbsent(specification.name(), specification);
        }
        return List.copyOf(byName.values());
    }
}
