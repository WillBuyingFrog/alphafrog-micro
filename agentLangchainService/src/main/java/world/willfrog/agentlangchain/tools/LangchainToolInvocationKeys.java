package world.willfrog.agentlangchain.tools;

/**
 * InvocationParameters keys for wiring run context into {@link ToolRouterToolProvider}.
 */
public final class LangchainToolInvocationKeys {

    public static final String RUN_ID = "agent.runId";
    public static final String USER_ID = "agent.userId";
    public static final String WEB_SEARCH_ENABLED = "agent.webSearchEnabled";
    public static final String CODE_INTERPRETER_ENABLED = "agent.codeInterpreterEnabled";

    private LangchainToolInvocationKeys() {
    }
}
