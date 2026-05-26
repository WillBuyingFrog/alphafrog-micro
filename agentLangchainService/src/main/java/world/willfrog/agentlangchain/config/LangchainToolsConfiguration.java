package world.willfrog.agentlangchain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.tools.search.SearchTools;
import world.willfrog.agentlangchain.tools.ToolRouterToolProvider;

/**
 * Registers the ToolRouter-backed {@link ToolProvider} for AiServices (P1 A2).
 */
@Configuration
@ConditionalOnBean({ToolRouter.class, MarketDataTools.class})
public class LangchainToolsConfiguration {

    @Bean
    ToolProvider langchainToolProvider(ToolRouter toolRouter,
                                       MarketDataTools marketDataTools,
                                       RagTools ragTools,
                                       SearchTools searchTools,
                                       PythonSandboxTools pythonSandboxTools,
                                       ObjectMapper objectMapper) {
        return new ToolRouterToolProvider(
                toolRouter,
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                objectMapper
        );
    }
}
