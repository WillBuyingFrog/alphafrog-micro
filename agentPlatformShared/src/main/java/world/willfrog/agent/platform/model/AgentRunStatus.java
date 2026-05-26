package world.willfrog.agent.platform.model;

public enum AgentRunStatus {
    RECEIVED,
    PLANNING,
    EXECUTING,
    WAITING,
    SUMMARIZING,
    COMPLETED,
    FAILED,
    CANCELING,  // 正在取消中，用于通知执行线程停止
    CANCELED,
    EXPIRED;
}
