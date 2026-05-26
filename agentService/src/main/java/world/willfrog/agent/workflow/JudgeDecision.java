package world.willfrog.agent.workflow;

public enum JudgeDecision {
    CONTINUE_WITH_RECOVERY_PARAMS,
    PATCH_PLAN,
    FALLBACK_TO_LINEAR,
    RETRY,
    FAIL,
    ABORT   // 关键步骤失败，终止整个 workflow
}
