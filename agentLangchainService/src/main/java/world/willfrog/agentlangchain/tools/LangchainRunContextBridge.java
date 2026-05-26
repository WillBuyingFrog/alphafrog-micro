package world.willfrog.agentlangchain.tools;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolProviderRequest;
import world.willfrog.agent.platform.context.AgentContext;

/**
 * Maps LangChain4j invocation parameters to {@link AgentContext} for ToolRouter observability/budget.
 */
final class LangchainRunContextBridge {

    private LangchainRunContextBridge() {
    }

    static void apply(ToolProviderRequest request) {
        if (request == null) {
            return;
        }
        apply(request.invocationParameters());
    }

    static void apply(InvocationParameters parameters) {
        if (parameters == null) {
            return;
        }
        String runId = parameters.get(LangchainToolInvocationKeys.RUN_ID);
        if (runId != null) {
            AgentContext.setRunId(runId);
        }
        String userId = parameters.get(LangchainToolInvocationKeys.USER_ID);
        if (userId != null) {
            AgentContext.setUserId(userId);
        }
        Boolean webSearchEnabled = parameters.get(LangchainToolInvocationKeys.WEB_SEARCH_ENABLED);
        if (webSearchEnabled != null) {
            AgentContext.setWebSearchEnabled(webSearchEnabled);
        }
    }
}
