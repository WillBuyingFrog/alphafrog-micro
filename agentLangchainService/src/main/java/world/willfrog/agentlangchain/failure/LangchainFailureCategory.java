package world.willfrog.agentlangchain.failure;

public enum LangchainFailureCategory {
    BUDGET_EXCEEDED,
    TOOL_ERROR,
    REPEATED_TOOL_CALL,
    PARAM_RETRY_WITH_HINT,
    INFRA_RETRY,
    EMPTY_OUTPUT,
    UNKNOWN
}
