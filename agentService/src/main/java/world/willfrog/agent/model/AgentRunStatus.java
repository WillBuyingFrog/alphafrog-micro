package world.willfrog.agent.model;

public enum AgentRunStatus {
    RECEIVED,
    PLANNING,
    EXECUTING,
    WAITING,
    SUMMARIZING,
    COMPLETED,
    FAILED,
    CANCELED,
    EXPIRED;
}
